package io.vanillabp.camunda7.wiring;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;

/**
 * The task connectables of ONE Camunda 7 engine (= one adapter id), registered by
 * the deployment service during <code>wireBpmn</code> and looked up by the
 * {@link Camunda7TaskELResolver} whenever the engine evaluates a top-level EL name.
 * Keyed by (workflow module ID, BPMN process ID) - one engine serves several workflow
 * modules. A caller which only has what the engine reports hands the tenant over and
 * gets the module back, see {@link #resolveWorkflowModuleId(String, String)}.
 * <p>
 * A running workflow asks for the tasks of its OWN process definition, see
 * {@link #tasksOf(String, String, String, Supplier)}. Two versions of a process may name
 * different expressions at the same element, and only the version a workflow runs on says
 * which of them it means.
 */
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings({
    "LombokGetterMayBeUsed", "LombokSetterMayBeUsed"
})
public class Camunda7TaskRegistry {

  /**
   * Starts out empty. One registry belongs to one engine, and the deployment of every
   * workflow module that engine serves fills it.
   */
  public Camunda7TaskRegistry() {

  }

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId) {
  }

  private final Map<RegistryKey, List<Camunda7TaskConnectable>> connectables = new ConcurrentHashMap<>();

  /**
   * The versions of the engine's process definitions - handed over by the
   * deployment service, which owns the engine's {@code RepositoryService}. Every
   * listener building an invocation context reaches it through this registry. May be
   * <code>null</code> (tests): no version is reported then, which matches every
   * method.
   */
  private Camunda7ProcessVersions processVersions;

  /**
   * The id of the adapter owning this engine. Reported with every inbound delivery,
   * so VanillaBP can record which BPMS holds a workflow. May be
   * <code>null</code> (tests): nothing is recorded then.
   */
  private String adapterId;

  /**
   * Hands over which adapter holds this engine. It arrives after construction because the
   * registry is built with the engine configuration, before the adapter id is known there.
   *
   * @param adapterId The id of the adapter owning this engine
   */
  public void setAdapterId(
      final String adapterId) {

    this.adapterId = adapterId;

  }

  /**
   * Which adapter every delivery of this engine is reported under, so the core can record
   * where a workflow lives.
   *
   * @return The id of the adapter owning this engine or <code>null</code>
   */
  public String getAdapterId() {

    return adapterId;

  }

  /**
   * The core's name-clash-avoidance model, which the {@link Camunda7TaskELResolver}
   * needs to translate the error code of a {@code TaskException} into what the engine
   * knows. It lives here for the same reason {@link #adapterId} does: the resolver is
   * built by the engine configuration, so nothing which creates it can hand it
   * anything. May be <code>null</code> (tests): the plain identifiers apply then.
   */
  private io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  /**
   * Hands over how an identifier is kept apart from the one of another workflow module. It
   * arrives after construction for the same reason the adapter id does.
   *
   * @param scoping The core's name-clash-avoidance model
   */
  public void setScoping(
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping) {

    this.scoping = scoping;

  }

  /**
   * What translates between the identifiers the application wrote and the ones the engine
   * was given. Every path out of this registry needs it, which is why it is published.
   *
   * @return The core's name-clash-avoidance model or <code>null</code>
   */
  public io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport getScoping() {

    return scoping;

  }

  /**
   * Whether this engine was given a datasource of its own
   * (<code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>), which is the one thing
   * about the engine every listener building an invocation context has to know: an engine
   * on the application's datasource delivers inside the transaction which persists the
   * workflow aggregate, an engine on its own datasource cannot. Set by the platform
   * integration, which builds the engine; <code>false</code> in tests, which is the
   * shared-datasource answer this adapter had before an own datasource was configurable.
   */
  private boolean engineRunsOnItsOwnDataSource;

  /**
   * Tells the registry whether the engine shares the application's datasource. A delivery
   * of a shared engine commits together with the workflow aggregate, and one of a separate
   * engine does not, which is the difference every delivery path asks about.
   *
   * @param engineRunsOnItsOwnDataSource Whether the engine runs on a datasource of its
   *          own
   */
  public void setEngineRunsOnItsOwnDataSource(
      final boolean engineRunsOnItsOwnDataSource) {

    this.engineRunsOnItsOwnDataSource = engineRunsOnItsOwnDataSource;

  }

  /**
   * Whether this engine has a datasource of its own, and therefore its own transaction.
   *
   * @return Whether the engine runs on a datasource of its own
   */
  public boolean engineRunsOnItsOwnDataSource() {

    return engineRunsOnItsOwnDataSource;

  }

  /**
   * Hands over what the engine knows about its deployed versions, which is how a delivery
   * finds the method serving the version it belongs to.
   *
   * @param processVersions The versions of the engine's process definitions
   */
  public void setProcessVersions(
      final Camunda7ProcessVersions processVersions) {

    this.processVersions = processVersions;

  }

  /**
   * The version of a running execution's process definition, matched against the
   * <code>version</code> attribute of the application's annotations.
   *
   * @param processDefinitionId The engine's process definition id
   * @return The version or <code>null</code> if it cannot be determined
   */
  public String versionOfDefinition(
      final String processDefinitionId) {

    return processVersions == null
        ? null
        : processVersions.versionOfDefinition(processDefinitionId);

  }

  /**
   * The deployed version behind a process definition id, answered from the same cache
   * {@link #versionOfDefinition(String)} reads, so a caller pays the engine for that
   * definition once.
   *
   * @param processDefinitionId The engine's process definition id
   * @return The version and its tag, or <code>null</code> where the engine does not know
   *         that definition (any more) or no deployment service was handed over (tests)
   */
  public io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion definitionOf(
      final String processDefinitionId) {

    return processVersions == null
        ? null
        : processVersions.definitionOf(processDefinitionId);

  }

  /**
   * The one question about a pair of BPMN processes which is needed again after the
   * application has started: do they work on the same workflow aggregate.
   */
  public interface WorkflowAggregateSharing {

    /**
     * Whether two BPMN processes of one workflow module work on the same workflow
     * aggregate.
     *
     * @param workflowModuleId The workflow module id
     * @param bpmnProcessId The BPMN process id
     * @param otherBpmnProcessId The BPMN process id to compare it with
     * @return Whether both of them serve the same workflow aggregate
     */
    boolean workflowsShareTheWorkflowAggregate(
        String workflowModuleId,
        String bpmnProcessId,
        String otherBpmnProcessId);

  }

  private WorkflowAggregateSharing workflowAggregateSharing;

  /**
   * Hands over who answers whether two BPMN processes work on the same workflow
   * aggregate. That is the core, which reads it from the declarations of the workflow
   * services while the application starts.
   *
   * @param workflowAggregateSharing Who answers the question
   */
  public void setWorkflowAggregateSharing(
      final WorkflowAggregateSharing workflowAggregateSharing) {

    this.workflowAggregateSharing = workflowAggregateSharing;

  }

  /**
   * Whether two BPMN processes of one workflow module work on the same workflow
   * aggregate, which means that a call between them continues one business case.
   * <p>
   * {@link Camunda7CallActivities} asks the same question
   * while a model is prepared and writes the answer into the model. A call activity
   * naming the process to call in an EXPRESSION leaves nothing to write it onto, so the
   * start listener of the called process asks again while the workflow runs.
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The BPMN process id
   * @param otherBpmnProcessId The BPMN process id to compare it with
   * @return Whether both of them serve the same workflow aggregate;
   *         <code>false</code> where nobody handed over an answer (tests)
   */
  public boolean workflowsShareTheWorkflowAggregate(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String otherBpmnProcessId) {

    return (workflowAggregateSharing != null) && workflowAggregateSharing
        .workflowsShareTheWorkflowAggregate(workflowModuleId, bpmnProcessId, otherBpmnProcessId);

  }

  /**
   * Answers whether a <code>&#64;WorkflowService</code> class of the application claims a BPMN
   * process: the core's {@code WorkflowTaskWiring#isClaimedByAWorkflowService}.
   * <code>null</code> until the deployment service hands it over.
   */
  private java.util.function.BiPredicate<String, String> claimedProcesses;

  /**
   * Hands over who answers whether the application claims a BPMN process. That is the core,
   * and the deployment service hands its answer over while it is built, before anything is
   * deployed or parsed.
   *
   * @param claimedProcesses Answers for a workflow module and a PLAIN BPMN process id
   */
  public void setClaimedProcesses(
      final java.util.function.BiPredicate<String, String> claimedProcesses) {

    this.claimedProcesses = claimedProcesses;

  }

  /**
   * Whether a <code>&#64;WorkflowService</code> class of the application claims the BPMN
   * process. Only a claimed process gets anything from this adapter beyond being deployed with
   * its file: no flag, no listener, no check. See {@code DECISIONS.pending/937.md}.
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The PLAIN BPMN process id
   * @return Whether the application claims the process; <code>true</code> where nobody handed
   *         over an answer, which is an engine built for a test without a deployment service
   */
  public boolean isClaimedByAWorkflowService(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return (claimedProcesses == null) || claimedProcesses.test(workflowModuleId, bpmnProcessId);

  }

  /**
   * Whether the application claims the process definition the engine reports. The engine holds
   * every definition deployed against its database: the ones of this application, the ones it
   * deploys without claiming them, and the ones somebody else deployed. Only the first kind is
   * claimed. A definition this adapter did not deploy has no workflow module to be found under,
   * and it is not claimed whatever its key is.
   *
   * @param tenantId The tenant of the definition, <code>null</code> where it has none
   * @param processDefinitionKey The key of the definition, as the engine knows it
   * @return Whether the application claims it
   */
  public boolean claimsTheProcessDefinition(
      final String tenantId,
      final String processDefinitionKey) {

    final var workflowModuleId = resolveWorkflowModuleId(tenantId, processDefinitionKey);
    if (workflowModuleId == null) {
      return false;
    }
    return isClaimedByAWorkflowService(
        workflowModuleId,
        plainBpmnProcessId(workflowModuleId, processDefinitionKey));

  }

  /**
   * Which workflow module a process definition key belongs to - the way back when
   * there is no tenant to ask (prefixed identifiers, see decision 3 in the
   * repository's DECISIONS.md).
   */
  private final Map<String, String> workflowModuleIdsByScopedProcessId = new ConcurrentHashMap<>();

  /**
   * The plain BPMN process id per (workflow module, scoped process id) - filled for
   * every wired process, so a process without tasks is found as well.
   */
  private final Map<RegistryKey, String> plainProcessIdsByScopedProcessId = new ConcurrentHashMap<>();

  /**
   * The PLAIN signal name per signal start event, keyed by (workflow module, scoped
   * process id, start event id): the engine's parser cannot resolve a signalRef, and
   * the name the application is told has to be the modelled one.
   */
  private final Map<String, String> signalNamesOfStartEvents = new ConcurrentHashMap<>();

  /**
   * Which serialization format nested shared values are stored in - provided
   * by the platform integration, which binds the configuration. The task path asks the
   * registry because it has it at hand; <code>null</code> in tests, where the engine's
   * default applies.
   */
  private io.vanillabp.camunda7.sync.Camunda7SerializationFormats serializationFormats;

  /**
   * Hands over how a nested shared value is serialized. The task path asks the registry
   * because it has the registry at hand, not because the answer belongs here.
   *
   * @param serializationFormats The format resolution of the platform integration
   */
  public void setSerializationFormats(
      final io.vanillabp.camunda7.sync.Camunda7SerializationFormats serializationFormats) {

    this.serializationFormats = serializationFormats;

  }

  /**
   * The configured format for nested shared values of one workflow, or <code>null</code>.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The format or <code>null</code>
   */
  public String serializationFormatFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return serializationFormats != null
        ? serializationFormats.formatFor(workflowModuleId, bpmnProcessId)
        : null;

  }

  /**
   * One served listener which has to be told that its element was canceled.
   *
   * @param elementId The BPMN element the listener sits on
   * @param taskDefinition The listener's task definition, which is what resolves its
   *          <code>&#64;WorkflowTask</code> method
   */
  public record ListenerNeedingACancellation(
                                             String elementId,
                                             String taskDefinition) {
  }

  /**
   * Per process definition key the engine knows, the served listeners which have to be told
   * about a cancellation. Read while the engine PARSES a model, which is why it is keyed by
   * what the engine reports there.
   */
  private final Map<RegistryKey, List<ListenerNeedingACancellation>> listenersNeedingACancellation = new ConcurrentHashMap<>();

  /**
   * Remembers a served listener whose method has to hear the cancellation of its element.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @param elementId The BPMN element the listener sits on
   * @param taskDefinition The listener's task definition
   */
  public void registerListenerNeedingACancellation(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String elementId,
      final String taskDefinition) {

    final var listener = new ListenerNeedingACancellation(elementId, taskDefinition);
    final var known = listenersNeedingACancellation
        .computeIfAbsent(
            new RegistryKey(workflowModuleId, scopedBpmnProcessId),
            key -> new CopyOnWriteArrayList<>());
    // a second deployment of the same model registers the same listener again, and the engine
    // would then notify the one method twice
    if (!known.contains(listener)) {
      known.add(listener);
    }

  }

  /**
   * The served listeners of one process which have to be told about a cancellation.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @return The listeners, empty where none was registered
   */
  public List<ListenerNeedingACancellation> listenersNeedingACancellation(
      final String workflowModuleId,
      final String scopedBpmnProcessId) {

    return listenersNeedingACancellation
        .getOrDefault(new RegistryKey(workflowModuleId, scopedBpmnProcessId), List.of());

  }

  /**
   * Remembers one wired task. Everything is keyed by what the ENGINE reports at runtime,
   * so a delivery is looked up without translating anything first.
   *
   * @param connectable The task of a model, as the wiring extracted it
   */
  public void register(
      final Camunda7TaskConnectable connectable) {

    // keyed by what the ENGINE reports at runtime: the scoped process id (equal to
    // the plain one unless the module's identifiers are prefixed)
    connectables
        .computeIfAbsent(
            new RegistryKey(connectable.workflowModuleId(), connectable.scopedBpmnProcessId()),
            key -> new CopyOnWriteArrayList<>())
        .add(connectable);
    workflowModuleIdsByScopedProcessId
        .putIfAbsent(connectable.scopedBpmnProcessId(), connectable.workflowModuleId());
    plainProcessIdsByScopedProcessId
        .putIfAbsent(
            new RegistryKey(connectable.workflowModuleId(), connectable.scopedBpmnProcessId()),
            connectable.bpmnProcessId());

  }

  /**
   * Registers what a process is called on both sides, without any task being
   * involved: a process the BPMS starts on its own (timer, signal or conditional
   * start event) may have no tasks at all, and the start listener still has to find
   * its way back from the engine's process-definition key to the workflow module and
   * the plain BPMN process id.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   */
  public void registerProcess(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId) {

    plainProcessIdsByScopedProcessId
        .putIfAbsent(new RegistryKey(workflowModuleId, scopedBpmnProcessId), bpmnProcessId);
    workflowModuleIdsByScopedProcessId.putIfAbsent(scopedBpmnProcessId, workflowModuleId);

  }

  /**
   * Which workflow module a Camunda TENANT belongs to. The two names are the same
   * unless the application gave a workflow module a tenant name of its own, which
   * {@link Camunda7ConfiguredTenant} lets it do, and then the engine reports a name
   * this registry is not keyed by.
   */
  private final Map<String, String> workflowModuleIdsByTenantId = new ConcurrentHashMap<>();

  /**
   * Registers under which Camunda tenant a workflow module reaches the engine, so an
   * execution reporting that tenant leads back to the module. Called by the deployment
   * service while it wires a workflow module, with the very name the deployment uses.
   *
   * @param tenantId The tenant the module is deployed to, <code>null</code> where the
   *          mode uses none
   * @param workflowModuleId The workflow module ID
   */
  public void registerTenant(
      final String tenantId,
      final String workflowModuleId) {

    if ((tenantId == null) || (workflowModuleId == null)) {
      return;
    }
    workflowModuleIdsByTenantId.putIfAbsent(tenantId, workflowModuleId);

  }

  /**
   * Registers the plain signal name of a signal start event.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @param startEventId The BPMN id of the start event
   * @param signalName The PLAIN signal name
   */
  public void registerSignalStartEvent(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String startEventId,
      final String signalName) {

    if (signalName == null) {
      return;
    }
    signalNamesOfStartEvents
        .putIfAbsent(signalKey(workflowModuleId, scopedBpmnProcessId, startEventId), signalName);

  }

  /**
   * Which signal started a workflow, in the name the application wrote. The engine reports
   * the start event and not the signal, so the name is remembered while the model is wired
   * and read back here.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The process definition key the engine reported
   * @param startEventId The BPMN id of the start event which fired
   * @return The plain signal name or <code>null</code>
   */
  public String signalNameOfStartEvent(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String startEventId) {

    return signalNamesOfStartEvents.get(signalKey(workflowModuleId, scopedBpmnProcessId, startEventId));

  }

  private static String signalKey(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String startEventId) {

    return "%s|%s|%s".formatted(workflowModuleId, scopedBpmnProcessId, startEventId);

  }

  /**
   * The workflow module of a running execution. Camunda's tenant answers it whenever
   * the module is isolated by a tenant; with prefixed identifiers there
   * is no tenant, so the module is looked up by the process definition key the
   * wiring registered - a KNOWN value, never parsed out of the key.
   * <p>
   * The tenant is not the module id where the application named the tenant itself. It is
   * therefore translated through what the deployment registered, and only a tenant nobody
   * registered is taken as the module id: that is a tenant of another application on the
   * same engine, and answering it unchanged keeps the old behaviour for everything which
   * never configured a name.
   *
   * @param tenantId The execution's tenant ID (may be <code>null</code>)
   * @param processDefinitionKey The execution's process definition key
   * @return The workflow module ID or <code>null</code> if unknown
   */
  public String resolveWorkflowModuleId(
      final String tenantId,
      final String processDefinitionKey) {

    if (tenantId != null) {
      return workflowModuleIdsByTenantId.getOrDefault(tenantId, tenantId);
    }
    return workflowModuleIdsByScopedProcessId.get(processDefinitionKey);

  }

  /**
   * The PLAIN BPMN process id of a process definition key the engine reported.
   *
   * @param workflowModuleId The workflow module ID
   * @param processDefinitionKey The execution's process definition key
   * @return The plain BPMN process ID (the key itself if nothing was registered)
   */
  public String plainBpmnProcessId(
      final String workflowModuleId,
      final String processDefinitionKey) {

    final var registered = plainProcessIdsByScopedProcessId
        .get(new RegistryKey(workflowModuleId, processDefinitionKey));
    if (registered != null) {
      return registered;
    }
    return connectables
        .getOrDefault(new RegistryKey(workflowModuleId, processDefinitionKey), List.of())
        .stream()
        .map(Camunda7TaskConnectable::bpmnProcessId)
        .findFirst()
        .orElse(processDefinitionKey);

  }

  /**
   * One BPMN process of one workflow module, named the way the application wrote it.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   */
  public record WorkflowProcess(
                                String workflowModuleId,
                                String bpmnProcessId) {
  }

  /**
   * The way back from what the engine reports to what the application wrote: a tenant and a
   * process definition key become the workflow module and the plain BPMN process id.
   *
   * <h4>When the answer is complete</h4>
   *
   * A process enters this registry while the deployment pipeline wires it, in
   * <code>wireBpmn</code>, which is before the engine parses that workflow module's files
   * and long before any workflow of it runs. A process the engine only still HOLDS under a
   * declared id enters it while <code>startWorkflowProcessing</code> runs, which is the last
   * step of the pipeline. So the answer is complete for a workflow module once
   * <code>wireBpmn</code> ran for it, and complete for the application once the pipeline
   * finished. Asking earlier is asking a registry which is still being filled, and that is
   * what a second registry filled at another stage of the pipeline gets wrong: the two see a
   * process at different moments and the process is reported under the wrong module.
   *
   * <h4>Which name a caller hands over</h4>
   *
   * The one the ENGINE reports, which is what an execution, a task or a process definition
   * carries. That name is the workflow module id only as long as nobody configured a tenant
   * name of its own (<code>vanillabp.workflow-modules.&lt;id&gt;.adapters.&lt;adapter&gt;.tenant-id</code>
   * and the adapter-wide key next to it); where somebody did, the engine reports the
   * configured name and this registry translates it back. So a caller which has the module
   * id at hand and no tenant may pass the module id as well, because the two are the same
   * name for every application which left the key alone, and where they differ the
   * translation answers both. Passing anything else is asking about another application's
   * tenant, and the answer to that is empty.
   *
   * <h4>What an empty answer means</h4>
   *
   * That this application deployed no such process. An embedded engine may hold the
   * definitions of another application on the same database, and a definition of a release
   * this application no longer carries stays in the engine as well. Neither is an error and
   * neither is guessed at: nothing is parsed out of the key, because a prefix somebody cuts
   * off a string is a prefix which drifts apart from the one the adapter wrote (see decision
   * 3 in the repository's DECISIONS.md).
   *
   * @param tenantId The tenant the engine stored, <code>null</code> where it stored none
   * @param processDefinitionKey The process definition key the engine stored
   * @return The workflow module and the plain BPMN process id, or empty
   */
  public Optional<WorkflowProcess> resolve(
      final String tenantId,
      final String processDefinitionKey) {

    if (processDefinitionKey == null) {
      return Optional.empty();
    }
    final var workflowModuleId = resolveWorkflowModuleId(tenantId, processDefinitionKey);
    if (workflowModuleId == null) {
      return Optional.empty();
    }
    // deliberately NOT plainBpmnProcessId(): that one answers the key itself for a process
    // nobody registered, which is the right answer for a listener of a wired process and
    // the wrong one here, where "this application did not deploy it" has to be sayable
    final var bpmnProcessId = plainProcessIdsByScopedProcessId
        .get(new RegistryKey(workflowModuleId, processDefinitionKey));
    return bpmnProcessId == null
        ? Optional.empty()
        : Optional.of(new WorkflowProcess(workflowModuleId, bpmnProcessId));

  }

  /**
   * Resolves the connectable serving the given EL name - matching by the BPMN
   * element the expression is evaluated at, or by the task definition (the EL
   * name itself) - among the tasks of EVERY model wired under the process.
   * <p>
   * The process id is the SCOPED one, the key the engine reports, because that is what
   * {@link #register(Camunda7TaskConnectable)} stores under. Handing the plain id over
   * works as long as nothing is prefixed and answers nothing as soon as something is,
   * and nothing answers is the one outcome a caller cannot tell from "no handler here".
   * <p>
   * A caller holding an execution asks {@link #tasksOf(String, String, String, Supplier)}
   * instead: two versions of the process may wire different tasks to the same element, and
   * this lookup cannot tell them apart.
   *
   * @param workflowModuleId The workflow module (= tenant) ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @param currentElementId The BPMN element the expression evaluates at (may be
   *          <code>null</code>)
   * @param propertyName The top-level EL name
   * @return The connectable or empty (the EL name references an aggregate
   *         attribute instead)
   */
  public Optional<Camunda7TaskConnectable> resolve(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String currentElementId,
      final String propertyName) {

    return tasksOfEveryWiredModel(workflowModuleId, scopedBpmnProcessId).resolve(currentElementId, propertyName);

  }

  /**
   * Whether a connectable serves this EL name by NAME (its task definition), as
   * opposed to serving whatever is evaluated at its BPMN element. Asked among the tasks of
   * every model wired under the process, like {@link #resolve(String, String, String, String)}.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @param propertyName The EL name
   * @return Whether a connectable is named like this
   */
  public boolean isTaskDefinitionName(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String propertyName) {

    return tasksOfEveryWiredModel(workflowModuleId, scopedBpmnProcessId).isTaskDefinitionName(propertyName);

  }

  private TasksOfAModel tasksOfEveryWiredModel(
      final String workflowModuleId,
      final String scopedBpmnProcessId) {

    return new TasksOfAModel(
        connectables.getOrDefault(new RegistryKey(workflowModuleId, scopedBpmnProcessId), List.of()));

  }

  /**
   * Reads the tasks of the model one process definition carries, through the same extraction
   * the deployment runs over a model it brings.
   */
  public interface DefinitionReading {

    /**
     * The tasks of one process definition, as connectables.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The PLAIN BPMN process ID
     * @param scopedBpmnProcessId The process definition key the engine knows
     * @param processDefinitionId The engine's process definition id
     * @param model The model of that definition
     * @return Its connectables, empty where the model cannot be read
     */
    List<Camunda7TaskConnectable> connectablesOf(
        String workflowModuleId,
        String bpmnProcessId,
        String scopedBpmnProcessId,
        String processDefinitionId,
        BpmnModelInstance model);

  }

  /**
   * Who reads the model of a process definition. Handed over by the deployment service, which
   * owns the extraction. May be <code>null</code> (tests): every definition is then answered
   * with the tasks of every wired model.
   */
  private DefinitionReading definitionReading;

  /**
   * Hands over who reads the tasks of a process definition's model.
   *
   * @param definitionReading The extraction of the deployment service
   */
  public void setDefinitionReading(
      final DefinitionReading definitionReading) {

    this.definitionReading = definitionReading;

  }

  /**
   * The tasks of each process definition a workflow ran on, read once per definition. A
   * definition never changes, so the answer never goes stale, and the number of entries is the
   * number of versions in use.
   */
  private final Map<String, TasksOfAModel> tasksByDefinition = new ConcurrentHashMap<>();

  /**
   * The tasks of the process definition a workflow runs on, and nothing of any other version.
   * <p>
   * Camunda 7 evaluates the expressions of the model a workflow was STARTED with. An older
   * version may name another expression at the same element than the version this application
   * deploys, so a lookup over every model of the process finds the newer task by its element
   * and hands the workflow a method its own model never named. The model is read when a
   * workflow of that definition first asks, and kept for every later question, so a start
   * pays nothing for the versions the engine holds (see decision 40 in the repository's
   * DECISIONS.md).
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @param processDefinitionId The engine's process definition id of the workflow
   * @param model The model of that definition, read only on the first question about it
   * @return The tasks of that definition
   */
  public TasksOfAModel tasksOf(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String processDefinitionId,
      final Supplier<BpmnModelInstance> model) {

    final var bpmnProcessId = plainProcessIdsByScopedProcessId
        .get(new RegistryKey(workflowModuleId, scopedBpmnProcessId));
    if ((definitionReading == null) || (processDefinitionId == null) || (bpmnProcessId == null)) {
      // nothing to read the definition with, or a process this application never wired:
      // the second answers nothing either way, the first only happens in tests
      return tasksOfEveryWiredModel(workflowModuleId, scopedBpmnProcessId);
    }
    return tasksByDefinition
        .computeIfAbsent(
            processDefinitionId,
            definitionId -> new TasksOfAModel(
                List
                    .copyOf(definitionReading
                        .connectablesOf(
                            workflowModuleId, bpmnProcessId, scopedBpmnProcessId, definitionId, model.get()))));

  }

  /**
   * The tasks of one model, and the two questions an evaluated EL name asks about them.
   *
   * @param connectables The tasks of the model
   */
  public record TasksOfAModel(
                              List<Camunda7TaskConnectable> connectables) {

    /**
     * The connectable serving the given EL name, by NAME first: a name which IS a task
     * definition means that task, wherever it is evaluated. Only then by element, which
     * matches any name evaluated there.
     *
     * @param currentElementId The BPMN element the expression evaluates at (may be
     *          <code>null</code>)
     * @param propertyName The top-level EL name (may be <code>null</code>)
     * @return The connectable or empty
     */
    public Optional<Camunda7TaskConnectable> resolve(
        final String currentElementId,
        final String propertyName) {

      final var byName = connectables
          .stream()
          .filter(connectable -> connectable.appliesByName(propertyName))
          .findFirst();
      if (byName.isPresent()) {
        return byName;
      }
      return connectables
          .stream()
          .filter(connectable -> connectable.appliesByElement(currentElementId))
          .findFirst();

    }

    /**
     * Whether a task of this model is named like this, as opposed to merely sitting at the
     * element the name is evaluated at.
     *
     * @param propertyName The EL name
     * @return Whether a connectable is named like this
     */
    public boolean isTaskDefinitionName(
        final String propertyName) {

      return connectables
          .stream()
          .anyMatch(connectable -> connectable.appliesByName(propertyName));

    }

  }

}
