package io.vanillabp.camunda7.processservice;

import java.util.Map;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import lombok.extern.slf4j.Slf4j;

/**
 * Camunda 7 implementation of the VanillaBP {@link MigratableProcessService}. One
 * instance exists per configured adapter id.
 * <p>
 * Camunda 7 runs embedded in the application's JVM and normally shares its database and
 * its transaction. VanillaBP nevertheless progresses the process AFTER the commit, the
 * way every other BPMS does: every progressing operation is scheduled through the
 * phase-two outbox, which is what the adapter SPI asks of every adapter.
 * <p>
 * <b>Why, although sharing the transaction was comfortable:</b> an engine command which
 * loses a concurrency conflict cannot be repeated inside the caller's transaction. Every
 * command joins that transaction, so a failing one leaves it rollback-only - and
 * repeating just the engine part in a transaction of its own would advance the process
 * while the application rolls back, which is the ghost progress the two-phase pattern
 * exists to prevent. An application answering an open task while a timer job of the same
 * workflow runs met that as an {@code OptimisticLockingException} it could do nothing
 * about. Through the outbox the operation is simply repeated.
 * <p>
 * <b>What that costs:</b> the operation happens after the commit, so "the engine has done
 * it when my method returns" no longer holds. What phase one still does is ASK: an
 * embedded engine can answer for free and in the same transaction, so a task which does
 * not exist or a workflow the engine does not know is still reported synchronously, by
 * the core's election probe, before anything is scheduled.
 * <p>
 * Phase two is dispatched at-least-once (outbox retries, crash recovery), so every
 * operation is idempotent: starting skips an existing instance with the same business key
 * and tenant, completing or cancelling a task checks that the task is still there, and
 * correlating checks the subscription.
 * <p>
 * The workflow-aggregate ID maps naturally onto the Camunda 7 <b>business key</b> and the
 * workflow module ID onto the Camunda <b>tenant ID</b> - see
 * {@link #startProcessInstance(String, String, Object)}.
 * <p>
 * Why every probe and the instance lookup behind aggregateChanged filter by the scope of the call
 * rather than by the business key alone is decision 8 in the repository's DECISIONS.md. Why phase
 * one only asks while phase two acts is decision 2 in the repository's DECISIONS.md.
 *
 * @param <A> The workflow aggregate type of the workflows this service serves
 */
@Slf4j
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokSetterMayBeUsed")
public class Camunda7ProcessService<A> implements MigratableProcessService<A> {

  /**
   * The tail of the name of the exception at the root of every expression failure. The
   * ones for an unknown method and for an unknown property extend it, so walking a class
   * up through its ancestors finds them all.
   */
  private static final String EXPRESSION_FAILURE = ".el.ELException";

  private final String adapterId;

  /**
   * The embedded engine's runtime service used to start process instances. Provided by
   * the platform module (Spring Boot) which wires the embedded engine sharing the
   * application's data source and transaction manager.
   */
  private final RuntimeService runtimeService;

  /**
   * The embedded engine's task service - user-task operations.
   */
  private final org.camunda.bpm.engine.TaskService taskService;

  /**
   * The viewer/history API - definitions, BPMN XML and the instance
   * timeline, served by the engine's repository and history services.
   */
  private final Camunda7WorkflowViewer viewer;

  /**
   * The embedded engine's history service - workflow awareness of ENDED workflows
   * and the viewer/history API.
   */
  private final org.camunda.bpm.engine.HistoryService historyService;

  /**
   * The engine's repository service - the deployed BPMN model tells an activity
   * which creates a scope of its own (boundary events, multi-instance) from one
   * which does not, which decides where a task-scoped push has to write.
   */
  private final org.camunda.bpm.engine.RepositoryService repositoryService;

  /**
   * The core's sync model. Camunda 7 shares like every other
   * BPMS: the values are written as process variables at every point this adapter talks
   * to the engine, and the engine's expressions read those variables. Being embedded is
   * no reason to do it differently - the engine evaluates its models against its own
   * variables either way, and a model reading something else would work here and break
   * on every remote BPMS.
   * <p>
   * VanillaBP never reads these variables back: the aggregate is the truth. The only
   * variables read are the ones a {@code @TaskParam} asks for, which the BPMN model
   * provides deliberately.
   */
  private final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync;

  /**
   * Everything the platform hands over. An adapter which is registered incompletely does
   * not come into existence (see
   * {@link io.vanillabp.integration.adapter.spi.AdapterCollaborators}).
   */
  private final io.vanillabp.integration.adapter.spi.AdapterCollaborators collaborators;

  /**
   * The technical variable written when the application shares nothing: Camunda 7
   * evaluates conditional events on variable changes, so SOMETHING has to change for
   * the engine to look. Its value is the time of the push - only there to make every
   * write a change.
   */
  public static final String AGGREGATE_CHANGED_MARKER = "vanillabpAggregateChanged";

  /**
   * The default of this adapter: everything is shared unless the
   * application excludes it ({@code @NoSyncWithBPMS}), which is the default of every
   * VanillaBP adapter - a model must not depend on which BPMS runs it.
   */
  public static final io.vanillabp.integration.adapter.spi.AggregateSyncMode SYNC_MODE = io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL;

  /**
   * The core's name-clash-avoidance model: translates process ids, message
   * names and error codes into what the ENGINE knows, and decides whether operations
   * run in a Camunda tenant. May be <code>null</code> (tests): the workflow module id
   * is the tenant then, as before.
   */
  private final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  /**
   * Which serialization format nested shared values are stored in, resolved per workflow
   * with a fallback to the workflow module and the adapter. Provided by the
   * platform integration, which binds the configuration; <code>null</code> in tests -
   * the engine's default applies then.
   */
  private io.vanillabp.camunda7.sync.Camunda7SerializationFormats serializationFormats;

  /**
   * Hands over how a nested shared value is serialized. It arrives after construction
   * because the platform integration binds the configuration, and the service is built
   * before that is available.
   *
   * @param serializationFormats The format resolution of the platform integration
   */
  public void setSerializationFormats(
      final io.vanillabp.camunda7.sync.Camunda7SerializationFormats serializationFormats) {

    this.serializationFormats = serializationFormats;

  }

