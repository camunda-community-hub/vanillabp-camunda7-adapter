package io.vanillabp.camunda7.wiring;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;

/**
 * The task connectables of ONE Camunda 7 engine, which is one adapter id. The deployment
 * service registers them during <code>wireBpmn</code>. The {@link Camunda7TaskELResolver}
 * looks them up whenever the engine evaluates a top-level EL name. They are keyed by
 * (workflow module ID, BPMN process ID), because one engine serves several workflow
 * modules. A caller which only has what the engine reports hands the tenant over and gets
 * the module back, see {@link #resolveWorkflowModuleId(String, String)}.
 * <p>
 * A running workflow asks for the tasks of its OWN process definition, see
 * {@link #tasksOf(String, String, String, Supplier)}. Two versions of a process may name
 * different expressions at the same element. Only the version a workflow runs on says
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
   * The versions of the engine's process definitions. The deployment service hands them
   * over, because it owns the engine's {@code RepositoryService}. Every listener which
   * builds an invocation context reaches them through this registry. May be
   * <code>null</code> (tests). Then no version is reported, and that matches every method.
   */
  private Camunda7ProcessVersions processVersions;

  /**
   * The id of the adapter which owns this engine. It is reported with every inbound
   * delivery, so VanillaBP can record which BPMS holds a workflow. May be
   * <code>null</code> (tests). Then nothing is recorded.
   */
  private String adapterId;

  /**
   * Hands over which adapter holds this engine. It arrives after construction. The
   * registry is built with the engine configuration, and the adapter id is not known there
   * yet.
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
   * The core's name-clash-avoidance model. The {@link Camunda7TaskELResolver} needs it to
   * translate the error code of a {@code TaskException} into what the engine knows. It
   * lives here for the same reason {@link #adapterId} does. The engine configuration builds
   * the resolver, so nothing which creates it can hand it anything. May be
   * <code>null</code> (tests). Then the plain identifiers apply.
   */
  private io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  /**
   * Hands over how an identifier is kept apart from the one of another workflow module. It
   * arrives after construction, for the same reason as the adapter id.
   *
   * @param scoping The core's name-clash-avoidance model
   */
  public void setScoping(
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping) {

    this.scoping = scoping;

  }

  /**
   * What translates between the identifiers the application wrote and the ones the engine
   * was given. Every path out of this registry needs it. That is why it is public.
   *
   * @return The core's name-clash-avoidance model or <code>null</code>
   */
  public io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport getScoping() {

    return scoping;

  }

  /**
   * Whether this engine was given a datasource of its own
   * (<code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>). Every listener which
   * builds an invocation context has to know this one thing about the engine. An engine on
   * the application's datasource delivers inside the transaction which saves the workflow
   * aggregate. An engine on its own datasource cannot. The platform integration sets it,
   * because it builds the engine. It is <code>false</code> in tests. That is the
   * shared-datasource answer this adapter had before an own datasource could be configured.
   */
  private boolean engineRunsOnItsOwnDataSource;

  /**
   * Tells the registry whether the engine shares the application's datasource. A delivery
   * of a shared engine commits together with the workflow aggregate. A delivery of a
   * separate engine does not. Every delivery path asks about this difference.
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
   * Hands over what the engine knows about its deployed versions. With it a delivery finds
   * the method which serves the version the delivery belongs to.
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
   * The deployed version behind a process definition id. The answer comes from the same
   * cache {@link #versionOfDefinition(String)} reads. So a caller asks the engine about that
   * definition only once.
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
   * aggregate. That is the core. It reads it from the declarations of the workflow
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
   * aggregate. If they do, a call between them continues one business case.
   * <p>
   * {@link Camunda7CallActivities} asks the same question while a model is prepared, and
   * it writes the answer into the model. A call activity which names the process to call
   * in an EXPRESSION leaves nothing to write it onto. So the start listener of the called
   * process asks again while the workflow runs.
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
   * process. It is the core's {@code WorkflowTaskWiring#isClaimedByAWorkflowService}. It is
   * <code>null</code> until the deployment service hands it over.
   */
  private java.util.function.BiPredicate<String, String> claimedProcesses;

  /**
   * Hands over who answers whether the application claims a BPMN process. That is the core.
   * The deployment service hands its answer over while the service is built, before anything
   * is deployed or parsed.
   *
   * @param claimedProcesses Answers for a workflow module and a PLAIN BPMN process id
   */
  public void setClaimedProcesses(
      final java.util.function.BiPredicate<String, String> claimedProcesses) {

    this.claimedProcesses = claimedProcesses;

  }

  /**
   * Whether a <code>&#64;WorkflowService</code> class of the application claims the BPMN
   * process. A process which is not claimed is deployed with its file, and it gets nothing
   * else from this adapter: no flag, no listener, no check. See decision 43 of
   * {@code DECISIONS.md}.
   *
   * @param workflowModuleId The workflow module id
   * @param bpmnProcessId The PLAIN BPMN process id
   * @return Whether the application claims the process. <code>true</code> where nobody handed
   *         over an answer, which is an engine built for a test without a deployment service
   */
  public boolean isClaimedByAWorkflowService(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return (claimedProcesses == null) || claimedProcesses.test(workflowModuleId, bpmnProcessId);

  }

  /**
   * Whether the application claims the process definition the engine reports. The engine holds
   * every definition deployed against its database. Some are claimed by this application. Some
   * it deploys without claiming them. And some somebody else deployed. Only the first kind is
   * claimed. A definition this adapter did not deploy has no workflow module to be found under.
   * So it is not claimed, whatever its key is.
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
   * Which workflow module a process definition key belongs to. It is the way back when
   * there is no tenant to ask (prefixed identifiers, see decision 3 in the repository's
   * DECISIONS.md). It holds only the modules which deploy without a tenant.
   */
  private final Map<String, String> workflowModuleIdsByScopedProcessId = new ConcurrentHashMap<>();

  /**
   * The plain BPMN process id per (workflow module, scoped process id). It is filled for
   * every wired process, so a process without tasks is found as well.
   */
  private final Map<RegistryKey, String> plainProcessIdsByScopedProcessId = new ConcurrentHashMap<>();

  /**
   * The PLAIN signal name per signal start event, keyed by (workflow module, scoped
   * process id, start event id). The engine's parser cannot resolve a signalRef. And the
   * application has to be told the name that was modelled.
   */
  private final Map<String, String> signalNamesOfStartEvents = new ConcurrentHashMap<>();

  /**
   * Which serialization format nested shared values are stored in. The platform
   * integration provides it, because it binds the configuration. The task path asks the
   * registry because it has the registry at hand. It is <code>null</code> in tests, where
   * the engine's default applies.
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
   * about a cancellation. It is read while the engine PARSES a model. That is why it is keyed
   * by what the engine reports there.
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
    // a second deployment of the same model registers the same listener again. Then the
    // engine would notify the one method twice
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

    // keyed by what the ENGINE reports at runtime, which is the scoped process id. It is
    // equal to the plain one, unless the module's identifiers are prefixed
    connectables
        .computeIfAbsent(
            new RegistryKey(connectable.workflowModuleId(), connectable.scopedBpmnProcessId()),
            key -> new CopyOnWriteArrayList<>())
        .add(connectable);
    rememberTheModuleOfAKeyWithoutATenant(connectable.workflowModuleId(), connectable.scopedBpmnProcessId());
    plainProcessIdsByScopedProcessId
        .putIfAbsent(
            new RegistryKey(connectable.workflowModuleId(), connectable.scopedBpmnProcessId()),
            connectable.bpmnProcessId());

  }

  /**
   * Registers what a process is called on both sides, without any task. A process which
   * the BPMS starts on its own (timer, signal or conditional start event) may have no tasks
   * at all. The start listener still has to find its way back from the engine's
   * process-definition key to the workflow module and the plain BPMN process id.
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
    rememberTheModuleOfAKeyWithoutATenant(workflowModuleId, scopedBpmnProcessId);

  }

  /**
   * Remembers which workflow module a process definition key belongs to, for an execution
   * which reports no tenant. Only a workflow module which deploys WITHOUT a tenant is
   * remembered. A definition without a tenant cannot belong to a module which deploys into
   * one. So somebody else deployed such a definition, even where its key is the key of a
   * process this application claims. The deployment registers the tenant of a module before
   * any of its processes. That is why the tenant is known here already.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   */
  private void rememberTheModuleOfAKeyWithoutATenant(
      final String workflowModuleId,
      final String scopedBpmnProcessId) {

    if (deploysIntoATenant(workflowModuleId)) {
      return;
    }
    workflowModuleIdsByScopedProcessId.putIfAbsent(scopedBpmnProcessId, workflowModuleId);

  }

  private boolean deploysIntoATenant(
      final String workflowModuleId) {

    return workflowModuleIdsByTenantId.containsValue(workflowModuleId);

  }

  /**
   * Which workflow module a Camunda TENANT belongs to. The two names are the same, unless
   * the application gave a workflow module a tenant name of its own.
   * {@link Camunda7ConfiguredTenant} lets it do that. Then the engine reports a name this
   * registry is not keyed by.
   */
  private final Map<String, String> workflowModuleIdsByTenantId = new ConcurrentHashMap<>();

  /**
   * Registers under which Camunda tenant a workflow module reaches the engine. So an
   * execution which reports that tenant leads back to the module. The deployment service
   * calls it while it wires a workflow module, with the same name the deployment uses.
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
   * the start event and not the signal. So the name is remembered while the model is wired,
   * and it is read back here.
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
   * The workflow module of a running execution. Camunda's tenant answers it whenever a
   * tenant keeps the module apart. With prefixed identifiers there is no tenant. Then the
   * module is looked up by the process definition key which the wiring registered. That is
   * a KNOWN value, never parsed out of the key. The lookup finds only a module which
   * deploys without a tenant. Take a definition without a tenant whose key is the key of a
   * module which deploys into one. Somebody else deployed it, and it gets no answer.
   * <p>
   * The tenant is not the module id where the application named the tenant itself. So it
   * is translated through what the deployment registered. Only a tenant nobody registered
   * is taken as the module id. That is a tenant of another application on the same engine.
   * Answering it unchanged keeps the old behaviour for everything which never configured a
   * name.
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
    final var workflowModuleId = workflowModuleIdsByScopedProcessId.get(processDefinitionKey);
    // a module whose tenant was registered after its processes still owns no definition
    // without a tenant
    return (workflowModuleId == null) || deploysIntoATenant(workflowModuleId)
        ? null
        : workflowModuleId;

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
   * The way back from what the engine reports to what the application wrote. A tenant and a
   * process definition key become the workflow module and the plain BPMN process id.
   *
   * <h4>When the answer is complete</h4>
   *
   * A process enters this registry while the deployment pipeline wires it, in
   * <code>wireBpmn</code>. That is before the engine parses the files of that workflow
   * module, and long before any of its workflows runs. A process which the engine only still
   * HOLDS under a declared id enters it while <code>startWorkflowProcessing</code> runs. That
   * is the last step of the pipeline. So the answer is complete for a workflow module once
   * <code>wireBpmn</code> ran for it. It is complete for the application once the pipeline
   * finished. Asking earlier means asking a registry which is still being filled. A second
   * registry, filled at another stage of the pipeline, gets exactly this wrong. The two see
   * a process at different moments, and the process is reported under the wrong module.
   *
   * <h4>Which name a caller hands over</h4>
   *
   * The one the ENGINE reports. An execution, a task or a process definition carries it.
   * That name is the workflow module id only as long as nobody configured a tenant name of
   * its own (<code>vanillabp.workflow-modules.&lt;id&gt;.adapters.&lt;adapter&gt;.tenant-id</code>
   * and the adapter-wide key next to it). Where somebody did, the engine reports the
   * configured name, and this registry translates it back. So a caller which has the module
   * id at hand and no tenant may pass the module id as well. The two are the same name for
   * every application which left the key alone. Where they differ, the translation answers
   * both. Passing anything else means asking about another application's tenant, and the
   * answer to that is empty.
   *
   * <h4>What an empty answer means</h4>
   *
   * That this application deployed no such process. An embedded engine may hold the
   * definitions of another application on the same database. A definition of a release which
   * this application no longer carries stays in the engine as well. Neither is an error, and
   * neither is guessed at. Nothing is parsed out of the key. A prefix which somebody cuts off
   * a string drifts apart from the one the adapter wrote (see decision 3 in the repository's
   * DECISIONS.md).
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
    // NOT plainBpmnProcessId(), on purpose. That one answers the key itself for a process
    // nobody registered. This is the right answer for a listener of a wired process. It is
    // the wrong one here, where we must be able to say "this application did not deploy it"
    final var bpmnProcessId = plainProcessIdsByScopedProcessId
        .get(new RegistryKey(workflowModuleId, processDefinitionKey));
    return bpmnProcessId == null
        ? Optional.empty()
        : Optional.of(new WorkflowProcess(workflowModuleId, bpmnProcessId));

  }

  /**
   * Resolves the connectable which serves the given EL name, among the tasks of EVERY model
   * wired under the process. It matches by the BPMN element the expression is evaluated at,
   * or by the task definition (the EL name itself).
   * <p>
   * The process id is the SCOPED one, the key the engine reports.
   * {@link #register(Camunda7TaskConnectable)} stores under that key. The plain id works as
   * long as nothing is prefixed. As soon as something is, it answers nothing. And a caller
   * cannot tell "nothing answers" from "no handler here".
   * <p>
   * A caller which holds an execution asks
   * {@link #tasksOf(String, String, String, Supplier)} instead. Two versions of the process
   * may wire different tasks to the same element, and this lookup cannot tell them apart.
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
   * Whether a connectable serves this EL name by NAME (its task definition), and not just
   * whatever is evaluated at its BPMN element. It asks among the tasks of every model wired
   * under the process, like {@link #resolve(String, String, String, String)}.
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
   * Reads the tasks of the model which one process definition carries. It uses the same
   * extraction the deployment runs over a model it brings.
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
   * Who reads the model of a process definition. The deployment service hands it over,
   * because it owns the extraction. May be <code>null</code> (tests). Then every definition
   * is answered with the tasks of every wired model.
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
   * definition never changes, so the answer never gets out of date. The number of entries is
   * the number of versions in use.
   */
  private final Map<String, TasksOfAModel> tasksByDefinition = new ConcurrentHashMap<>();

  /**
   * The tasks of the process definition a workflow runs on, and nothing of any other version.
   * <p>
   * Camunda 7 evaluates the expressions of the model a workflow was STARTED with. An older
   * version may name another expression at the same element than the version this
   * application deploys. Then a lookup over every model of the process finds the newer task
   * by its element. It hands the workflow a method which its own model never named. The model
   * is read when a workflow of that definition first asks, and it is kept for every later
   * question. So a start pays nothing for the versions the engine holds (see decision 40 in
   * the repository's DECISIONS.md).
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
      // nothing to read the definition with, or a process this application never wired.
      // The second answers nothing either way. The first only happens in tests
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
     * The connectable which serves the given EL name. It looks by NAME first. A name which IS
     * a task definition means that task, wherever it is evaluated. Only then it looks by
     * element, which matches any name evaluated there.
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
     * Whether a task of this model is named like this. Sitting at the element the name is
     * evaluated at is not enough.
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
