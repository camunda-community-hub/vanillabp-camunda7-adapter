package io.vanillabp.camunda7.quarkus.runtime;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agroal.api.AgroalDataSource;
import io.vanillabp.camunda7.deployment.Camunda7WorkflowProcessingLifecycle;
import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.camunda7.engine.Camunda7JobExecutorLifecycle;
import io.vanillabp.camunda7.wiring.Camunda7AsyncBpmnParseListener;
import io.vanillabp.camunda7.wiring.Camunda7TaskExpressionManager;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import jakarta.transaction.TransactionManager;

/**
 * Owns ONE embedded Camunda 7 engine per configured adapter id on Quarkus (engine
 * name <code>vanillabp-camunda7-&lt;id&gt;</code>) - the Quarkus counterpart of the
 * Spring Boot module's engine holder. It wires:
 * <ul>
 *   <li>{@link Camunda7QuarkusProcessEngineConfiguration} on an Agroal datasource
 *       (the application's default, or the NAMED datasource configured via
 *       <code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>) with the CDI
 *       Narayana {@link TransactionManager};</li>
 *   <li>the engine classloader is pinned to the Quarkus runtime TCCL - without it
 *       the job executor's threads fail with {@code ClassNotFoundException} on
 *       delegate classes. The engine's own thread pool is kept rather than a
 *       context-propagating {@code ManagedExecutor}: with the classloader pinned it
 *       does the job, and the C7 family runs in JVM mode only;</li>
 *   <li>job-executor activation is DEFERRED to the deployment pipeline's
 *       {@code startWorkflowProcessing} (the shared reference-counted
 *       {@link Camunda7JobExecutorLifecycle} - the same semantics as on Spring
 *       Boot);</li>
 *   <li>where <code>sleep-until-something-is-due</code> is configured the engine gets the
 *       core's {@code Camunda7SleepingJobExecutor}, whose acquisition waits for the next due
 *       date rather than polling.</li>
 * </ul>
 */
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public class Camunda7QuarkusEngineHolder implements Camunda7WorkflowProcessingLifecycle, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(Camunda7QuarkusEngineHolder.class);

  private final String adapterId;

  /**
   * The task connectables of this engine, registered by the deployment service
   * during wireBpmn and looked up by the engine's EL resolver.
   */
  private final Camunda7TaskRegistry taskRegistry = new Camunda7TaskRegistry();

  private final boolean usesSeparateDataSource;

  /**
   * Whether the core's entry point for ended workflows was handed over, see
   * {@link #deliversWorkflowEnded()}.
   */
  private final boolean deliversWorkflowEnded;

  private final ProcessEngine processEngine;

  private final Camunda7JobExecutorLifecycle jobExecutorLifecycle;

  private volatile boolean closed = false;

  /**
   * Whether this engine reports the end of a workflow, which it does when the core
   * handed over its invoker. False means every <code>&#64;WorkflowEnded</code> method
   * of a process deployed here stays silent - the deployment service says so instead
   * of leaving the application waiting.
   *
   * @return Whether the end listener was attached
   */
  public boolean deliversWorkflowEnded() {

    return deliversWorkflowEnded;

  }

  /**
   * Whether the application asked to be told about the end of workflows of the
   * process definition the engine is parsing.
   *
   * @param workflowEndedInvoker The core's invoker
   * @param tenantId The tenant of the deployment (may be <code>null</code>)
   * @param processDefinitionKey The process definition key the engine parses
   * @return Whether an end listener has to be attached
   */
  private boolean workflowEndedHandlerExists(
      final io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker workflowEndedInvoker,
      final String tenantId,
      final String processDefinitionKey) {

    final var workflowModuleId = taskRegistry.resolveWorkflowModuleId(tenantId, processDefinitionKey);
    if (workflowModuleId == null) {
      return false;
    }
    return workflowEndedInvoker
        .workflowEndedHandlerExists(
            workflowModuleId,
            taskRegistry.plainBpmnProcessId(workflowModuleId, processDefinitionKey));

  }

  /**
   * There is deliberately only ONE constructor, and it takes every invoker the core
   * offers. The convenience constructors this class used to have filled the missing
   * ones with <code>null</code>, which silently switched off a feature: the engine
   * attached no end listener, the application booted without a warning and a
   * <code>&#64;WorkflowEnded</code> method was simply never called. What
   * an engine does not support is the adapter's decision, not something a forgotten
   * argument decides.
   *
   * @param adapterId The adapter id
   * @param properties The adapter id's engine settings
   *        (<code>vanillabp.adapters.&lt;id&gt;.*</code>)
   * @param dataSource The Agroal datasource the engine runs on
   * @param usesSeparateDataSource Whether the datasource is a NAMED one (not the
   *        application's default) - the engine cannot join the caller's transaction
   *        then, which the task delivery has to account for
   * @param transactionManager The CDI (Narayana) transaction manager
   * @param workflowTaskInvoker Where the application's method is called, handed to the
   *        listeners this engine is given
   * @param bpmsInitiatedStartInvoker The core's notification of a workflow the engine
   *        started on its own, or <code>null</code> where no method asks for one
   * @param workflowEndedInvoker The core's notification of a workflow which ended, or
   *        <code>null</code> where the application has no method for it
   */
  public Camunda7QuarkusEngineHolder(
      final String adapterId,
      final Camunda7EngineProperties properties,
      final AgroalDataSource dataSource,
      final boolean usesSeparateDataSource,
      final TransactionManager transactionManager,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker,
      final io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker workflowEndedInvoker) {

    this.adapterId = adapterId;
    this.usesSeparateDataSource = usesSeparateDataSource;
    this.deliversWorkflowEnded = workflowEndedInvoker != null;
    // every listener building an invocation context has to know whose transaction it
    // delivers in, and the datasource this engine was configured with is what decides it
    this.taskRegistry.setEngineRunsOnItsOwnDataSource(usesSeparateDataSource);

    // An adapter id running on a table prefix needs its tables to exist -
    // Camunda's schema management ignores the prefix and would create a set of
    // unprefixed ACT_* tables here. Asked BEFORE the engine is built, so those tables
    // are never written
    io.vanillabp.camunda7.engine.Camunda7TablePrefixSchema.validate(adapterId, properties, dataSource);

    final var parseListener = new Camunda7AsyncBpmnParseListener(
        new io.vanillabp.camunda7.wiring.Camunda7TaskCancellationListener(
            workflowTaskInvoker, taskRegistry), new io.vanillabp.camunda7.wiring.Camunda7UserTaskEventListener(
                workflowTaskInvoker, taskRegistry), bpmsInitiatedStartInvoker == null
                    ? null
                    : kind -> new io.vanillabp.camunda7.wiring.Camunda7BpmsInitiatedStartListener(
                        bpmsInitiatedStartInvoker, taskRegistry, kind, (
                            workflowModuleId,
                            bpmnProcessId) -> servesTheProcess(
                                workflowTaskInvoker, workflowModuleId, bpmnProcessId)));
    if (workflowEndedInvoker != null) {
      parseListener
          .setWorkflowEnded(
              new io.vanillabp.camunda7.wiring.Camunda7WorkflowEndedListener(
                  workflowEndedInvoker, taskRegistry),
              (
                  tenantId,
                  processDefinitionKey) -> workflowEndedHandlerExists(
                      workflowEndedInvoker, tenantId, processDefinitionKey));
    }

    final var configuration = new Camunda7QuarkusProcessEngineConfiguration(transactionManager);
    // VanillaBP task wiring: top-level EL names resolve @WorkflowTask methods and
    // workflow-aggregate attributes; the parse listener aligns transaction
    // boundaries with remote BPMS (async before/after)
    configuration.setExpressionManager(new Camunda7TaskExpressionManager(taskRegistry, workflowTaskInvoker));
    configuration.setCustomPreBPMNParseListeners(new java.util.ArrayList<>(
        java.util.List.of(parseListener)));
    configuration.setProcessEngineName("vanillabp-camunda7-%s".formatted(adapterId));
    configuration.setDataSource(dataSource);
    configuration.setDatabaseSchemaUpdate(properties.getDatabaseSchemaUpdate());
    // activation is deferred to startWorkflowProcessing (26e semantics)
    configuration.setJobExecutorActivate(false);
    // an adapter id which may let its engine sleep needs the acquisition loop which asks
    // for the next due date, and that loop is installed by the executor itself. Without
    // the sleep the engine builds its own executor, exactly as before
    if (properties.sleepsUntilSomethingIsDue()) {
      configuration.setJobExecutor(new io.vanillabp.camunda7.engine.Camunda7SleepingJobExecutor(adapterId));
    }
    // What an idle engine is allowed to stop doing: waiting for the next due date instead
    // of polling, waking on a commit, and leaving the metrics reporter's timer alone.
    // Applied for every adapter id, because the metrics reporter is a setting of its own
    io.vanillabp.camunda7.engine.Camunda7JobExecutorSleep.applyTo(adapterId, configuration, properties);
    // job-executor threads must load delegate classes via the Quarkus runtime
    // classloader (proven pitfall: ClassNotFoundException otherwise)
    configuration.setClassLoader(Thread.currentThread().getContextClassLoader());
    // Camunda 7.24 rejects deployments of processes without a history-time-to-live.
    // Provide an engine-wide default so BPMN models need not declare it individually
    // (a process may still override it via camunda:historyTimeToLive).
    configuration.setHistoryTimeToLive(properties.getHistoryTimeToLive());
    // Nested values shared by a workflow aggregate become object variables, and
    // the format they are stored in is the application's choice - configured once at the
    // adapter and applied to the engine here, so nobody has to touch the engine
    // configuration for it
    if ((properties.getSerializationFormat() != null) && !properties.getSerializationFormat().isBlank()) {
      configuration.setDefaultSerializationFormat(properties.getSerializationFormat());
    }
    // an own table prefix makes two adapter ids distinct engines on ONE datasource
    // (the side-by-side migration setup on a single database). The tables
    // of the prefix exist - Camunda7TablePrefixSchema asked about that above
    if ((properties.getTablePrefix() != null) && !properties.getTablePrefix().isBlank()) {
      configuration.setDatabaseTablePrefix(properties.getTablePrefix());
    }

    // The engine plugins - the way a serialization dataformat (camunda-xstream,
    // SPIN) reaches an embedded engine. Two ways in: configured per adapter id
    // ('vanillabp.adapters.<id>.engine-plugins', properties applied by Camunda itself), or
    // contributed as a CDI bean, which applies to every engine this adapter builds
    final var plugins = new java.util.LinkedList<org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin>(
        io.vanillabp.camunda7.engine.Camunda7EnginePlugins
            .of(adapterId, properties.getEnginePlugins()));
    plugins
        .addAll(
            io.quarkus.arc.Arc
                .container()
                .listAll(org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin.class)
                .stream()
                .map(io.quarkus.arc.InstanceHandle::get)
                .toList());
    if (!plugins.isEmpty()) {
      configuration
          .getProcessEnginePlugins()
          .addAll(plugins);
      log.info(
          "Camunda7[{}]: applying {} engine plugin(s) of the application: {}",
          adapterId,
          plugins.size(),
          plugins
              .stream()
              .map(plugin -> plugin.getClass().getName())
              .toList());
    }

    // what an extension contributes to THIS engine: parse listeners before or after
    // VanillaBP's own, and a history event handler installed next to the engine's
    io.vanillabp.camunda7.engine.Camunda7EngineCustomizers
        .apply(
            adapterId,
            configuration,
            io.quarkus.arc.Arc
                .container()
                .listAll(io.vanillabp.camunda7.engine.Camunda7EngineCustomizer.class)
                .stream()
                .map(io.quarkus.arc.InstanceHandle::get)
                .toList());

    this.processEngine = configuration.buildProcessEngine();
    this.jobExecutorLifecycle = new Camunda7JobExecutorLifecycle(
        adapterId, ((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration()).getJobExecutor());

  }

  /**
   * What the listeners of this engine look a task up in. One registry per engine, because
   * one engine serves the workflow modules of one adapter id.
   *
   * @return This engine's task registry
   */
  public Camunda7TaskRegistry getTaskRegistry() {

    return taskRegistry;

  }

  /**
   * Which configured adapter id this engine belongs to.
   *
   * @return The adapter id
   */
  public String getAdapterId() {

    return adapterId;

  }

  /**
   * The engine itself, for a caller which needs something none of the services below
   * offers.
   *
   * @return The embedded engine of this adapter id
   */
  public ProcessEngine getProcessEngine() {

    return processEngine;

  }

  /**
   * The engine's tasks, where a user task is completed.
   *
   * @return The task service of this engine
   */
  public org.camunda.bpm.engine.TaskService getTaskService() {

    return processEngine.getTaskService();

  }

  /**
   * The engine's runtime, where a workflow is started and a message is correlated.
   *
   * @return The runtime service of this engine
   */
  public RuntimeService getRuntimeService() {

    return processEngine.getRuntimeService();

  }

  /**
   * The engine's repository, where the resources of a workflow module are deployed.
   *
   * @return The repository service of this engine
   */
  public RepositoryService getRepositoryService() {

    return processEngine.getRepositoryService();

  }

  /**
   * The engine's history, which is what the viewer API reads.
   *
   * @return The history service of this engine
   */
  public org.camunda.bpm.engine.HistoryService getHistoryService() {

    return processEngine.getHistoryService();

  }

  /**
   * Whether this engine has a datasource of its own. On the application's default
   * datasource an engine command joins the caller's transaction, which is the guarantee an
   * embedded engine is chosen for; on a named one it cannot.
   *
   * @return Whether this adapter id's engine runs on a NAMED datasource (see class
   *         comment)
   */
  public boolean usesSeparateDataSource() {

    return usesSeparateDataSource;

  }

  /**
   * Visible for tests asserting the deferred activation.
   *
   * @return Whether the job executor is currently active
   */
  public boolean isJobExecutorActive() {

    return jobExecutorLifecycle.isActive();

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId) {

    jobExecutorLifecycle.startWorkflowProcessing(workflowModuleId);

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId) {

    jobExecutorLifecycle.stopWorkflowProcessing(workflowModuleId);

  }

  /**
   * Shutdown ordering: job executor stop &rarr; engine close (the Agroal datasource
   * itself is owned and closed by Quarkus). Safe to call more than once.
   */
  @Override
  public synchronized void close() {

    if (closed) {
      return;
    }
    closed = true;

    jobExecutorLifecycle.shutdown();
    processEngine.close();
    log.info("Camunda7[{}]: engine closed", adapterId);

  }


  /**
   * Whether a workflow service of this application serves that BPMN process. The engine
   * holds every definition deployed against its database, so a start of a process this
   * application does not claim reaches the listener as well - and the core has no workflow
   * service to answer for it.
   *
   * @param workflowTaskInvoker The core's entry point, which knows the workflow services
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   * @return Whether the core knows a workflow service for it
   */
  private static boolean servesTheProcess(
      final WorkflowTaskInvoker workflowTaskInvoker,
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (workflowTaskInvoker == null) {
      return true;
    }
    try {
      return workflowTaskInvoker.resolveWorkflowAggregateIdName(workflowModuleId, bpmnProcessId) != null;
    } catch (final RuntimeException e) {
      return false;
    }

  }

}