  /**
   * The configured format for nested shared values of one workflow, or
   * <code>null</code>.
   */
  private String serializationFormatOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return serializationFormats != null
        ? serializationFormats.formatFor(workflowModuleId, bpmnProcessId)
        : null;

  }

  /**
   * What a workflow module's tenant is CONFIGURED as, resolved by the platform modules over
   * the levels the name may be set at, or <code>null</code> for a module nothing names a
   * tenant for - then the workflow module id names it.
   */
  private java.util.function.Function<String, io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant> configuredTenants;

  /**
   * Narrows a runtime query down to the scope an awareness probe was asked about.
   * <p>
   * <b>Why the probes need it.</b> A Camunda 7 business key is the workflow-aggregate id,
   * unique per aggregate type and not across an engine, so the key alone answers for any
   * workflow of any workflow module which happens to count from the same number - and for
   * processes this application never wired at all, an engine being a database somebody
   * else may share. What tells them apart is what the scope names: the workflow module and
   * its BPMN processes, translated into the process definition keys the engine knows and
   * the tenant the module runs in.
   *
   * @param query The query to narrow
   * @param scope What the probe was asked about
   * @return The same query, narrowed
   */
  private org.camunda.bpm.engine.runtime.ProcessInstanceQuery within(
      final org.camunda.bpm.engine.runtime.ProcessInstanceQuery query,
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope) {

    final var scoped = query.processDefinitionKeyIn(scopedProcessIdsOf(scope));
    final var tenantId = tenantIdOf(scope.workflowModuleId());
    return tenantId != null
        ? scoped.tenantIdIn(tenantId)
        : scoped.withoutTenantId();

  }

  /**
   * Narrows a history query down to the same scope.
   */
  private org.camunda.bpm.engine.history.HistoricProcessInstanceQuery withinHistory(
      final org.camunda.bpm.engine.history.HistoricProcessInstanceQuery query,
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope) {

    final var scoped = query.processDefinitionKeyIn(scopedProcessIdsOf(scope));
    final var tenantId = tenantIdOf(scope.workflowModuleId());
    return tenantId != null
        ? scoped.tenantIdIn(tenantId)
        : scoped.withoutTenantId();

  }

  /**
   * @param scope What the probe was asked about
   * @return The process definition keys the engine knows that scope's processes by
   */
  private String[] scopedProcessIdsOf(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope) {

    return scope
        .bpmnProcessIds()
        .stream()
        .map(bpmnProcessId -> scopedProcessId(scope.workflowModuleId(), bpmnProcessId))
        .toArray(String[]::new);

  }

  /**
   * Whether this adapter id was given a datasource of its own
   * (<code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>). Set by the platform
   * integration, which builds the engine; <code>false</code> in tests, which is the
   * shared-datasource answer.
   */
  private boolean engineRunsOnItsOwnDataSource;

  /**
   * Tells the service whether the engine shares the application's datasource. It changes
   * what an operation may assume about the caller's transaction, so it is set by whoever
   * built the engine and by nobody else.
   *
   * @param engineRunsOnItsOwnDataSource Whether the engine runs on a datasource of its
   *          own
   */
  public void setEngineRunsOnItsOwnDataSource(
      final boolean engineRunsOnItsOwnDataSource) {

    this.engineRunsOnItsOwnDataSource = engineRunsOnItsOwnDataSource;

  }

  /**
   * The adapter's knowledge about its deployed process definitions, shared with the task
   * deliveries of the same engine. <code>null</code> in tests which build the service
   * without an engine holder: a start then reports no version, and a start by message asks
   * the engine for the newest definition.
   */
  private io.vanillabp.camunda7.wiring.Camunda7TaskRegistry taskRegistry;

  /**
   * Hands over the registry the deliveries of this engine read the version of a process
   * definition from. A start reports its version through the same cache, so a start and a
   * task of the same workflow name the version in the same form.
   *
   * @param taskRegistry The task registry of the engine this service runs on
   */
  public void setTaskRegistry(
      final io.vanillabp.camunda7.wiring.Camunda7TaskRegistry taskRegistry) {

    this.taskRegistry = taskRegistry;

  }

  /**
   * The version of the process definition a new instance runs on, in the form a task of the
   * same workflow reports it.
   */
  private String versionOfTheDefinitionOf(
      final ProcessInstance started) {

    return taskRegistry == null
        ? null
        : taskRegistry.versionOfDefinition(started.getProcessDefinitionId());

  }

  /**
   * Sets the tenant names the application configured - this adapter's own configuration,
   * unlike the name-clash-avoidance support, which arrives with the collaborators.
   *
   * @param configuredTenants What a workflow module's tenant is configured as
   */
  public void setConfiguredTenants(
      final java.util.function.Function<String, io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant> configuredTenants) {

    this.configuredTenants = configuredTenants;

  }

  /**
   * Correlates a message which STARTS a workflow, and only a workflow of the process the
   * caller's process service belongs to. Answers the instance the message created.
   * <p>
   * The correlation names the process definition. Without it the engine would start every
   * process whose start event waits for that message, which is what version 1 did. The core
   * refuses such a message before phase one wherever this adapter could report the messages
   * of the model. Naming the definition keeps the rule where the core could not check.
   * <p>
   * The definition is the newest version of the process, the same one {@code startWorkflow}
   * starts. A version somebody deployed from outside after the application started is the
   * newest one, too. See {@code Camunda7StartByMessageIT}.
   * <p>
   * Camunda 7 refuses a correlation which names a process definition and a tenant at the
   * same time. The definition was looked up within the module's tenant, so it carries the
   * tenant already.
   */
  private ProcessInstance startByMessage(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName,
      final String businessKey,
      final java.util.Map<String, Object> sharedValues) {

    return runtimeService
        .createMessageCorrelation(scopedIdentifier(workflowModuleId, messageName))
        .processDefinitionId(definitionStartedByMessages(workflowModuleId, bpmnProcessId))
        .processInstanceBusinessKey(businessKey)
        // the new instance starts with the values its model may read, exactly
        // like a workflow started without a message
        .setVariables(sharedValues)
        .correlateStartMessage();

  }

  /**
   * The process definition a message start creates its instance of: the newest version of
   * the process in the module's tenant. Usually this is the version the application deployed
   * while it started. A version deployed later from outside replaces it, exactly as it does
   * for a start without a message.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The engine's process definition id
   * @throws IllegalStateException If the engine holds no definition of that process
   */
  private String definitionStartedByMessages(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var tenantId = tenantIdOf(workflowModuleId);
    var query = repositoryService
        .createProcessDefinitionQuery()
        .processDefinitionKey(scopedProcessId(workflowModuleId, bpmnProcessId))
        .latestVersion();
    query = tenantId != null
        ? query.tenantIdIn(tenantId)
        : query.withoutTenantId();
    final var newest = query.singleResult();
    if (newest == null) {
      throw new IllegalStateException(
          """
              BPMN process '%s' of workflow module '%s' is not deployed to adapter '%s', so no \
              message can start it! Check that the model of this process is part of the \
              workflow module's resources."""
              .formatted(bpmnProcessId, workflowModuleId, adapterId));
    }
    return newest.getId();

  }

  /**
   * Whether the model of a process definition has a start event for this message, read the
   * way the engine reads it when a correlation names that definition: the start events the
   * process itself holds, and the message name as the model writes it. Camunda 7 refuses to
   * deploy a message start event whose name is an expression, so the name in the model is
   * always the plain name. See {@code Camunda7MessageNamedByExpressionTest}.
   */
  private boolean startsByMessage(
      final String processDefinitionId,
      final String scopedProcessId,
      final String scopedMessageName) {

    return repositoryService
        .getBpmnModelInstance(processDefinitionId)
        .getModelElementsByType(org.camunda.bpm.model.bpmn.instance.StartEvent.class)
        .stream()
        .filter(startEvent -> (startEvent
            .getParentElement() instanceof org.camunda.bpm.model.bpmn.instance.Process process) && scopedProcessId
                .equals(process.getId()))
        .flatMap(startEvent -> startEvent.getEventDefinitions().stream())
        .filter(org.camunda.bpm.model.bpmn.instance.MessageEventDefinition.class::isInstance)
        .map(org.camunda.bpm.model.bpmn.instance.MessageEventDefinition.class::cast)
        .anyMatch(definition -> (definition.getMessage() != null) && scopedMessageName
            .equals(definition.getMessage().getName()));

  }

  /**
   * Whether a RUNNING instance of the given workflow exists - the idempotency check
   * of the two-phase start. Honors the module's tenant and the scoped process id.
   */
  private boolean instanceExists(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String businessKey) {

    var query = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(businessKey)
        .processDefinitionKey(scopedProcessId(workflowModuleId, bpmnProcessId));
    final var tenantId = tenantIdOf(workflowModuleId);
    query = tenantId != null
        ? query.tenantIdIn(tenantId)
        : query.withoutTenantId();
    return query.count() > 0;

  }

  /**
   * The BPMN process id as the engine knows it.
   */
  private String scopedProcessId(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return scoping == null
        ? bpmnProcessId
        : scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);

  }

  /**
   * A message name / error code as the engine knows it.
   */
  private String scopedIdentifier(
      final String workflowModuleId,
      final String identifier) {

    return scoping == null
        ? identifier
        : scoping.scopedIdentifier(workflowModuleId, identifier, adapterId);

  }

  /**
   * The Camunda tenant an operation runs in, or <code>null</code> when the module's
   * mode uses no tenant.
   */
  private String tenantIdOf(
      final String workflowModuleId) {

    final var configured = configuredTenants != null
        ? configuredTenants.apply(workflowModuleId)
        : null;
    return io.vanillabp.camunda7.wiring.Camunda7Scoping
        .tenantIdFor(
            scoping,
            workflowModuleId,
            adapterId,
            configured != null
                ? configured.tenantId()
                : null);

  }

  /**
   * What Camunda 7 does with that type, so the startup check can say what happens to a value
   * which travels between the application and this BPMS.
   *
   * @param valueType The declared type of the value
   * @param direction Which way the value travels
   * @return The verdict of this adapter
   */
  @Override
  public io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict whatThisBpmsDoesWith(
      final Class<?> valueType,
      final io.vanillabp.integration.adapter.spi.values.ValueDirection direction) {

    return io.vanillabp.camunda7.sync.Camunda7ValueTypes.verdictFor(valueType, direction);

  }

  /**
   * Convenience constructor without the sync model (tests) - no operator context
   * is written then.
   *
   * @param adapterId The configured adapter id this service serves
   * @param runtimeService The engine's runtime, where a workflow is started and a message
   *          is correlated
   * @param taskService The engine's tasks, where a user task is completed
   * @param repositoryService The engine's repository, asked which definition a workflow
   *          runs on
   * @param historyService The engine's history, which is what the viewer API reads
   * @param collaborators Everything the platform hands over; the sync model is taken from
   *          here where there is one
   */
  public Camunda7ProcessService(
      final String adapterId,
      final RuntimeService runtimeService,
      final org.camunda.bpm.engine.TaskService taskService,
      final org.camunda.bpm.engine.RepositoryService repositoryService,
      final org.camunda.bpm.engine.HistoryService historyService,
      final io.vanillabp.integration.adapter.spi.AdapterCollaborators collaborators) {

    this.collaborators = collaborators;
    this.aggregateSync = collaborators.workflowAggregateSync();
    this.scoping = collaborators.scoping();
    this.adapterId = adapterId;
    this.runtimeService = runtimeService;
    this.taskService = taskService;
    this.historyService = historyService;
    this.repositoryService = repositoryService;
    this.viewer = new Camunda7WorkflowViewer(adapterId, repositoryService, historyService, runtimeService);

  }

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  /**
   * What this adapter does for each operation, in both phases.
   * <p>
   * Every handler asks in phase one and acts in phase two, and every phase-two half is
   * idempotent because the outbox dispatches at-least-once: starting skips an instance
   * which already carries the business key, completing or cancelling checks that the
   * task is still there, and correlating checks the subscription. The embedded engine
   * answers phase one from the caller's own transaction, so a task which does not exist
   * or a message nothing waits for is reported where the application asked for it.
   */
  @Override
  public Map<PhaseOperation, PhaseOperationHandler<A>> phaseOperations() {

    return Map
        .ofEntries(
            Map
                .entry(
                    PhaseOperation.START_WORKFLOW,
                    PhaseOperationHandler
                        .of(
                            request -> {
                              // phase one does not advance the process: the instance is created
                              // after the commit, so a rollback cannot leave a ghost instance
                              // behind. There is nothing to validate against the engine either -
                              // starting is the degenerate two-phase case, like on a remote BPMS
                            },
                            this::startWorkflow)),
            Map
                .entry(
                    PhaseOperation.START_WORKFLOW_BY_MESSAGE,
                    PhaseOperationHandler.of(this::checkMessageStartEventExists, this::startWorkflowByMessage)),
            Map
                .entry(
                    PhaseOperation.COMPLETE_TASK,
                    PhaseOperationHandler
                        .of(
                            // the non-advancing phase-one check: does the task still exist? The
                            // completion itself happens after the commit - doing it here would
                            // advance the process although the transaction may still roll back,
                            // and a completion losing a concurrency conflict could not be repeated
                            request -> checkTaskExists(request.taskId(), "completing"),
                            this::completeTask)),
            Map
                .entry(
                    PhaseOperation.CANCEL_TASK,
                    PhaseOperationHandler
                        .of(
                            // the non-advancing phase-one check - the cancellation itself and the
                            // values an operator sees along with it happen after the commit
                            request -> checkTaskExists(request.taskId(), "canceling"),
                            this::cancelTask)),
            Map
                .entry(
                    PhaseOperation.COMPLETE_USER_TASK,
                    PhaseOperationHandler
                        .of(
                            // the non-advancing phase-one check - the completion happens after
                            // the commit
                            request -> checkUserTaskExists(request.taskId(), "completing"),
                            this::completeUserTask)),
            Map
                .entry(
                    PhaseOperation.CANCEL_USER_TASK,
                    PhaseOperationHandler
                        .of(
                            request -> checkUserTaskExists(request.taskId(), "canceling"),
                            this::cancelUserTask)),
            Map
                .entry(
                    PhaseOperation.CORRELATE_MESSAGE,
                    PhaseOperationHandler.of(this::checkSubscriptionWaits, this::correlateMessage)),
            Map
                .entry(
                    PhaseOperation.SEND_SIGNAL,
                    PhaseOperationHandler
                        .of(
                            request -> {
                              // phase one does not advance the process - a signal reaching a
                              // subscription IS progress, so it is broadcast after the commit
                            },
                            request -> broadcastSignal(request.workflowModuleId(), request.signalName()))),
            Map
                .entry(
                    PhaseOperation.AGGREGATE_CHANGED,
                    PhaseOperationHandler
                        .of(
                            request -> {
                              // phase one does not advance the process - writing here would show
                              // values of a transaction which may still roll back, and
                              // re-evaluating conditional events IS progress
                            },
                            this::pushChangedAggregate)));

  }

  /**
   * Creates the instance, unless one for this aggregate is already running.
   * <p>
   * Phase two is dispatched at-least-once (outbox retries, crash recovery), so the
   * check is needed - but it is the SECOND line rather than the mechanism: the core owns
   * the idempotency of a start and probes {@code awarenessOfWorkflowForRedispatch}
   * before it dispatches a start again, which this adapter answers exactly. The check
   * stays because the query is free on an embedded engine and because it also covers a
   * start which reached the engine while the core had no reason to re-probe.
   */
  private void startWorkflow(
      final PhaseTwoRequest<A> request) {

    final var businessKey = String.valueOf(request.workflowAggregateId());
    if (instanceExists(request.workflowModuleId(), request.bpmnProcessId(), businessKey)) {
      log
          .info(
              "Camunda7[{}]: workflow '{}' of module '{}' was already started for aggregate '{}' - "
                  + "skipping the redelivered phase-two start",
              adapterId,
              request.bpmnProcessId(),
              request.workflowModuleId(),
              businessKey);
      return;
    }

    // the aggregate is committed by now, so its shared attributes can be written as
    // the operator context of the new instance - phase one had it at hand, phase two
    // has to read it back
    final var started = startProcessInstance(
        request.workflowModuleId(),
        request.bpmnProcessId(),
        request.workflowAggregateId(),
        aggregateForOperatorContext(request.aggregatePersistence(), request.workflowAggregateId()));
    // a start creates the root of a call tree, so this instance is the workflow the aggregate
    // IS, named the way a task of it reports its workflow id
    request.reportStartedWorkflow(started.getProcessInstanceId(), versionOfTheDefinitionOf(started));

  }

  /**
   * The non-advancing phase-one check of a start by message: does the process of the
   * caller's process service have a message start event of this name? Phase two names the
   * same process definition, so a message which passes here is one phase two can correlate.
   * Starting itself happens after the commit, idempotently.
   */
  private void checkMessageStartEventExists(
      final PhaseOneRequest<A> request) {

    final var scopedMessageName = scopedIdentifier(request.workflowModuleId(), request.messageName());
    final var definitionId = definitionStartedByMessages(request.workflowModuleId(), request.bpmnProcessId());
    final var scopedProcessId = scopedProcessId(request.workflowModuleId(), request.bpmnProcessId());
    if (!startsByMessage(definitionId, scopedProcessId, scopedMessageName)) {
      throw new IllegalStateException(
          """
              BPMN process '%s' of workflow module '%s' has no message start event named '%s' in \
              the model adapter '%s' starts! A message starts only the process of the process \
              service you called. Check the message name against the model of that process. \
              Camunda 7 does not support an expression as the name of a message start event, so \
              the name you pass has to be the plain name the model writes."""
              .formatted(request.bpmnProcessId(), request.workflowModuleId(), request.messageName(), adapterId));
    }

  }

  /**
   * Starts the workflow by its message start event, unless one for this aggregate is
   * already running - the same idempotency contract as {@link #startWorkflow}.
   */
  private void startWorkflowByMessage(
      final PhaseTwoRequest<A> request) {

    final var businessKey = String.valueOf(request.workflowAggregateId());
    if (instanceExists(request.workflowModuleId(), request.bpmnProcessId(), businessKey)) {
      log
          .info(
              "Camunda7[{}]: workflow '{}' of module '{}' was already started for aggregate '{}' - "
                  + "skipping the redelivered phase-two start-by-message",
              adapterId,
              request.bpmnProcessId(),
              request.workflowModuleId(),
              businessKey);
      return;
    }
    final var started = startByMessage(
        request.workflowModuleId(),
        request.bpmnProcessId(),
        request.messageName(),
        businessKey,
        sharedValues(
            request.aggregatePersistence(),
            request.workflowAggregateId(),
            request.workflowModuleId(),
            request.bpmnProcessId()));
    request.reportStartedWorkflow(started.getProcessInstanceId(), versionOfTheDefinitionOf(started));

  }

  /**
   * Completes the parked task. The values the model reads are written along with the
   * completion: whatever the caller changed before answering the task decides where the
   * workflow goes next. Only after {@code signalTask} verified the task is still there -
   * writing variables of a gone execution would mark the dispatcher's transaction
   * rollback-only.
   */
  private void completeTask(
      final PhaseTwoRequest<A> request) {

    signalTask(
        request.taskId(),
        null,
        true,
        () -> refreshSharedValues(
            request.taskId(),
            aggregateForOperatorContext(request.aggregatePersistence(), request.workflowAggregateId()),
            request.workflowModuleId(),
            request.bpmnProcessId()));

  }

  /**
   * Cancels the parked task by BPMN error. The values an operator sees in Cockpit are
   * refreshed along with the cancellation, and only once {@code signalTask} verified the
   * task is still there.
   */
  private void cancelTask(
      final PhaseTwoRequest<A> request) {

    signalTask(
        request.taskId(),
        scopedIdentifier(request.workflowModuleId(), request.bpmnErrorCode()),
        true,
        () -> {
          final var workflowAggregate = request.aggregatePersistence().loadById(request.workflowAggregateId());
          if (workflowAggregate != null) {
            refreshSharedValues(
                request.taskId(), workflowAggregate, request.workflowModuleId(), request.bpmnProcessId());
          }
        });

  }

  private void completeUserTask(
      final PhaseTwoRequest<A> request) {

    executeUserTask(
        request.taskId(),
        null,
        true,
        () -> refreshSharedValuesOfUserTask(
            request.taskId(),
            request.aggregatePersistence(),
            request.workflowAggregateId(),
            request.workflowModuleId(),
            request.bpmnProcessId()));

  }

  private void cancelUserTask(
      final PhaseTwoRequest<A> request) {

    executeUserTask(
        request.taskId(),
        scopedIdentifier(request.workflowModuleId(), request.bpmnErrorCode()),
        true,
        () -> refreshSharedValuesOfUserTask(
            request.taskId(),
            request.aggregatePersistence(),
            request.workflowAggregateId(),
            request.workflowModuleId(),
            request.bpmnProcessId()));

  }

  /**
   * The non-advancing phase-one check of a correlation: is a subscription waiting for
   * this message? Correlating itself continues the workflow and happens after the
   * commit - but "nothing matched" was a synchronous failure before this adapter went
   * two-phase, and it stays one. The embedded engine answers from the same transaction,
   * so the answer is exact.
   */
  private void checkSubscriptionWaits(
      final PhaseOneRequest<A> request) {

    final var businessKey = String
        .valueOf(request.aggregatePersistence().getAggregateId(request.workflowAggregate()));
    if (!subscriptionWaitingFor(
        request.workflowModuleId(),
        request.bpmnProcessId(),
        request.messageName(),
        businessKey,
        request.correlationId())) {
      throw new IllegalStateException(
          """
              No execution of workflow aggregate '%s' waits for message '%s' (BPMN process '%s' of \
              workflow module '%s')%s! Correlating it would do nothing - check the message name \
              against the model, and whether the workflow already passed the point where it waits."""
              .formatted(
                  businessKey,
                  request.messageName(),
                  request.bpmnProcessId(),
                  request.workflowModuleId(),
                  request.correlationId() == null
                      ? ""
                      : (" with correlation id '%s' (the waiting execution expects the one stored in "
                          + "its local variable '%s')").formatted(
                              request.correlationId(),
                              correlationIdVariableName(request.bpmnProcessId(), request.messageName()))));
    }

  }

  private void correlateMessage(
      final PhaseTwoRequest<A> request) {

    final var businessKey = String.valueOf(request.workflowAggregateId());
    // check BEFORE correlating (rollback-only pitfall, see signalTask): a waiting
    // subscription gone by dispatch time is the at-least-once residual
    if (!messageSubscriptionWaiting(request.workflowModuleId(), request.messageName(), businessKey)) {
      log
          .warn(
              "Camunda7[{}]: no waiting subscription for message '{}' of workflow aggregate '{}' - "
                  + "skipping the redelivered phase-two correlation",
              adapterId,
              request.messageName(),
              businessKey);
      return;
    }
    // the values the model reads travel with the correlation: a gateway
    // behind the receiving event decides on what the caller changed before correlating
    messageCorrelation(
        request.workflowModuleId(),
        request.bpmnProcessId(),
        request.messageName(),
        businessKey,
        request.correlationId())
        .setVariables(
            sharedValues(
                request.aggregatePersistence(),
                request.workflowAggregateId(),
                request.workflowModuleId(),
                request.bpmnProcessId()))
        .correlateWithResult();

  }

  private void pushChangedAggregate(
      final PhaseTwoRequest<A> request) {

    final var aggregate = request.aggregatePersistence().loadById(request.workflowAggregateId());
    pushAggregate(
        String.valueOf(request.workflowAggregateId()),
        aggregate,
        request.taskId(),
        true,
        request.workflowModuleId(),
        request.bpmnProcessId());

  }

  @Override
  public boolean isPhaseTwoFailureRepeatable(
      final Throwable failure) {

    // The conflict this adapter went two-phase for: the engine reports a
    // row another transaction touched as OptimisticLockingException, and the next
    // attempt simply wins - which is exactly what Camunda's own job executor does
    // with a failed job. Everything else is repeated as well, because an engine
    // command may fail for a reason which passes: a locked instance, or a database
    // which was briefly away.
    //
    // Three failures never pass. A request the engine rejects as wrong looks the same on
    // every attempt: a task id which does not exist, or an argument it cannot accept.
    // A broken expression is the second one. Not every one of them is hopeless, because a
    // navigation into an attribute which is null today could read a value tomorrow, but
    // an expression which fails while an instance is being created loses the instance:
    // nothing in the engine shows it and nobody in the application is waiting for it, so
    // hours of attempts help no one. Giving up early at least leaves an entry somebody
    // can find.
    //
    // The third is a start of a process the engine does not hold. It is a case of its
    // own because the engine reports it with an exception which is repeatable for every
    // other operation, so the start marks its own refusal (Camunda7RefusedStart).
    var candidate = failure;
    while (candidate != null) {
      if (candidate instanceof Camunda7RefusedStart) {
        return false;
      }
      if (candidate instanceof org.camunda.bpm.engine.BadUserRequestException) {
        return false;
      }
      if (isExpressionFailure(candidate)) {
        return false;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    return true;

  }

  /**
   * Camunda 7 wraps what its expression language threw into a
   * {@link org.camunda.bpm.engine.ProcessEngineException} and keeps it as the cause, so
   * the type is what tells an unreadable expression from an engine having a bad moment.
   * The message does not: it is prose which differs per failure and per engine version.
   * <p>
   * The type is matched by name rather than imported. Camunda ships its expression
   * language shaded into a package of its own implementation, which already moved once
   * when the EL API went from <code>javax</code> to <code>jakarta</code>, and a fork of
   * the engine shades it somewhere else again. Reading the last two segments of the name
   * survives that, and it costs nothing here: the adapter recognises the type, it never
   * catches or constructs one.
   *
   * @param candidate One link of the cause chain
   * @return Whether it is a failure of the expression language, the subclasses for an
   *         unknown method and for an unknown property included
   */
  private static boolean isExpressionFailure(
      final Throwable candidate) {

    Class<?> type = candidate.getClass();
    while (type != null) {
      if (type.getName().endsWith(EXPRESSION_FAILURE)) {
        return true;
      }
      type = type.getSuperclass();
    }
    return false;

  }

  @Override
  public boolean deliversTasksAtLeastOnce() {

    // On the application's datasource Camunda 7 hands a task to the handler INSIDE its
    // own (job) transaction (TaskInvocationContext#runInCurrentTransaction), so
    // aggregate changes and engine state commit or roll back together: a redelivered job
    // proves that NOTHING was committed, and there is nothing a delivery record could
    // remember. Deduplicating would cost a store access per task and buy nothing.
    //
    // An engine on a datasource of its own commits where the application's persistence
    // cannot join, so the aggregate commits first and the job after it. A crash in
    // between leaves the engine with work it never saw the end of, and it hands the same
    // job out again - the window every remote BPMS has. The job names that delivery, so
    // the core recognizes the repetition and answers it from what it recorded
    return engineRunsOnItsOwnDataSource;

  }

  /**
   * Starts a Camunda 7 process instance. The <b>business key</b> is the
   * workflow-aggregate ID as a string, and the process is addressed the way
   * {@code Camunda7DeploymentService} deployed it, which the name-clash-avoidance mode
   * decides (a tenant named after the workflow module, prefixed identifiers, or
   * neither).
   * <p>
   * This runs in phase two, after the caller's transaction committed, dispatched by the
   * phase-two outbox like every operation which progresses a workflow here (see
   * decision 2 in the repository's DECISIONS.md). So the instance is NOT committed
   * together with the caller's business data: the outbox entry is, and the entry is
   * repeated until the engine accepted the start.
   * <p>
   * The service task following the (asynchronous) start event is not executed
   * synchronously: the async-before continuation parks the instance in the job executor,
   * so no {@code @WorkflowTask} wiring is required for the start to succeed.
   *
   * @param workflowModuleId The workflow module ID, which names the Camunda tenant under
   *        {@code by-adapter} and prefixes the process id under {@code use-prefix}
   * @param bpmnProcessId The BPMN process ID to start
   * @param workflowAggregateId The workflow-aggregate ID (used as the business key)
   * @return The started process instance
   */
  public ProcessInstance startProcessInstance(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId) {

    return startProcessInstance(workflowModuleId, bpmnProcessId, workflowAggregateId, null);

  }

  /**
   * Starts a process instance, writing the aggregate's shared attributes as
   * process variables - CONTEXT INFORMATION FOR OPERATORS only (see
   * {@link #aggregateSync}); nothing reads them back.
   *
   * @param workflowModuleId The workflow module ID, which names the Camunda tenant under
   *        {@code by-adapter} and prefixes the process id under {@code use-prefix}
   * @param bpmnProcessId The BPMN process ID to start
   * @param workflowAggregateId The workflow-aggregate ID (the business key)
   * @param aggregate The workflow aggregate or <code>null</code> if unavailable
   * @return The started process instance
   */
  public ProcessInstance startProcessInstance(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId,
      final A aggregate) {

    // the aggregate id was validated (non-null, non-blank) once in the core's
    // MigrationProcessService before phase one is invoked
    final var businessKey = String.valueOf(workflowAggregateId);

    final var tenantId = tenantIdOf(workflowModuleId);
    final var scopedProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    var builder = runtimeService
        .createProcessInstanceByKey(scopedProcessId);
    builder = tenantId != null
        ? builder.processDefinitionTenantId(tenantId)
        : builder.processDefinitionWithoutTenantId();
    final ProcessInstance processInstance;
    try {
      processInstance = builder
          .businessKey(businessKey)
          .setVariables(sharedValues(aggregate, workflowModuleId, bpmnProcessId))
          .execute();
    } catch (final org.camunda.bpm.engine.exception.NullValueException e) {
      // the one thing the engine can be missing here is the process definition: the
      // command is built from values the core validated, and the engine reports a
      // definition nothing was deployed under by the same exception it uses for an
      // argument nobody passed. Which one it is, is the operation rather than the
      // exception, so the start says it here and the classification reads it back
      throw new Camunda7RefusedStart(
          ("Camunda 7 holds no process '%s' of workflow module '%s'%s, so the workflow of aggregate "
              + "'%s' cannot be started (adapter '%s'). The engine answers the same way on every "
              + "attempt, so this start is not repeated and its outbox entry is blocked. Either the "
              + "workflow module of that process is not deployed by this application, or it is "
              + "deployed under another name-clash-avoidance mode than the one this adapter reads.")
              .formatted(
                  scopedProcessId,
                  workflowModuleId,
                  tenantId == null
                      ? " without a tenant"
                      : " in tenant '%s'".formatted(tenantId),
                  businessKey,
                  adapterId), e);
    }

    // what the engine was really given, not what the caller asked for: the tenant is null
    // unless the mode of this module is 'by-adapter', and the process id carries the
    // module's prefix under 'use-prefix'. A module id in the tenant's place sends the next
    // reader looking for a scope the engine does not have
    log.info(
        "Camunda7[{}]: started workflow '{}' of workflow module '{}' ({}tenant '{}', business key "
            + "'{}') as process instance '{}'",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        scopedProcessId.equals(bpmnProcessId)
            ? ""
            : "deployed as '%s', ".formatted(scopedProcessId),
        tenantId != null
            ? tenantId
            : "<none>",
        businessKey,
        processInstance.getId());

    return processInstance;

  }

  /**
   * The values the aggregate shares with the BPMS, as Camunda 7 process variables:
   * what the engine's expressions read, and what an operator sees in
   * Cockpit. Nested structures travel as JSON strings, see
   * {@link io.vanillabp.camunda7.sync.Camunda7Variables}.
   *
   * @param aggregate The workflow aggregate or <code>null</code>
   * @return The variables (never <code>null</code>)
   */
  private java.util.Map<String, Object> sharedValues(
      final A aggregate,
      final String workflowModuleId,
      final String bpmnProcessId) {

    if ((aggregateSync == null) || (aggregate == null)) {
      return java.util.Map.of();
    }
    return io.vanillabp.camunda7.sync.Camunda7Variables
        .of(
            aggregateSync.syncedValues(aggregate, SYNC_MODE),
            serializationFormatOf(workflowModuleId, bpmnProcessId));

  }

  /**
   * The shared values of an aggregate this method has to read first - the shape phase
   * two needs, where the caller's transaction is committed and only the ID is at hand.
   * A persistence which cannot read the aggregate must not keep the operation from
   * happening, so a failure yields no variables rather than an exception.
   *
   * @param aggregatePersistence The aggregate's persistence (may be
   *          <code>null</code>)
   * @param workflowAggregateId The aggregate's ID
   * @return The variables (never <code>null</code>)
   */
  private java.util.Map<String, Object> sharedValues(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return sharedValues(
        aggregateForOperatorContext(aggregatePersistence, workflowAggregateId),
        workflowModuleId,
        bpmnProcessId);

  }

  /**
   * Writes the shared values into a running workflow (see {@link #sharedValues}) - a
   * no-op where the aggregate shares nothing at all.
   *
   * @param executionId The execution to write the variables at
   * @param aggregate The workflow aggregate or <code>null</code>
   */
  private void refreshSharedValues(
      final String executionId,
      final A aggregate,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var variables = sharedValues(aggregate, workflowModuleId, bpmnProcessId);
    if (variables.isEmpty()) {
      return;
    }
    runtimeService.setVariables(executionId, variables);

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final Object workflowAggregateId,
      final String taskId) {

    // the task ID of a Camunda 7 @TaskId handler is the parked execution's ID, unique
    // within the engine. That the business key matches rules out an unrelated
    // aggregate; what it does NOT rule out is a workflow of another workflow module of
    // this engine carrying the same aggregate id, so the instance has to be one of this
    // adapter's own scopes as well
    try {
      final var execution = runtimeService
          .createExecutionQuery()
          .executionId(taskId)
          .singleResult();
      if (execution == null) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }
      return awarenessOfInstance(scope, execution.getProcessInstanceId(), workflowAggregateId);
    } catch (final org.camunda.bpm.engine.ProcessEngineException e) {
      // an embedded engine sharing the application's datasource practically cannot
      // be unavailable; an engine on its OWN datasource can - never fall back to
      // another adapter in that case (contract)
      log.warn(
          "Camunda7[{}]: could not determine awareness of task '{}' - reporting BPMS_UNAVAILABLE",
          adapterId,
          taskId,
          e);
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    }

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final io.vanillabp.integration.spi.AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    // the business key IS the aggregate ID. A running instance means ACTIVE; an
    // ENDED one is reported as COMPLETED as long as the engine's history still
    // holds it (history level != NONE and within 'history-time-to-live') - the
    // honest answer the SPI asks for: it keeps a re-dispatched start from
    // starting a second instance of a workflow which already ran to its end, and
    // it makes the viewer/history API work for ended workflows. Once the history
    // was cleaned up an ended instance is indistinguishable from a never-existing
    // one - both map to UNKNOWN_TO_BPMS (the caller's guiding error explains the
    // causes).
    // Both queries run within the scope the probe was asked about.
    // A business key is the aggregate id, unique per aggregate type and not across an
    // application, so the unscoped question answers ACTIVE for the workflow of another
    // workflow module of this engine which happens to count from the same number.
    try {
      final var businessKey = String.valueOf(workflowAggregateId);
      final var active = within(
          runtimeService
              .createProcessInstanceQuery()
              .processInstanceBusinessKey(businessKey),
          scope)
          .count() > 0;
      if (active) {
        return WorkflowAwareness.ACTIVE;
      }
      final var ended = withinHistory(
          historyService
              .createHistoricProcessInstanceQuery()
              .processInstanceBusinessKey(businessKey)
              .finished(),
          scope)
          .count() > 0;
      return ended
          ? WorkflowAwareness.COMPLETED
          : WorkflowAwareness.UNKNOWN_TO_BPMS;
    } catch (final org.camunda.bpm.engine.ProcessEngineException e) {
      log.warn(
          "Camunda7[{}]: could not determine awareness of the workflow of aggregate '{}' - "
              + "reporting BPMS_UNAVAILABLE",
          adapterId,
          workflowAggregateId,
          e);
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    }

  }

  /**
   * Whether the process instance behind a task belongs to the scope the probe was asked
   * about AND carries the expected business key. Both are needed:
   * the business key rules out an unrelated aggregate, the scope rules out the same
   * aggregate id in another workflow module or another application of this engine.
   *
   * @param scope What the probe was asked about
   * @param processInstanceId The instance the task belongs to
   * @param workflowAggregateId The aggregate the caller means
   * @return {@link WorkflowAwareness#ACTIVE} if this adapter may claim the task
   */
  private WorkflowAwareness awarenessOfInstance(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final String processInstanceId,
      final Object workflowAggregateId) {

    final var own = within(
        runtimeService
            .createProcessInstanceQuery()
            .processInstanceId(processInstanceId)
            .processInstanceBusinessKey(String.valueOf(workflowAggregateId)),
        scope)
        .count() > 0;
    return own
        ? WorkflowAwareness.ACTIVE
        : WorkflowAwareness.UNKNOWN_TO_BPMS;

  }

  /**
   * Completes (or cancels, if an error code is given) the parked execution of an
   * asynchronous task by signaling it - the {@code Camunda7WorkflowTaskBehavior}
   * leaves the activity or propagates the BPMN error. Runs within the caller's
   * transaction for engines sharing the application's datasource.
   *
   * @param taskId The parked execution's ID
   * @param bpmnErrorCode The BPMN error code or <code>null</code> to complete
   * @param tolerateGoneTask Whether a no-longer-existing execution is tolerated
   *        (phase two is at-least-once) instead of failing
   */
  private void signalTask(
      final String taskId,
      final String bpmnErrorCode,
      final boolean tolerateGoneTask) {

    signalTask(taskId, bpmnErrorCode, tolerateGoneTask, null);

  }

  /**
   * @param beforeSignal Runs after the task was found and before it is signalled -
   *        used to refresh what an operator sees along with a cancellation
   */
  private void signalTask(
      final String taskId,
      final String bpmnErrorCode,
      final boolean tolerateGoneTask,
      final Runnable beforeSignal) {

    if (tolerateGoneTask) {
      // check BEFORE signaling: a failing engine command would mark the joined
      // transaction rollback-only even if the exception is caught - the outbox
      // dispatcher's transaction could then never commit the DONE entry
      final var exists = runtimeService
          .createExecutionQuery()
          .executionId(taskId)
          .count() > 0;
      if (!exists) {
        // at-least-once residual: the task disappeared between the dispatch-time
        // probe and this signal (e.g. a boundary event canceled it)
        log.warn(
            "Camunda7[{}]: task '{}' is gone - skipping the redelivered phase-two {}",
            adapterId,
            taskId,
            bpmnErrorCode == null
                ? "completion"
                : "cancellation");
        return;
      }
    }
    if (beforeSignal != null) {
      beforeSignal.run();
    }
    if (bpmnErrorCode == null) {
      runtimeService.signal(taskId);
    } else {
      runtimeService.signal(
          taskId,
          io.vanillabp.camunda7.wiring.Camunda7WorkflowTaskBehavior.SIGNAL_CANCEL,
          bpmnErrorCode,
          java.util.Map.of());
    }

  }

  @Override
  public WorkflowAwareness awarenessOfUserTask(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final Object workflowAggregateId,
      final String taskId) {

    // user-task IDs (ACT_RU_TASK) are unique within the engine - the business key AND
    // the scope are verified like in awarenessOfTask
    try {
      final var task = taskService
          .createTaskQuery()
          .taskId(taskId)
          .singleResult();
      if (task == null) {
        return WorkflowAwareness.UNKNOWN_TO_BPMS;
      }
      return awarenessOfInstance(scope, task.getProcessInstanceId(), workflowAggregateId);
    } catch (final org.camunda.bpm.engine.ProcessEngineException e) {
      log.warn(
          "Camunda7[{}]: could not determine awareness of user task '{}' - reporting BPMS_UNAVAILABLE",
          adapterId,
          taskId,
          e);
      return WorkflowAwareness.BPMS_UNAVAILABLE;
    }

  }

  /**
   * Completes (or cancels by BPMN error) a user task. Runs within the caller's
   * transaction for engines sharing the application's datasource.
   *
   * @param taskId The user task's ID (ACT_RU_TASK)
   * @param bpmnErrorCode The BPMN error code or <code>null</code> to complete
   * @param tolerateGoneTask Whether a no-longer-existing task is tolerated (phase
   *        two is at-least-once)
   */
  private void executeUserTask(
      final String taskId,
      final String bpmnErrorCode,
      final boolean tolerateGoneTask) {

    executeUserTask(taskId, bpmnErrorCode, tolerateGoneTask, null);

  }

  /**
   * @param beforeExecute Runs after the user task was found and before it is completed
   *        or cancelled - writes the values the model reads next
   */
  private void executeUserTask(
      final String taskId,
      final String bpmnErrorCode,
      final boolean tolerateGoneTask,
      final Runnable beforeExecute) {

    if (tolerateGoneTask) {
      // check BEFORE executing - see signalTask: a failing engine command would
      // mark the joined transaction rollback-only even if the exception is caught
      final var exists = taskService
          .createTaskQuery()
          .taskId(taskId)
          .count() > 0;
      if (!exists) {
        log.warn(
            "Camunda7[{}]: user task '{}' is gone - skipping the redelivered phase-two {}",
            adapterId,
            taskId,
            bpmnErrorCode == null
                ? "completion"
                : "cancellation");
        return;
      }
    }
    if (beforeExecute != null) {
      beforeExecute.run();
    }
    if (bpmnErrorCode == null) {
      taskService.complete(taskId);
    } else {
      // routes the workflow through an error boundary event on the user task
      taskService.handleBpmnError(taskId, bpmnErrorCode);
    }

  }

  /**
   * Writes the shared values at the execution of a user task - the variables
   * the flow behind the task reads.
   *
   * @param taskId The user task's ID
   * @param aggregatePersistence The aggregate's persistence
   * @param workflowAggregateId The aggregate's ID
   */
  private void refreshSharedValuesOfUserTask(
      final String taskId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var variables = sharedValues(
        aggregatePersistence,
        workflowAggregateId,
        workflowModuleId,
        bpmnProcessId);
    if (variables.isEmpty()) {
      return;
    }
    taskService.setVariables(taskId, variables);

  }

  /**
   * The V1-compatible name of the LOCAL variable holding the expected correlation
   * id at a message subscription's execution:
   * <code>&lt;bpmnProcessId&gt;-&lt;messageName&gt;</code>. Applications set this
   * local variable at the receiving scope; a correlation carrying a correlation id
   * only matches executions whose variable equals it.
   *
   * @param bpmnProcessId The plain BPMN process id of the waiting workflow
   * @param messageName The plain name of the message it waits for
   * @return The name of the local variable the correlation id is compared against
   */
  public static String correlationIdVariableName(
      final String bpmnProcessId,
      final String messageName) {

    return bpmnProcessId
        + "-"
        + messageName;

  }

  /**
   * The non-advancing phase-one check for a task of a {@code @TaskId} handler: the
   * parked execution has to be there, otherwise completing or cancelling it after the
   * commit could only be skipped. The core's election probe asks the same question
   * before electing this adapter - asking again inside the caller's transaction
   * catches a task which disappeared in between and aborts the transaction rather
   * than leaving an outbox entry which phase two can only skip.
   * <p>
   * What it raises is {@code io.vanillabp.spi.process.TaskNotFoundException}, the type the
   * SPI documents for a task no BPMS knows any more. Which of the two found out, the
   * platform's probe or this check, is decided by whether a delivery record exists, and no
   * application can see that - so it must not decide what an application is able to catch.
   * The query is exact and answers a count, so there is nothing to classify and nothing to
   * log about a rejection.
   *
   * @param taskId The task (the parked execution's id)
   * @param operationDescription What was attempted, for the message
   */
  private void checkTaskExists(
      final String taskId,
      final String operationDescription) {

    final var exists = runtimeService
        .createExecutionQuery()
        .executionId(taskId)
        .count() > 0;
    if (!exists) {
      throw new io.vanillabp.spi.process.TaskNotFoundException(
          """
              The task '%s' is gone (completed or canceled meanwhile) - aborting the transaction %s \
              it! VanillaBP progresses the workflow after the commit, so a task which no longer \
              exists cannot be reached any more."""
              .formatted(taskId, operationDescription));
    }

  }

  /**
   * The same check for a USER task, which lives in the task service rather than as a
   * parked execution, and it raises the same exception for the same reason.
   *
   * @param taskId The user task's id
   * @param operationDescription What was attempted, for the message
   */
  private void checkUserTaskExists(
      final String taskId,
      final String operationDescription) {

    final var exists = taskService
        .createTaskQuery()
        .taskId(taskId)
        .count() > 0;
    if (!exists) {
      throw new io.vanillabp.spi.process.TaskNotFoundException(
          """
              The user task '%s' is gone (completed or canceled meanwhile) - aborting the \
              transaction %s it! VanillaBP progresses the workflow after the commit, so a task \
              which no longer exists cannot be reached any more."""
              .formatted(taskId, operationDescription));
    }

  }

  /**
   * Whether an execution of the given workflow waits for the message - the question
   * phase one asks before scheduling a correlation, and phase two asks again before
   * correlating (a subscription gone by dispatch time is the at-least-once residual).
   * <p>
   * The subscription carries the SCOPED message name, like the correlation itself:
   * querying the plain name would find nothing wherever a workflow module
   * prefixes its identifiers. A correlation id is deliberately NOT part of the
   * question - it selects among several waiting executions, which is the correlation's
   * job, not this check's.
   *
   * @param workflowModuleId The workflow module ID
   * @param messageName The message name as the application knows it
   * @param businessKey The workflow aggregate's ID
   * @return Whether at least one execution waits for that message
   */
  private boolean messageSubscriptionWaiting(
      final String workflowModuleId,
      final String messageName,
      final String businessKey) {

    return !waitingExecutions(workflowModuleId, messageName, businessKey).isEmpty();

  }

  /**
   * The executions waiting for a message of this workflow aggregate.
   *
   * @param workflowModuleId The workflow module ID
   * @param messageName The BPMN message name
   * @param businessKey The workflow aggregate's ID
   * @return The waiting executions (never <code>null</code>)
   */
  private java.util.List<org.camunda.bpm.engine.runtime.Execution> waitingExecutions(
      final String workflowModuleId,
      final String messageName,
      final String businessKey) {

    var query = runtimeService
        .createExecutionQuery()
        .messageEventSubscriptionName(scopedIdentifier(workflowModuleId, messageName))
        .processInstanceBusinessKey(businessKey);
    final var tenantId = tenantIdOf(workflowModuleId);
    query = tenantId != null
        ? query.tenantIdIn(tenantId)
        : query.withoutTenantId();
    return query.list();

  }

  private org.camunda.bpm.engine.runtime.MessageCorrelationBuilder messageCorrelation(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName,
      final String businessKey,
      final String correlationId) {

    var correlation = runtimeService
        .createMessageCorrelation(scopedIdentifier(workflowModuleId, messageName))
        .processInstanceBusinessKey(businessKey);
    final var correlationTenantId = tenantIdOf(workflowModuleId);
    correlation = correlationTenantId != null
        ? correlation.tenantId(correlationTenantId)
        : correlation.withoutTenantId();
    if (correlationId != null) {
      // V1 convention: the local variable '<bpmnProcessId>-<messageName>' at the
      // subscription's execution holds the expected correlation id
      correlation = correlation.localVariableEquals(
          correlationIdVariableName(bpmnProcessId, messageName),
          correlationId);
    }
    return correlation;

  }

  /**
   * A signal reaches every subscription of the workflow module's scope - the
   * tenant it is deployed into, respectively no tenant where the module prefixes
   * its identifiers. No variables travel, although every other sync point
   * writes them: a broadcast reaches workflows of
   * OTHER aggregates, so writing the values of the sending one into them would state
   * something false. Camunda 8 behaves the same way, for the same reason - the
   * difference to the other sync points is the broadcast, not the engine.
   */
  private void broadcastSignal(
      final String workflowModuleId,
      final String signalName) {

    var signal = runtimeService
        .createSignalEvent(scopedIdentifier(workflowModuleId, signalName));
    final var signalTenantId = tenantIdOf(workflowModuleId);
    signal = signalTenantId != null
        ? signal.tenantId(signalTenantId)
        : signal.withoutTenantId();
    signal.send();

  }

  /**
   * The phase-one question of a correlation: does an execution wait for this
   * message - and, if the application named a correlation id, does one of those
   * executions expect exactly that id? Reading a local variable is what the
   * correlation itself matches on ({@code localVariableEquals}), so a wrong id fails
   * where the application passed it instead of behind the commit.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID (names the correlation-id variable)
   * @param messageName The BPMN message name
   * @param businessKey The workflow aggregate's ID
   * @param correlationId The expected correlation id or <code>null</code>
   * @return Whether correlating would find a subscription
   */
  private boolean subscriptionWaitingFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName,
      final String businessKey,
      final String correlationId) {

    if (correlationId == null) {
      return messageSubscriptionWaiting(workflowModuleId, messageName, businessKey);
    }
    final var variableName = correlationIdVariableName(bpmnProcessId, messageName);
    return waitingExecutions(workflowModuleId, messageName, businessKey)
        .stream()
        .anyMatch(execution -> correlationId
            .equals(runtimeService.getVariableLocal(execution.getId(), variableName)));

  }

  /**
   * Writes the aggregate's shared values into the workflow (see
   * {@link #aggregateForOperatorContext}) and lets the engine re-evaluate what waits
   * for a change.
   * <p>
   * Camunda 7 evaluates conditional events when a variable of their scope changes,
   * so the write itself is the trigger - and it has to happen even for an aggregate
   * sharing nothing at all, which is why a technical marker variable is written
   * alongside. What a condition then reads are the values written here;
   * without the write nothing would look.
   *
   * @param businessKey The aggregate's ID
   * @param aggregate The workflow aggregate or <code>null</code>
   * @param taskId The parked execution whose scope receives the values, or
   *        <code>null</code> for the workflow's global scope
   * @param tolerateGoneWorkflow Whether a workflow gone by now is tolerated (phase
   *        two is at-least-once) instead of failing. Judged within the scope of the
   *        call - a foreign workflow carrying the same business key would otherwise
   *        make an ended workflow look present, and a redelivered push would write
   *        there instead of being skipped
   */
  private void pushAggregate(
      final String businessKey,
      final A aggregate,
      final String taskId,
      final boolean tolerateGoneWorkflow,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var variables = new java.util.LinkedHashMap<String, Object>(
        sharedValues(aggregate, workflowModuleId, bpmnProcessId));
    if (variables.isEmpty()) {
      // an aggregate sharing nothing at all (@NoSyncWithBPMS on the class) - without a
      // variable event the engine would not look at its conditional events, so a
      // technical marker is written instead
      variables.put(AGGREGATE_CHANGED_MARKER, System.currentTimeMillis());
    }

    if (taskId != null) {
      // check BEFORE writing (rollback-only pitfall, see signalTask)
      final var execution = runtimeService
          .createExecutionQuery()
          .executionId(taskId)
          .singleResult();
      if (execution == null) {
        if (!tolerateGoneWorkflow) {
          throw new io.vanillabp.spi.process.TaskNotFoundException(
              ("The task '%s' of the workflow of aggregate '%s' is not active in Camunda 7 (adapter "
                  + "'%s')! Either the task was completed meanwhile or the id is not the one reported "
                  + "to the @TaskId parameter.")
                  .formatted(taskId, businessKey, adapterId));
        }
        log.warn(
            "Camunda7[{}]: task '{}' of workflow aggregate '{}' is gone - skipping the redelivered "
                + "phase-two push of the aggregate",
            adapterId,
            taskId,
            businessKey);
        return;
      }
      // no scope comparison on this path, and that is a decision rather than an
      // omission: the caller names a task it was told about through
      // '@TaskId', and a Camunda 7 execution id is a UUID the engine hands out per
      // execution, so it addresses exactly one execution of one instance. That is
      // unlike Camunda 8, where a job or user-task KEY ignores the tenant and a
      // second adapter id on the same cluster can therefore be asked about a task of
      // its own. What the branch below cannot rely on is the business
      // key, which is why only it needs the filter.
      //
      // the scope the task RUNS IN - the process, an embedded subprocess, or the one
      // iteration of a multi-instance embedded subprocess it belongs to. Writing on
      // the task's own execution would reach a boundary conditional event and nothing
      // else, while the scope is what an event subprocess with a conditional start
      // event listens on
      runtimeService
          .setVariablesLocal(
              flowScopeExecutionIdOf(execution.getProcessInstanceId(), taskId),
              variables);
      return;
    }

    // narrowed to the scope of the CALL: a business key is the
    // workflow-aggregate id, unique per aggregate type and not across an engine, so the
    // key alone also matches a workflow of another workflow module, of another adapter id
    // during a migration, or of another application sharing the database. Writing there
    // would not only report the wrong thing, it would ADVANCE that workflow, because a
    // variable write is what makes Camunda 7 re-evaluate conditional events - and this
    // method writes even for an aggregate sharing nothing (the marker above)
    final var candidates = within(
        runtimeService.createProcessInstanceQuery().processInstanceBusinessKey(businessKey),
        io.vanillabp.integration.adapter.spi.WorkflowScope.of(workflowModuleId, bpmnProcessId))
        .list();
    if (candidates.size() > 1) {
      // one business key is one aggregate, so two instances of one scope carrying it is a
      // broken assumption rather than an ambiguity to resolve. It used to arrive as a
      // 'ProcessEngineException' from singleResult() naming neither the aggregate nor the
      // instances
      throw new IllegalStateException(
          ("Camunda7[%s]: aggregate '%s' of '%s/%s' is carried by %d workflows at once (%s)! A "
              + "workflow-aggregate id belongs to ONE workflow, so its changed values cannot be "
              + "pushed - check whether something started a second workflow with the same "
              + "aggregate.")
              .formatted(
                  adapterId,
                  businessKey,
                  workflowModuleId,
                  bpmnProcessId,
                  candidates.size(),
                  candidates
                      .stream()
                      .map(org.camunda.bpm.engine.runtime.ProcessInstance::getId)
                      .collect(java.util.stream.Collectors.joining(", "))));
    }
    final var processInstance = candidates.isEmpty()
        ? null
        : candidates.get(0);
    if (processInstance == null) {
      if (!tolerateGoneWorkflow) {
        throw new io.vanillabp.spi.process.WorkflowNotFoundException(
            ("The workflow of aggregate '%s' is not active in Camunda 7 (adapter '%s') - its changed "
                + "aggregate cannot be pushed!")
                .formatted(businessKey, adapterId));
      }
      log.warn(
          "Camunda7[{}]: the workflow of aggregate '{}' is gone - skipping the redelivered phase-two "
              + "push of the aggregate",
          adapterId,
          businessKey);
      return;
    }
    runtimeService.setVariables(processInstance.getId(), variables);

  }

  /**
   * The execution of the scope the task with the given ID RUNS IN: the process
   * instance, an embedded subprocess, or the one iteration of a multi-instance
   * embedded subprocess it belongs to.
   * <p>
   * Not the task's own context: an activity with boundary events (and every instance
   * of a multi-instance activity) gets a scope of its own in the engine, and writing
   * there would serve a boundary conditional event and nothing else. The scope AROUND
   * the task is what an event subprocess with a conditional start event listens on,
   * and what the rest of that scope reads.
   * <p>
   * The walk goes up the execution tree: from the task's execution to the closest
   * SCOPE execution, skipping the execution of the task's own activity scope and the
   * technical wrapper around the instances of a multi-instance activity.
   *
   * @param processInstanceId The workflow's process instance
   * @param taskId The parked execution of the task
   * @return The execution to write the local variables at (the process instance if
   *         the task cannot be located)
   */
  private String flowScopeExecutionIdOf(
      final String processInstanceId,
      final String taskId) {

    final var executions = runtimeService
        .createExecutionQuery()
        .processInstanceId(processInstanceId)
        .list()
        .stream()
        .filter(org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity.class::isInstance)
        .map(org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity.class::cast)
        .collect(
            java.util.stream.Collectors
                .toMap(
                    org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity::getId,
                    execution -> execution));

    var current = executions.get(taskId);
    if (current == null) {
      return processInstanceId;
    }
    final var processDefinitionId = current.getProcessDefinitionId();
    if (current.isScope() && activityHasAScopeOfItsOwn(processDefinitionId, current.getActivityId())) {
      // the scope of the task itself - the task runs in the scope around it
      current = executions.get(current.getParentId());
    }
    while (current != null) {
      if (current.isScope() && !isMultiInstanceBody(current)) {
        return current.getId();
      }
      current = executions.get(current.getParentId());
    }
    return processInstanceId;

  }

  /**
   * Whether the BPMN activity creates a scope of its own in the engine: an activity
   * with boundary events attached, or a multi-instance activity (whose instances each
   * get one).
   *
   * @param processDefinitionId The definition the workflow runs on
   * @param activityId The activity to look at
   * @return Whether the engine gives that activity a scope
   */
  private boolean activityHasAScopeOfItsOwn(
      final String processDefinitionId,
      final String activityId) {

    if (activityId == null) {
      return false;
    }
    try {
      final var model = repositoryService.getBpmnModelInstance(processDefinitionId);
      final var element = model.getModelElementById(activityId);
      if (element instanceof org.camunda.bpm.model.bpmn.instance.Activity activity) {
        if (activity.getLoopCharacteristics() != null) {
          return true;
        }
        return model
            .getModelElementsByType(org.camunda.bpm.model.bpmn.instance.BoundaryEvent.class)
            .stream()
            .anyMatch(boundaryEvent -> activityId.equals(boundaryEvent.getAttachedTo().getId()));
      }
      return false;
    } catch (final RuntimeException e) {
      log
          .debug(
              "Camunda7[{}]: could not read the model of process definition '{}' - assuming activity '{}' "
                  + "has no scope of its own",
              adapterId,
              processDefinitionId,
              activityId,
              e);
      return false;
    }

  }

  /**
   * Whether the execution is the technical wrapper around the instances of a
   * multi-instance activity. Its variables would be shared by all of them, so it is
   * never the scope a push is meant for.
   *
   * @param execution The execution to look at
   * @return Whether it is a multi-instance body
   */
  private boolean isMultiInstanceBody(
      final org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity execution) {

    final var activityId = execution.getActivityId();
    // the engine names it "<activity id>#multiInstanceBody"
    return (activityId != null) && activityId.endsWith("#"
        + org.camunda.bpm.engine.ActivityTypes.MULTI_INSTANCE_BODY);

  }

  /**
   * Reads the aggregate for the operator context of a workflow about to start. The
   * context is what an operator sees in Cockpit, so a persistence which cannot read
   * an aggregate by its ID must not keep the workflow from starting.
   *
   * @param aggregatePersistence The aggregate's persistence
   * @param workflowAggregateId The aggregate's ID
   * @return The aggregate or <code>null</code> if it cannot be read
   */
  private A aggregateForOperatorContext(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    if (aggregatePersistence == null) {
      return null;
    }
    try {
      return aggregatePersistence.loadById(workflowAggregateId);
    } catch (final RuntimeException e) {
      log.warn(
          "Camunda7[{}]: could not read the aggregate '{}' to write the operator context of "
              + "the workflow about to start - starting it without that context",
          adapterId,
          workflowAggregateId,
          e);
      return null;
    }

  }


  @Override
  public java.util.List<io.vanillabp.spi.process.ProcessDefinition> getProcessDefinitions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    return viewer.getProcessDefinitions(
        workflowModuleId,
        scopedProcessId(workflowModuleId, bpmnProcessId),
        tenantIdOf(workflowModuleId),
        workflowAggregateId,
        historyContext);

  }

  @Override
  public java.io.InputStream getBpmnXml(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String processDefinitionId) {

    return viewer.getBpmnXml(processDefinitionId);

  }

  @Override
  public io.vanillabp.spi.process.WorkflowHistory getWorkflowHistory(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    return viewer.getWorkflowHistory(
        workflowModuleId,
        scopedProcessId(workflowModuleId, bpmnProcessId),
        tenantIdOf(workflowModuleId),
        workflowAggregateId,
        historyContext);

  }

}
