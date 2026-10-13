package io.vanillabp.camunda7.deployment;

import java.io.InputStream;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BusinessRuleTask;
import org.camunda.bpm.model.bpmn.instance.FlowElement;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.SendTask;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.Task;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.vanillabp.camunda7.Camunda7ProcessingContext;
import io.vanillabp.camunda7.engine.Camunda7InstanceIdentity;
import io.vanillabp.camunda7.wiring.Camunda7TaskConnectable;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.AddressedSystem;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.spi.parts.VanillaBpParts;
import lombok.extern.slf4j.Slf4j;

/**
 * Camunda 7 implementation of the VanillaBP adapter deployment SPI. One instance exists
 * per configured adapter id (not per adapter type).
 * <p>
 * The BPMN files of a workflow module are read into Camunda's own
 * {@link BpmnModelInstance} model and collected in a {@link Camunda7ProcessingContext}. At
 * the end they are deployed as one single Camunda deployment. Whether a module is isolated
 * by a Camunda TENANT named after it (version 1's behavior), by prefixed identifiers or not
 * at all is the name-clash-avoidance mode's decision, {@code by-adapter} (the tenant) being
 * this adapter's default. Duplicate filtering is on, so unchanged models are not deployed again on every
 * boot.
 * <p>
 * Task wiring ({@link #wireBpmn}) extracts the service-like tasks from the model. It
 * validates them against the registered {@code @WorkflowTask} methods, in both directions
 * and with guiding messages. Then it registers the connectables with the engine's EL
 * resolver. The engine then hands the model to the core's {@code WorkflowTaskWiring}.
 */
@Slf4j
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokSetterMayBeUsed")
public class Camunda7DeploymentService implements AdapterDeploymentService<BpmnModelInstance, Camunda7ProcessingContext> {

  /**
   * The adapter type of the Camunda 7 adapter. Several adapter ids of this type may be
   * configured, e.g. two Camunda 7 engines side by side during a migration.
   */
  public static final String ADAPTER_TYPE = io.vanillabp.camunda7.Camunda7Adapter.ADAPTER_TYPE;

  private final String adapterId;

  /**
   * The embedded engine's repository service, used to deploy BPMN resources. The platform
   * module (Spring Boot or Quarkus) provides it. That module wires the embedded engine. The
   * engine runs on the application's datasource, or on a datasource of its own where the
   * adapter id names one.
   */
  private final RepositoryService repositoryService;

  /**
   * Controls the engine's job executor. The executor is only started in
   * {@link #startWorkflowProcessing}. There is one executor for the whole engine. So the
   * platform's implementation counts the started modules. It stops the executor only when
   * the last module stops, see {@link Camunda7WorkflowProcessingLifecycle}.
   */
  private final Camunda7WorkflowProcessingLifecycle workflowProcessingLifecycle;

  /**
   * The core's task-processing entry point: wiring validation during
   * {@link #wireBpmn} and task dispatch at runtime (via the EL resolver).
   */
  private final WorkflowTaskWiring workflowTaskWiring;

  /**
   * The core's registry of <code>&#64;WorkflowTask</code> methods. It is asked one question
   * here: whether a method names the task definition which a modelled listener carries. This
   * decides whether the listener is this application's business at all.
   */
  private final io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * Everything the platform hands over. An adapter which is registered incompletely does
   * not come into existence (see {@link AdapterCollaborators}).
   */
  private final AdapterCollaborators collaborators;

  /**
   * The task connectables of this adapter id's engine, registered during
   * {@link #wireBpmn} and looked up by the engine's EL resolver.
   */
  private final Camunda7TaskRegistry taskRegistry;

  /**
   * The core's entry point for workflows which the engine starts on its own. The start
   * events of a process are reported here while wiring. May be <code>null</code> (tests).
   * Then nothing is reported.
   */
  private final io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker;

  /**
   * The core's registry of <code>&#64;WorkflowEnded</code> methods. It is used at
   * deployment to tell an application that its method will never be called. May be
   * <code>null</code> (tests). Then nothing is checked.
   */
  private final io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker workflowEndedInvoker;

  /**
   * Whether the engine of this adapter id attached the end listener, see
   * {@link #setEngineDeliversWorkflowEnded(boolean)}.
   */
  private boolean engineDeliversWorkflowEnded;

  /**
   * Says whether the engine of this adapter id attached the end listener. This is the half
   * of the end support which is this adapter's own business. The core's registry of
   * <code>&#64;WorkflowEnded</code> methods arrives with the collaborators.
   *
   * @param engineDeliversWorkflowEnded Whether the engine attached its end listener
   */
  public void setEngineDeliversWorkflowEnded(
      final boolean engineDeliversWorkflowEnded) {

    this.engineDeliversWorkflowEnded = engineDeliversWorkflowEnded;

  }

  /**
   * The core's name-clash-avoidance model. It decides how a workflow module is isolated: by
   * the Camunda TENANT ({@code by-adapter}, version 1's behavior), by PREFIXING the
   * identifiers ({@code use-prefix}, no tenant) or not at
   * all ({@code none}). {@code by-adapter} is this adapter's default. May be <code>null</code> (tests).
   */
  private final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  /**
   * The engine's identity service. It tells whether the tenant deployed into is
   * REGISTERED there (see {@link Camunda7TenantCheck}). May be <code>null</code>, in tests
   * or if a platform does not hand it over. Then the check is skipped.
   */
  private org.camunda.bpm.engine.IdentityService identityService;

  /**
   * The property keys whose tenant was already checked against the mode. It is a key, not
   * a flag. The name may come from the adapter's section or from a workflow module's. The
   * message has to quote the one which is set.
   */
  private final java.util.Set<String> tenantKeysCheckedAgainstTheMode = new java.util.HashSet<>();

  /**
   * Whether the application accepted unscoped identifiers on purpose
   * (<code>vanillabp.adapters.&lt;id&gt;.accept-unscoped-identifiers</code>). This
   * silences {@link #warnAboutUnscopedIdentifiers(String, boolean)}.
   */
  private boolean acceptUnscopedIdentifiers;

  /**
   * Sets the acknowledgement that identifiers are unique across workflow modules (the
   * platform modules read it from the adapter's configuration).
   *
   * @param acceptUnscopedIdentifiers Whether unscoped identifiers are accepted
   */
  public void setAcceptUnscopedIdentifiers(
      final boolean acceptUnscopedIdentifiers) {

    this.acceptUnscopedIdentifiers = acceptUnscopedIdentifiers;

  }

  /**
   * What a workflow module's tenant is CONFIGURED as. The platform modules resolve it over
   * the levels where the name may be set (the workflow module, then the adapter). It is
   * <code>null</code> for a module which no setting names a tenant for. Then the workflow
   * module ID names it (VanillaBP 1's behavior). May be <code>null</code> itself (tests).
   */
  private java.util.function.Function<String, io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant> configuredTenants;

  /**
   * Sets the tenant names the application configured (the platform modules read them from
   * the configuration, per workflow module).
   *
   * @param configuredTenants What a workflow module's tenant is configured as
   */
  public void setConfiguredTenants(
      final java.util.function.Function<String, io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant> configuredTenants) {

    this.configuredTenants = configuredTenants;

  }

  /**
   * What the application configured as the tenant of one workflow module, with the key it
   * wrote it under, or <code>null</code>.
   */
  private io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant configuredTenantOf(
      final String workflowModuleId) {

    return configuredTenants != null
        ? configuredTenants.apply(workflowModuleId)
        : null;

  }

  /**
   * Sets the engine's identity service (the platform modules take it from this adapter
   * id's engine).
   *
   * @param identityService The identity service or <code>null</code>
   */
  public void setIdentityService(
      final org.camunda.bpm.engine.IdentityService identityService) {

    this.identityService = identityService;

  }

  /**
   * The BPMN process id as the ENGINE knows it (prefixed when the module's mode is
   * {@code use-prefix}).
   */
  private String scopedProcessId(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return scoping == null
        ? bpmnProcessId
        : scoping.scopedProcessId(workflowModuleId, bpmnProcessId, adapterId);

  }

  /**
   * What this engine holds for a BPMN process which this application declares without
   * deploying a model under it. This is the old id of a renamed process. The engine keeps it
   * with every version ever deployed under it and with the workflows still running on them.
   * <p>
   * It is the same catalog which every deployed process of this adapter is registered with.
   * It queries the definitions by the process key as the ENGINE knows it. So a prefix and a
   * tenant reach the old id like any other.
   */
  @Override
  public io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog processVersionCatalogOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    // being asked about a declared id is the FIRST thing which happens to it. Whatever
    // reads the catalog next makes the engine PARSE the definitions the id still has.
    // At that moment the parse listener uses exactly this registration to decide
    // whether the end of such a workflow is reported. Registering any later loses the
    // end listener for good, because a parsed definition stays cached (measured by
    // Camunda7DeclaredIdRuntimeIT). May be null in tests
    if (taskRegistry != null) {
      registerTheWayBackFromTheEngine(
          workflowModuleId, bpmnProcessId, scopedProcessId(workflowModuleId, bpmnProcessId));
    }
    return processVersions;

  }

  /**
   * Fails the boot if a tenant is configured although no workflow module is deployed into
   * one, i.e. the mode says {@code none} or {@code use-prefix} everywhere. This adapter knows
   * that only {@code by-adapter} can use a tenant. The core answers which modes apply. The
   * check runs while deploying, before anything reaches the engine. It runs once per
   * property key which set a name. The adapter's section and a workflow module's section
   * are two different lines for the developer to go to.
   *
   * @param workflowModuleId The workflow module being deployed
   */
  private void validateTenantConfiguration(
      final String workflowModuleId) {

    if (scoping == null) {
      return;
    }
    final var configured = configuredTenantOf(workflowModuleId);
    if ((configured == null) || !tenantKeysCheckedAgainstTheMode.add(configured.propertyKey())) {
      return;
    }
    scoping.validateNoneNameClashStrategy(adapterId, configured.propertyKey());

  }

  /**
   * The Camunda tenant a workflow module is deployed to. Under {@code by-adapter} it is the
   * configured name, or the module id if nothing configured one. Under
   * {@code use-prefix}/{@code none} there is none.
   */
  private String tenantIdOf(
      final String workflowModuleId) {

    final var configured = configuredTenantOf(workflowModuleId);
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
   * The way back from what the engine reports to what the application wrote, for one BPMN
   * process and for the workflow module it belongs to.
   * <p>
   * Both are registered together, because both are read together. An execution names a
   * tenant and a process definition key. The core is keyed by neither of them, as soon as
   * the application gave the workflow module a tenant name of its own. Without the tenant,
   * the registry would answer that name as the workflow module. Then every listener of such
   * a module would look for its process under a module which nobody registered.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   */
  private void registerTheWayBackFromTheEngine(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId) {

    taskRegistry.registerTenant(tenantIdOf(workflowModuleId), workflowModuleId);
    taskRegistry.registerProcess(workflowModuleId, bpmnProcessId, scopedBpmnProcessId);

  }

  /**
   * Whether Camunda 7's own isolation would keep the two given workflow modules apart. On
   * this engine this means one question: would they be deployed into two different TENANTS?
   * The core asks this in the mode which leaves the keys plain and relies on the BPMS
   * instead. It then checks whether two BPMN processes of this application reach the engine
   * under one process definition key.
   * <p>
   * The tenant of each module is resolved the way {@link #deployResources} resolves the one it
   * deploys under. So the answer is about the scope which this adapter would REALLY use, not
   * about a property read on its own. The name may come from the module's own section or from
   * the adapter's, and the mode may drop it altogether. An adapter-wide name makes the two
   * sides equal. The check exists for exactly this configuration.
   * <p>
   * A workflow module whose mode uses no tenant reaches the engine WITHOUT one. Here, no
   * tenant is a scope like any other. Two such modules share every process definition key
   * which they both declare. One of them shares no key with a module which has a tenant.
   */
  @Override
  public boolean ownIsolationSeparatesWorkflowModules(
      final String oneWorkflowModuleId,
      final String anotherWorkflowModuleId) {

    return !java.util.Objects
        .equals(
            tenantIdOf(oneWorkflowModuleId),
            tenantIdOf(anotherWorkflowModuleId));

  }

  /**
   * The namespace of Camunda's BPMN extension attributes. It is kept as ONE constant, and
   * the attributes are read through the namespace ({@code getAttributeValueNs}). This keeps
   * the code portable to forks. Operaton and CIB seven renamed the typed extension getters,
   * but both accept the attribute namespace.
   */
  public static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";

  private static final Pattern EL_PATTERN = Pattern.compile("^[#$]\\{([^}]+)}$");

  /**
   * Resolves what makes an adapter id a DISTINCT engine (datasource and table prefix). The
   * platform supplies it, and {@link #validateDistinctAdapterInstances(List)} uses it. May
   * be <code>null</code> (tests). Then the check is skipped.
   */
  private final java.util.function.Function<String, Camunda7InstanceIdentity> instanceIdentities;

  /**
   * Convenience constructor without the instance-identity resolver (tests). Two adapter ids
   * of this type are then not checked for being distinct.
   *
   * @param adapterId The configured adapter id this service deploys for
   * @param repositoryService The engine's repository, where the resources are deployed
   * @param workflowProcessingLifecycle What starts and stops the job executor of this engine
   * @param collaborators Everything the platform hands over, wiring and invoker included
   * @param taskRegistry What the engine's listeners and the EL resolver look a task up in
   */
  public Camunda7DeploymentService(
      final String adapterId,
      final RepositoryService repositoryService,
      final Camunda7WorkflowProcessingLifecycle workflowProcessingLifecycle,
      final AdapterCollaborators collaborators,
      final Camunda7TaskRegistry taskRegistry) {

    this(adapterId, repositoryService, workflowProcessingLifecycle, collaborators, taskRegistry, null);

  }

  /**
   * The constructor a platform integration uses. Two embedded engines on one schema share
   * one engine state. The identity resolver lets the boot refuse that, before anybody
   * deploys anything.
   *
   * @param adapterId The configured adapter id this service deploys for
   * @param repositoryService The engine's repository, where the resources are deployed
   * @param workflowProcessingLifecycle What starts and stops the job executor of this engine
   * @param collaborators Everything the platform hands over, wiring and invoker included
   * @param taskRegistry What the engine's listeners and the EL resolver look a task up in
   * @param instanceIdentities Where an adapter id's datasource and table prefix are read,
   *          so two ids on the same one end the boot
   */
  public Camunda7DeploymentService(
      final String adapterId,
      final RepositoryService repositoryService,
      final Camunda7WorkflowProcessingLifecycle workflowProcessingLifecycle,
      final AdapterCollaborators collaborators,
      final Camunda7TaskRegistry taskRegistry,
      final java.util.function.Function<String, Camunda7InstanceIdentity> instanceIdentities) {

    VanillaBpParts.requireAdapterFitsPlatform(ADAPTER_TYPE, Camunda7DeploymentService.class);

    this.adapterId = adapterId;
    this.repositoryService = repositoryService;
    this.workflowProcessingLifecycle = workflowProcessingLifecycle;
    this.collaborators = collaborators;
    this.workflowTaskWiring = collaborators.workflowTaskWiring();
    this.workflowTaskInvoker = collaborators.workflowTaskInvoker();
    this.bpmsInitiatedStartInvoker = collaborators.bpmsInitiatedStartInvoker().orElse(null);
    this.workflowEndedInvoker = collaborators.workflowEndedInvoker().orElse(null);
    this.scoping = collaborators.scoping();
    this.taskRegistry = taskRegistry;
    this.instanceIdentities = instanceIdentities;
    // What the engine's process definitions are versioned as. The registry hands it
    // to every listener which builds an invocation context
    this.processVersions = new io.vanillabp.camunda7.wiring.Camunda7ProcessVersions(
        adapterId, repositoryService, this::scopedProcessId, this::tenantIdOf, new HeldModels());
    // the emergency exit past the old-versions check is meant to be the decision of THIS
    // start. So every start says out loud that the exit was taken
    io.vanillabp.camunda7.wiring.SuspendedProcessDefinitions.reportIfTheSwitchIsSet(adapterId);
    if (taskRegistry != null) {
      taskRegistry.setProcessVersions(processVersions);
      // A call activity naming the process to call in an expression cannot be given the
      // business key while its model is deployed, because nobody knows then which process
      // is called. So the start listener of the called process asks the core the same
      // question which Camunda7CallActivities asks here. It asks through the registry
      taskRegistry.setWorkflowAggregateSharing(workflowTaskWiring::workflowsShareTheWorkflowAggregate);
      // the parse listener runs for every model the engine parses. Only a process which the
      // application claims gets anything from it (see decision 43 of DECISIONS.md)
      taskRegistry.setClaimedProcesses(workflowTaskWiring::isClaimedByAWorkflowService);
      // every inbound delivery reports which adapter it came from
      taskRegistry.setAdapterId(adapterId);
      // the EL resolver builds the task behavior. It needs both to raise a BPMN error which
      // the deployed model still carries. Nothing can be handed to the resolver itself
      taskRegistry.setScoping(scoping);
      // a running workflow is served from the model of its own version. The registry reads
      // that model through the same extraction which a deployed model goes through
      taskRegistry.setDefinitionReading(this::connectablesOfDefinition);
    }

  }

  /**
   * The tasks of the model which one process definition carries. They are read when a
   * workflow of that definition first asks for one of them. Camunda 7 evaluates the
   * expressions of the model a workflow was started with. So the version a workflow runs on
   * decides which method an expression means, not the version this boot deploys (see
   * decision 40 in the repository's DECISIONS.md).
   * <p>
   * A model which the extraction refuses gets no task at all. The same is true for a version
   * the engine holds under a declared id. Its workflows then end in an incident at their next
   * task, and the incident names the expression. This lookup exists to prevent that they are
   * served from another version instead.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @param processDefinitionId The engine's process definition id
   * @param model The model of that definition
   * @return The connectables of that model, empty where it cannot be read
   */
  private List<Camunda7TaskConnectable> connectablesOfDefinition(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final String processDefinitionId,
      final BpmnModelInstance model) {

    final var connectables = new LinkedList<Camunda7TaskConnectable>();
    try {
      collectTasks(
          model,
          workflowModuleId,
          bpmnProcessId,
          scopedBpmnProcessId,
          "process definition '%s'".formatted(processDefinitionId),
          new LinkedList<>(),
          connectables,
          null);
    } catch (final RuntimeException e) {
      log.warn(
          """
              Camunda7[{}]: the model of process definition '{}' of BPMN process '{}' (workflow \
              module '{}') cannot be wired, so no task of it is served. A workflow running on that \
              definition ends in an incident at its next task. Either deploy that model again \
              with the problem fixed, or complete those workflows by other means.""",
          adapterId,
          processDefinitionId,
          bpmnProcessId,
          workflowModuleId,
          e);
      return List.of();
    }
    return connectables;

  }

  /**
   * The versions of this engine's process definitions. Every task, start and end reports
   * its version from here. The core also resolves version TAGS through this catalog.
   */
  private final io.vanillabp.camunda7.wiring.Camunda7ProcessVersions processVersions;

  /**
   * Two <code>camunda7</code> adapter ids are only distinct engines if they run on
   * different databases. This means an own datasource, or an own table prefix on a shared
   * one (see {@link Camunda7InstanceIdentity}).
   */
  @Override
  public void validateDistinctAdapterInstances(
      final List<String> adapterIdsOfThisType) {

    Camunda7InstanceIdentity.validateDistinct(adapterIdsOfThisType, instanceIdentities);

  }

  /**
   * The tables this engine works on: datasource and table prefix, compared exactly as
   * {@link #validateDistinctAdapterInstances(List)} compares two <code>camunda7</code> ids.
   * The core compares it with the ids of other adapter types, so that a Camunda 7 engine and
   * an engine of a fork never share one set of tables (see {@link Camunda7InstanceIdentity}).
   */
  @Override
  public Optional<AddressedSystem> getAddressedSystem() {

    if (instanceIdentities == null) {
      return Optional.empty();
    }
    return Optional
        .ofNullable(instanceIdentities.apply(adapterId))
        .map(Camunda7InstanceIdentity::addressedSystem);

  }

  /**
   * Camunda 7 keeps the SPI's default, {@code by-adapter}. On this engine this means a
   * tenant named after the workflow module. This is exactly what VanillaBP 1 deployed when
   * nothing was configured. So an application which upgrades from version 1 without
   * touching its configuration finds the workflows it started back then. That is the whole
   * reason why the adapter does not make this decision for itself.
   * <p>
   * It costs nothing on this engine. A tenant id is an attribute of the deployment, so the
   * engine accepts any name and creates nothing (see {@link Camunda7TenantCheck}). The
   * alternatives are named where they matter.
   * {@link #warnAboutUnscopedIdentifiers(String, boolean)} names them once a module runs
   * unscoped. The wiki names them for an application which would rather prefix its
   * identifiers or give a module an engine of its own.
   * <p>
   * {@code Camunda7DeploymentServiceTest} holds this. Between 2026-08-11 and 2026-08-22 it
   * asserted {@code none}, and that was the defect.
   */
  @Override
  public io.vanillabp.integration.adapter.spi.NameClashAvoidance defaultNameClashAvoidance() {

    return io.vanillabp.integration.adapter.spi.NameClashAvoidance.BY_ADAPTER;

  }

  /**
   * Names what Camunda 7 offers instead of {@code none}: a tenant per workflow module
   * (the engine is multi-tenant out of the box), prefixing, or an engine per workflow
   * module. An engine per module means an own datasource, or an own table prefix on a
   * shared one.
   * <p>
   * Silent if the application accepted unscoped identifiers on purpose
   * ({@code vanillabp.adapters.<id>.accept-unscoped-identifiers}). The point of the
   * warning is the DECISION. Once it is on record, there is nothing left to ask.
   */
  @Override
  public void warnAboutUnscopedIdentifiers(
      final String workflowModuleId,
      final boolean fromDefault) {

    if (acceptUnscopedIdentifiers) {
      log.debug(
          "Camunda7[{}]: workflow module '{}' is deployed with name-clash-avoidance 'none', accepted by "
              + "'vanillabp.adapters.{}.accept-unscoped-identifiers'",
          adapterId,
          workflowModuleId,
          adapterId);
      return;
    }
    log.warn(
        """
            Workflow module '{}' is deployed to Camunda 7 (adapter '{}') with name-clash-avoidance \
            'none'{}. Its identifiers reach the engine as they are - BPMN process ids, message and \
            signal names, error codes and task definitions - so a second workflow module using the \
            same identifier addresses the very same process definitions and tasks, and neither \
            VanillaBP nor the engine can tell. Keep 'none' only as long as your identifiers are \
            unique across ALL workflow modules of this application. Otherwise choose:
              vanillabp.adapters.{}.name-clash-avoidance: by-adapter   # a tenant per workflow module, Camunda 7's own isolation
              vanillabp.adapters.{}.name-clash-avoidance: use-prefix   # VanillaBP prefixes the identifiers, no tenant needed
            A third option is an engine per workflow module, configured as one adapter id per engine \
            with its own database ('vanillabp.adapters.<id>.data-source-name') respectively its own \
            tables in a shared one ('vanillabp.adapters.<id>.table-prefix'). The same key may be set \
            per workflow module (vanillabp.workflow-modules.{}.adapters.{}.name-clash-avoidance). The \
            mode is not a runtime switch - changing it once workflows are running is a BPMS \
            migration. If the identifiers ARE unique, say so once and this warning is gone:
              vanillabp.adapters.{}.accept-unscoped-identifiers: true""",
        workflowModuleId,
        adapterId,
        fromDefault
            ? " (nothing is configured, so the adapter's default applies)"
            : "",
        adapterId,
        adapterId,
        workflowModuleId,
        adapterId,
        adapterId);

  }

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  @Override
  public String getAdapterType() {

    return ADAPTER_TYPE;

  }

  @Override
  public Class<BpmnModelInstance> getModelType() {

    return BpmnModelInstance.class;

  }

  @Override
  public Class<Camunda7ProcessingContext> getProcessContextType() {

    return Camunda7ProcessingContext.class;

  }

  @Override
  public List<Map.Entry<String, BpmnModelInstance>> readBpmn(
      final String workflowModuleId,
      final String filename,
      final InputStream bpmn,
      final boolean isVanillaBpBpmn) throws BpmnParseException {

    final BpmnModelInstance model;
    try {
      model = Bpmn.readModelFromStream(bpmn);
    } catch (final RuntimeException e) {
      throw new BpmnParseException(
          "Failed to parse BPMN file '%s' of workflow module '%s'!".formatted(filename, workflowModuleId), e);
    }

    // one entry per executable process. All entries share the model of the file, because
    // the whole file is deployed once (see Camunda7ProcessingContext#addResource)
    return model
        .getModelElementsByType(Process.class)
        .stream()
        .filter(Process::isExecutable)
        .map(process -> (Map.Entry<String, BpmnModelInstance>) new SimpleImmutableEntry<>(process.getId(), model))
        .toList();

  }

  @Override
  public Camunda7ProcessingContext prepareBpmn(
      final String workflowModuleId,
      final Camunda7ProcessingContext existingContext,
      final String filename,
      final String bpmnProcessId,
      final BpmnModelInstance model) {

    // the core passes null for the first BPMN of a workflow module
    final var context = existingContext != null
        ? existingContext
        : new Camunda7ProcessingContext(workflowModuleId);
    // Rewrite the identifiers which the engine resolves across process definitions,
    // BEFORE wiring. This does nothing unless the mode is 'use-prefix'. The core calls
    // prepareBpmn once per executable PROCESS, but all processes of a file share ONE
    // model. So scoping has to happen once per FILE. Otherwise a multi-process file
    // would collect one prefix per process.
    final var modelAlreadyScoped = context
        .getResourcesByFilename()
        .containsKey(filename);
    if (!modelAlreadyScoped) {
      // A call activity of this engine does not pass the business key unless the model
      // says so. The business key holds the workflow aggregate's ID. And what the core
      // answers about the aggregate is needed again while a workflow runs. Both are done
      // BEFORE scoping, because scoping rewrites the called elements. Here the process IDs
      // are still the ones the application knows
      io.vanillabp.camunda7.wiring.Camunda7CallActivities
          .prepareCallActivities(model, workflowModuleId, workflowTaskWiring);
      // the names which the engine resolves across process definitions. They are read
      // while they are still the ones the application modelled. Scoping rewrites exactly
      // these. So the answer to "which of them does this module declare" is free right here
      context
          .recordDeclaredIdentifiers(
              io.vanillabp.camunda7.wiring.Camunda7Scoping
                  .identifiersDeclaredBy(model, java.util.function.UnaryOperator.identity()));
      io.vanillabp.camunda7.wiring.Camunda7Scoping.apply(model, workflowModuleId, adapterId, scoping);
    }
    context.addResource(filename, model);
    context.recordDeployedProcess(bpmnProcessId);
    return context;

  }

  @Override
  public Camunda7ProcessingContext readDmn(
      final String workflowModuleId,
      final Camunda7ProcessingContext existingContext,
      final String filename,
      final java.io.InputStream dmn) {

    // the decision travels as bytes. The engine reads it. This adapter only has to make
    // sure that the id it is deployed under matches what the business rule task points at
    final var file = io.vanillabp.integration.adapter.spi.DmnDecisionIds.bytesOf(dmn);
    final var prefixes = io.vanillabp.camunda7.wiring.Camunda7Scoping
        .prefixes(workflowModuleId, adapterId, scoping);
    final var toDeploy = prefixes
        ? io.vanillabp.integration.adapter.spi.DmnDecisionIds
            .rewrite(file, id -> scoping.scopedIdentifier(workflowModuleId, id, adapterId))
        : file;
    if (prefixes) {
      log.debug(
          "Camunda7[{}]: the decisions of '{}' are deployed under prefixed ids ({}), matching the "
              + "'camunda:decisionRef' of the business rule tasks calling them",
          adapterId,
          filename,
          io.vanillabp.integration.adapter.spi.DmnDecisionIds.of(toDeploy));
    }
    // the core reads the DMN files after the BPMN files, and only for a module whose BPMN
    // files produced a context. So the context is never null here
    existingContext.addDecision(filename, toDeploy);
    // the ids as the application knows them. The engine is asked about these, and another
    // workflow module may declare them as well
    existingContext
        .recordDecisionIds(io.vanillabp.integration.adapter.spi.DmnDecisionIds.of(file));
    return existingContext;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final BpmnModelInstance model,
      final Camunda7ProcessingContext context) {

    // extract the service-like tasks of THIS process from the model. VanillaBP's Camunda 7
    // convention wires tasks by 'camunda:expression' (the handler runs while the
    // expression evaluates) or by 'camunda:delegateExpression' (@TaskId tasks can stay
    // open). The unwrapped expression text is the task definition
    final var specs = new LinkedList<BpmnTaskSpec>();
    final var connectables = new LinkedList<Camunda7TaskConnectable>();
    // the model carries the identifiers the ENGINE will know (prepareBpmn rewrote them).
    // But the core is keyed by the plain ones. So the model is searched by the scoped id,
    // and the invoker is called with the plain one
    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);

    // the way back from the engine's process-definition key is registered before anything
    // else. This is done for a claimed process and for one which nobody claims. The engine
    // may parse the model as soon as it is deployed. The parse listener then has to tell
    // the two apart
    registerTheWayBackFromTheEngine(workflowModuleId, bpmnProcessId, scopedBpmnProcessId);

    // a process which nobody claims travels with its file and stays as it was modelled. No
    // task is collected, no check refuses it, no listener is wired to it, and the parse
    // listener does not touch it (see decision 43 of DECISIONS.md). The core already ended
    // the start over it, unless the application marked it as somebody else's
    if (!workflowTaskWiring.isClaimedByAWorkflowService(workflowModuleId, bpmnProcessId)) {
      log.debug(
          "Camunda7[{}]: BPMN process '{}' of file '{}' (workflow module '{}') is claimed by no "
              + "@WorkflowService and is deployed as it was modelled",
          adapterId,
          bpmnProcessId,
          filename,
          workflowModuleId);
      return;
    }

    // The engine runs an activity with a standard loop once and says nothing. This is asked
    // first. No other finding about this model matters while it does not do what was drawn
    final var standardLoops = Camunda7StandardLoops.elementIdsOf(model, scopedBpmnProcessId);
    if (!standardLoops.isEmpty()) {
      throw new IllegalStateException(
          Camunda7StandardLoops.refusal(standardLoops, bpmnProcessId, workflowModuleId));
    }
    processesWiredByModule
        .computeIfAbsent(workflowModuleId, id -> java.util.concurrent.ConcurrentHashMap.newKeySet())
        .add(bpmnProcessId);

    collectTasks(
        model,
        workflowModuleId,
        bpmnProcessId,
        scopedBpmnProcessId,
        "file '%s'".formatted(filename),
        specs,
        connectables,
        context);

    // both directions, with guiding messages. Throwing here follows the
    // deployment-failure policy for adapter ids which are not first in priority
    workflowTaskWiring.validateTaskWiring(adapterId, workflowModuleId, bpmnProcessId, specs);

    // What follows judges the model which this boot brings. A check whose finding a
    // modeller can still act on belongs here and nowhere else. Two examples are an
    // asynchronous task wired by expression, and an expression which reads what the
    // aggregate does not share. Both can be changed and deployed again. The findings which
    // outlive a deployment are asked of the version catalog instead, over the models the
    // engine holds. A workflow started years ago runs into them just the same, and nobody
    // can go back and change the model it is on.

    // A task wired by 'camunda:expression' completes as soon as the expression returns.
    // So a method which declares @TaskId can never keep it open. The engine's EL resolver
    // says the same at runtime, but only once a workflow reaches the task. Asking the
    // core here moves the verdict to the boot. The reverse case needs no message.
    // 'camunda:delegateExpression' serves a method without @TaskId just as well: the
    // behavior leaves the activity when the handler returns.
    connectables
        .stream()
        .filter(connectable -> connectable.type() == Camunda7TaskConnectable.Type.EXPRESSION)
        .filter(connectable -> aMethodOfTheTaskWantsToKeepItOpen(workflowModuleId, bpmnProcessId, connectable))
        .findFirst()
        .ifPresent(connectable -> {
          throw new IllegalStateException(
              Camunda7TaskConnectable.asynchronousTaskWiredByExpression(
                  connectable.taskDefinition(),
                  bpmnProcessId,
                  workflowModuleId));
        });

    // a listener is notified and done. The engine is inside a transition of its own while the
    // listener runs. So a method which declares @TaskId would wait for a completion which
    // nobody can send. Version 1 accepted such a method, and the workflow went on without it.
    // Unlike the task above, this is asked by the task definition ALONE. A method serves a
    // listener only by naming its task definition. An element may carry a task and a listener
    // at once, and the element id cannot say which of them a method means. So the element id
    // would answer for the task's method here and refuse a model which is right
    connectables
        .stream()
        .filter(Camunda7TaskConnectable::isExecutionListener)
        .filter(connectable -> workflowTaskWiring.workflowTaskCompletesAsynchronously(
            workflowModuleId,
            bpmnProcessId,
            connectable.taskDefinition()))
        .findFirst()
        .ifPresent(connectable -> {
          throw new IllegalStateException(
              """
                  The @WorkflowTask method serving the listener '%s' of BPMN process '%s' (workflow \
                  module '%s') declares a @TaskId parameter! A listener is notified and done, so such \
                  a task can never stay open and the id would complete nothing. Drop the parameter, or \
                  model the work as a task of the process where it has to stay open."""
                  .formatted(connectable.taskDefinition(), bpmnProcessId, workflowModuleId));
        });

    // A bean or a shared attribute which has the name of a task definition answers the
    // expression before this adapter is asked. The method would never run, and the
    // workflow would not say why. So the start ends here, before any workflow can run
    // into it (see decision 46 in the repository's DECISIONS.md)
    refuseNamesWhichHideATask(workflowModuleId, bpmnProcessId, connectables);

    // A handler reads the item of an iteration out of the variable the model names in
    // 'camunda:elementVariable'. An element which names none hands no item over. So the
    // parameter would receive null once a workflow reaches the task, and nothing would say
    // why. Only this adapter reads the model, and only the core scans the handlers. So
    // this is the one place where the two halves meet
    refuseHandlersWantingAnItemTheModelHasNot(
        workflowModuleId, bpmnProcessId, model, connectables);

    connectables.forEach(taskRegistry::register);

    // The engine can be asked which versions of this process it has. A version
    // specification which names a version TAG needs this
    workflowTaskWiring
        .registerProcessVersions(adapterId, workflowModuleId, bpmnProcessId, processVersions);

    // Which elements can put a second token into a running workflow. Two tokens are two
    // writers on the workflow aggregate. The core knows whether that aggregate can
    // survive them
    workflowTaskWiring
        .reportConcurrentTokenElements(
            workflowModuleId,
            bpmnProcessId,
            Camunda7ConcurrentTokens.elementIdsOf(model, scopedBpmnProcessId));

    // compensation is the same second token, drawn in another way. It needs its shape. A
    // throw event which compensates two finished activities starts both handlers. The
    // developer has to read WHICH event starts WHICH handlers
    final var compensation = Camunda7ConcurrentTokens.compensationOf(model, scopedBpmnProcessId);
    workflowTaskWiring
        .reportCompensation(
            workflowModuleId,
            bpmnProcessId,
            compensation);

    // and all those handlers run in ONE transaction. This is the one promise of decision 5
    // which this engine does not keep
    warnAboutCompensationSharingOneTransaction(workflowModuleId, bpmnProcessId, compensation);

    // What the expressions of this model read. Two checks ask the core about it. The
    // paths are collected once, because both questions are about the same paths
    final var expressionOrigins = io.vanillabp.camunda7.sync.Camunda7ExpressionIdentifiers
        .of(model, scopedBpmnProcessId);

    // An expression which reads an attribute the aggregate does not share evaluates to
    // null. Camunda 7 then takes the default flow without saying a word. The adapter knows
    // the model, and the core knows what is shared. Together they can say it while the
    // application starts
    warnAboutUnsharedAggregatePaths(workflowModuleId, bpmnProcessId, expressionOrigins);

    // And a value which IS shared may still reach the expression as something other than
    // what the application holds. A value which the engine has no type for travels through
    // the configured serialization format
    warnAboutTypesTheFormatCannotCarry(workflowModuleId, bpmnProcessId, expressionOrigins);

    // The third question about the same expressions. It is the only one whose answer must
    // not depend on the BPMS: what does an expression which reads more than the name of
    // one variable cost the application next year? So the adapter reports what it read,
    // and the core judges it. The expressions go over as the model has them, not as the
    // paths above. A keyword and a function call are part of what the core judges, and
    // the path collection drops both
    workflowTaskWiring
        .reportModelExpressions(
            workflowModuleId,
            bpmnProcessId,
            io.vanillabp.camunda7.sync.Camunda7ExpressionIdentifiers
                .expressionsOf(model, scopedBpmnProcessId));

    // This engine reports the end of a workflow. So a @WorkflowEnded method which stays
    // silent means that the adapter was not wired. This used to be invisible: the
    // application booted, the workflow ran, the method was never called and nothing was
    // logged. The same is asked for a declared id in wireTheVersionsHeldUnder, where no
    // model of this boot passes by
    warnAboutUnservedWorkflowEndedHandlers(workflowModuleId, bpmnProcessId);

    // Everything VanillaBP scopes is scoped per workflow module, a BPMN error code too. A
    // call activity which sends this engine into another tenant calls a process of another
    // module. The error which that process raises then carries the other module's prefix.
    // But the boundary event here waits for this module's prefix
    warnAboutCallActivitiesLeavingTheWorkflowModule(workflowModuleId, bpmnProcessId, scopedBpmnProcessId, model);

    wireBpmsInitiatedStarts(workflowModuleId, bpmnProcessId, scopedBpmnProcessId, model);

    log.info(
        "Camunda7[{}]: wired {} task(s) of BPMN process '{}' (file '{}', workflow module '{}')",
        adapterId,
        connectables.size(),
        bpmnProcessId,
        filename,
        workflowModuleId);

  }

  /**
   * The class of the bean the engine resolves a name to, before it asks this adapter. Only
   * the Spring Boot module has such beans. The engine there resolves every bean of the
   * application context by its name. Without a platform module handing one over, no name is
   * a bean.
   */
  private java.util.function.Function<String, java.util.Optional<Class<?>>> classOfTheBeanNamed = name -> java.util.Optional
      .empty();

  /**
   * Hands over how to find the bean the engine resolves a name to. The startup check needs
   * it, because such a bean hides a task of the same name.
   *
   * @param classOfTheBeanNamed The class of the bean of a name, empty where the engine
   *          resolves no bean of that name
   */
  public void setClassOfTheBeanNamed(
      final java.util.function.Function<String, java.util.Optional<Class<?>>> classOfTheBeanNamed) {

    this.classOfTheBeanNamed = classOfTheBeanNamed;

  }

  /**
   * Ends the start where a task definition of the model this boot deploys has the name of
   * something the engine resolves first: a shared attribute of the workflow aggregate, or a
   * bean. Every such task definition of the process is named at once.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param connectables The tasks of the model
   */
  private void refuseNamesWhichHideATask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<Camunda7TaskConnectable> connectables) {

    final var names = Camunda7NamesHidingATask
        .reachedByAnExpression(connectables)
        .stream()
        .map(Camunda7TaskConnectable::taskDefinition)
        // a name with a dot in it would be read as a path into the aggregate. A task
        // definition is one name, and only one name can be a process variable
        .filter(name -> !name.contains("."))
        .distinct()
        .toList();
    if (names.isEmpty()) {
      return;
    }
    // the core answers a type exactly for the names whose value reaches the engine. That is
    // the set of process variables the aggregate writes, under the sync mode which applies
    final var sharedAttributes = workflowTaskWiring
        .declaredTypesOfWorkflowAggregatePaths(
            workflowModuleId,
            bpmnProcessId,
            names,
            io.vanillabp.camunda7.processservice.Camunda7ProcessService.SYNC_MODE)
        .keySet();
    final var findings = Camunda7NamesHidingATask
        .findings(connectables, bpmnProcessId, workflowModuleId, sharedAttributes, classOfTheBeanNamed);
    if (!findings.isEmpty()) {
      throw new IllegalStateException(String.join("\n", findings));
    }

  }

  /**
   * What the configuration says about the listeners somebody modelled. It is
   * <code>null</code> until a platform module hands one over. This is the default: no
   * listener is served.
   */
  private io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver allowListenersResolver;

  /**
   * Hands over how <code>allow-listeners</code> resolves for this adapter instance.
   *
   * @param allowListenersResolver The resolver, or <code>null</code> for the default
   */
  public void setAllowListenersResolver(
      final io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver allowListenersResolver) {

    this.allowListenersResolver = allowListenersResolver;

  }

  /**
   * Whether the listeners somebody modelled are served for one BPMN process, and which key said
   * so.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   * @return The setting, never <code>null</code>
   */
  private io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver.Setting listenersAllowedFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver
        .resolve(allowListenersResolver, workflowModuleId, bpmnProcessId);

  }

  /**
   * The engine's runtime service. The startup check uses it to ask how many workflows still
   * run on an old version of a process. The platform integration sets it, like the identity
   * service.
   *
   * @param runtimeService The engine's runtime service
   */
  public void setRuntimeService(
      final org.camunda.bpm.engine.RuntimeService runtimeService) {

    this.runtimeService = runtimeService;
    processVersions.setRuntimeService(runtimeService);

  }

  /**
   * The engine's runtime service, kept here as well. So the startup can ask WHERE the
   * workflows of a process run. May be <code>null</code> in tests.
   */
  private org.camunda.bpm.engine.RuntimeService runtimeService;

  /**
   * How the serialization format of a workflow is resolved. The startup check needs this,
   * because a workflow may deviate from its module and a module from the adapter. The
   * platform integration sets it, like the identity service. May be <code>null</code>
   * (tests). Then nothing is reported about a format.
   */
  private io.vanillabp.camunda7.sync.Camunda7SerializationFormats serializationFormats;

  /**
   * Sets the format resolution of the platform integration.
   *
   * @param serializationFormats The format per workflow, module and adapter
   */
  public void setSerializationFormats(
      final io.vanillabp.camunda7.sync.Camunda7SerializationFormats serializationFormats) {

    this.serializationFormats = serializationFormats;

  }

  /**
   * What a configured format does to a value, measured through this engine's own
   * serializers. May be <code>null</code>, in tests or for an engine which does not hand its
   * configuration over. Then nothing is reported.
   */
  private io.vanillabp.camunda7.sync.Camunda7SerializationRoundTrip serializationRoundTrip;

  /**
   * Sets the probe reading this engine's serializers.
   *
   * @param serializationRoundTrip The probe, or <code>null</code>
   */
  public void setSerializationRoundTrip(
      final io.vanillabp.camunda7.sync.Camunda7SerializationRoundTrip serializationRoundTrip) {

    this.serializationRoundTrip = serializationRoundTrip;

  }

  /**
   * The process definition which the engine considers current for that process. This
   * application runs on it, if its resources were deployed before.
   */
  private org.camunda.bpm.engine.repository.ProcessDefinition latestVersionOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var tenantId = tenantIdOf(workflowModuleId);
    var query = repositoryService
        .createProcessDefinitionQuery()
        .processDefinitionKey(scopedProcessId(workflowModuleId, bpmnProcessId))
        .latestVersion();
    query = tenantId == null
        ? query.withoutTenantId()
        : query.tenantIdIn(tenantId);
    return query.singleResult();

  }

  /**
   * What this adapter's extraction says about a model which the engine still holds. It is
   * handed to the version catalog. So the catalog's questions about an old version are
   * answered by the same walks which a deployed model goes through.
   */
  private final class HeldModels implements io.vanillabp.camunda7.wiring.Camunda7ProcessVersions.HeldModelReading {

    @Override
    public java.util.Collection<BpmnTaskSpec> tasksOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version,
        final BpmnModelInstance model) {

      return tasksOfDeployedModel(workflowModuleId, bpmnProcessId, version, model);

    }

    @Override
    public java.util.Collection<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec> startEventsOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version,
        final BpmnModelInstance model) {

      return startEventsOfHeldModel(workflowModuleId, bpmnProcessId, version, model);

    }

    @Override
    public java.util.Collection<String> concurrentTokenElementsOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version,
        final BpmnModelInstance model) {

      return concurrentTokenElementsOfHeldModel(workflowModuleId, bpmnProcessId, model);

    }

    @Override
    public java.util.Collection<io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier> identifiersOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version,
        final BpmnModelInstance model) {

      return identifiersOfHeldModel(workflowModuleId, model);

    }

  }

  /**
   * The identifiers which a model the engine still holds declares and the workflow module
   * scopes. This is the same walk which the deployment runs over a model it brings. Here it
   * runs over a version which an earlier generation of this application deployed.
   * <p>
   * The names come back as the application knows them. The engine holds the model as it was
   * deployed. So a prefix is stripped here, because the core composes the scoped forms
   * itself.
   *
   * @param workflowModuleId The workflow module ID
   * @param model The model of that version
   * @return What it declares, plain
   */
  private java.util.Collection<io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier> identifiersOfHeldModel(
      final String workflowModuleId,
      final BpmnModelInstance model) {

    return io.vanillabp.camunda7.wiring.Camunda7Scoping
        .identifiersDeclaredBy(model, identifier -> plainIdentifier(workflowModuleId, identifier));

  }

  /**
   * The elements of a model which the engine still holds and which can put a SECOND token
   * into one of its workflows. This is the same walk which this adapter reports to the core
   * while wiring. Here it runs over an old version.
   * <p>
   * The versions which run longest are the ones which a look at this boot's model never
   * reaches. Say the newest model dropped a parallel gateway. The gateway still forks every
   * workflow started before. Two branches which write one workflow aggregate lose an update
   * there, exactly as they would in the model just deployed.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param model The model of that version
   * @return The IDs of the elements producing a second token in that version
   */
  private java.util.Collection<String> concurrentTokenElementsOfHeldModel(
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmnModelInstance model) {

    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    final var elementIds = new java.util.LinkedHashSet<>(
        Camunda7ConcurrentTokens.elementIdsOf(model, scopedBpmnProcessId));
    // a version which the engine still holds carries its compensation flat, as element ids
    // among the others. The shaped report belongs to the model this boot deploys. A developer
    // can still redraw that one. For an older version, there is only one thing to say: its
    // workflows can hold two tokens. Carrying the shape here as well would take a second
    // method on the version catalog. The flat list has no room for which throw event starts
    // which handlers. And nothing an old version could answer would change what a developer
    // does about it
    Camunda7ConcurrentTokens
        .compensationOf(model, scopedBpmnProcessId)
        .stream()
        .filter(compensation -> compensation.handlerIds().size() > 1)
        .forEach(compensation -> {
          elementIds.add(compensation.throwEventId());
          elementIds.addAll(compensation.handlerIds());
        });
    return List.copyOf(elementIds);

  }

  /**
   * The tasks of a model which the engine still holds, read for the old-versions startup
   * check. This is the same extraction which the deployed model goes through. So both
   * directions cannot disagree about what a task is.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version the engine assigned
   * @param model The model of that version
   * @return The tasks of that version
   */
  private java.util.Collection<BpmnTaskSpec> tasksOfDeployedModel(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final BpmnModelInstance model) {

    final var specs = new LinkedList<BpmnTaskSpec>();
    collectTasks(
        model,
        workflowModuleId,
        bpmnProcessId,
        scopedProcessId(workflowModuleId, bpmnProcessId),
        "version %s".formatted(version),
        specs,
        null,
        null);
    return specs;

  }

  /**
   * Collects the connectables of ONE version which the engine holds under a declared BPMN
   * process id. They are keyed, so a task which several versions share is registered once.
   * <p>
   * A model which the extraction refuses is skipped. It does not end the boot. The
   * extraction refuses a model wired by <code>camunda:topic</code>, or by an expression
   * which VanillaBP does not understand. The deployment of a MODEL THIS BOOT BRINGS should
   * end over it, and that is the same check. But this model was deployed by an earlier
   * generation of the application, and nobody can change it any more. So the honest answer
   * is one warning about the version which cannot be wired. The versions which can be
   * wired are wired.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID nothing was deployed under
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @param version The version the engine assigned
   * @param definitionId The engine's process definition id of that version
   * @param distinctConnectables Collects the connectables, by element, task definition and
   *          type
   */
  private void wireTheModelOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final String version,
      final String definitionId,
      final Map<String, Camunda7TaskConnectable> distinctConnectables) {

    final var specs = new LinkedList<BpmnTaskSpec>();
    final var connectables = new LinkedList<Camunda7TaskConnectable>();
    try {
      final var model = repositoryService.getBpmnModelInstance(definitionId);
      // the start listener attached at parse time asks for the PLAIN signal name of a
      // signal start event. For a model which only the engine holds, nobody registered
      // one. But it is right here, in the engine's own copy of the model. So a workflow
      // which the engine starts under the old id is told which signal fired
      registerSignalStartEventsOf(
          workflowModuleId, scopedBpmnProcessId, startEventsOf(workflowModuleId, scopedBpmnProcessId, model));
      collectTasks(
          model,
          workflowModuleId,
          bpmnProcessId,
          scopedBpmnProcessId,
          "version %s".formatted(version),
          specs,
          connectables,
          null);
    } catch (final RuntimeException e) {
      log.warn(
          """
              Camunda7[{}]: version {} of the declared BPMN process '{}' (workflow module '{}') could \
              not be wired, so a workflow still running on THAT version reaches its next task, finds \
              nothing wired to it and ends in an incident. The other versions of that id are wired. \
              Either deploy a model under the old id again until those workflows have ended, or \
              complete them by other means.""",
          adapterId,
          version,
          bpmnProcessId,
          workflowModuleId,
          e);
      return;
    }
    connectables
        .forEach(connectable -> distinctConnectables
            .putIfAbsent(
                "%s|%s|%s".formatted(connectable.elementId(), connectable.taskDefinition(), connectable.type()),
                connectable));

  }

  /**
   * Whether a <code>&#64;WorkflowTask</code> method serving this task wants to keep it open.
   * It is asked with BOTH keys a task is wired by: the task definition and the element id. A
   * method names either of the two (<code>&#64;WorkflowTask(taskDefinition = ...)</code> or
   * <code>&#64;WorkflowTask(id = ...)</code>), and <code>validateTaskWiring</code> matches on
   * either. So a question asked with one key alone says nothing about an application which
   * wires by the other one. Such an application would meet the defect as an incident on a
   * live workflow, not as a boot which does not start.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param connectable The task of the model
   * @return Whether a method serving the task completes it asynchronously
   */
  private boolean aMethodOfTheTaskWantsToKeepItOpen(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Camunda7TaskConnectable connectable) {

    return workflowTaskWiring
        .workflowTaskCompletesAsynchronously(workflowModuleId, bpmnProcessId,
            connectable.taskDefinition()) || workflowTaskWiring
                .workflowTaskCompletesAsynchronously(workflowModuleId, bpmnProcessId, connectable.elementId());

  }

  /**
   * Ends the deployment where a <code>&#64;WorkflowTask</code> method wants the item of a
   * multi-instance element which this model never names one for.
   * <p>
   * It is judged per task, over the chain of iterations ENCLOSING the task. A handler gets
   * the item of the rounds its own element runs in. So an element of another branch of the
   * same process is no finding here. Its item never reaches this handler, whatever the
   * element names. Reading the whole process instead, as this adapter once did, ends the
   * boot over a model which is right. Camunda 8 has read the chain from the start.
   * <p>
   * Only elements of THIS model are judged. The multi-instance chain crosses a call
   * activity. So a task of a called process asks for an element of its caller, and this
   * model is the wrong place to look for that element. So an id which nothing here knows
   * is no finding.
   * <p>
   * The core is asked by the task definition AND by the element id. This is the pair which
   * {@code validateTaskWiring} matches a method against. A method may name either of the
   * two. Otherwise a method which names the element id would be missed.
   * <p>
   * Every finding of the process goes into ONE message. The wiring validation does the same
   * and reports every unwired task at once. A developer who fixes one model should not have
   * to restart to meet the next line of the same defect.
   */
  private void refuseHandlersWantingAnItemTheModelHasNot(
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmnModelInstance model,
      final List<Camunda7TaskConnectable> connectables) {

    final var findings = new LinkedList<Camunda7MultiInstanceItems.Finding>();
    for (final var connectable : connectables) {
      final var withoutAnItem = Camunda7MultiInstanceItems
          .elementsWithoutAnItemAround(model.getModelElementById(connectable.elementId()));
      if (withoutAnItem.isEmpty()) {
        continue;
      }
      final var wanted = new java.util.LinkedHashSet<String>();
      wanted
          .addAll(workflowTaskWiring
              .multiInstanceElementNames(workflowModuleId, bpmnProcessId, connectable.taskDefinition()));
      wanted
          .addAll(workflowTaskWiring
              .multiInstanceElementNames(workflowModuleId, bpmnProcessId, connectable.elementId()));
      wanted.retainAll(withoutAnItem);
      if (!wanted.isEmpty()) {
        findings
            .add(new Camunda7MultiInstanceItems.Finding(
                connectable.elementId(), connectable.taskDefinition(), wanted));
      }
    }
    if (findings.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        Camunda7MultiInstanceItems.refusal(findings, bpmnProcessId, workflowModuleId));

  }

  /**
   * Extracts the tasks of ONE executable BPMN process into the specs which the core
   * validates against. For the model this boot deploys, it also extracts them into the
   * connectables which the engine's EL resolver looks up at runtime.
   * <p>
   * The startup check reads the models of OLDER versions which the engine still holds. It
   * asks the core whether the application still serves them. That is why this sits in its
   * own method. Both directions have to see a model exactly the same way, and a second
   * implementation would drift.
   *
   * @param model The BPMN model, carrying the identifiers the ENGINE knows
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param scopedBpmnProcessId The BPMN process ID as the engine knows it
   * @param describedSource What to name in a message about the model, self-describing
   *          ("file 'x.bpmn'" for the deployed model, "version 3" for one the engine
   *          holds)
   * @param specs Collects the task specs
   * @param connectables Collects the connectables, or <code>null</code> for a model
   *          which is only being READ on behalf of a check. Such a model is never
   *          refused. A model which this boot deploys may be refused. A model which the
   *          engine already holds gets a warning instead, because nobody can change it
   *          any more
   * @param context What the module's startup report is assembled in, or <code>null</code>
   *          for a model which is only being READ
   */
  private void collectTasks(
      final BpmnModelInstance model,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final String describedSource,
      final List<BpmnTaskSpec> specs,
      final List<Camunda7TaskConnectable> connectables,
      final Camunda7ProcessingContext context) {
    serviceLikeTasksOf(model, scopedBpmnProcessId)
        .forEach(task -> {
          final var delegateExpression = task.getAttributeValueNs(CAMUNDA_NS, "delegateExpression");
          final var expression = task.getAttributeValueNs(CAMUNDA_NS, "expression");
          final var topic = task.getAttributeValueNs(CAMUNDA_NS, "topic");
          if ((topic != null) && !topic.isBlank()) {
            if (connectables == null) {
              // the refusal below is about a model which is being DEPLOYED. This model
              // is only being READ, for a check about versions which the engine already
              // holds. Nobody can change it any more. So the boot goes on, and the check
              // does not see this task
              log.warn(
                  """
                      Camunda7[{}]: task '{}' of BPMN process '{}' ({}, workflow module '{}') is \
                      implemented as an external task (camunda:topic '{}'), which VanillaBP does \
                      not serve. The model is already in the engine, so nothing here can change \
                      that - workflows reaching the task are served by whatever polls the topic, \
                      and the report about older versions says nothing about it.""",
                  adapterId,
                  task.getId(),
                  bpmnProcessId,
                  describedSource,
                  workflowModuleId,
                  topic);
              return;
            }
            // an external task is served by whatever polls its topic, and that is not
            // VanillaBP. Where the application says so, the task goes to the core like any
            // other one. The core holds the rule for every task and refuses a method next to
            // the line. Nothing here subscribes to the topic
            final var externalTask = new BpmnTaskSpec(
                task.getId(), topic, false, null, Camunda7MultiInstanceItems.elementsWithoutAnItemAround(task));
            if (workflowTaskWiring.isImplementedExternally(adapterId, workflowModuleId, bpmnProcessId, externalTask)) {
              specs.add(externalTask);
              return;
            }
            throw new IllegalStateException(
                """
                    Task '%s' of BPMN process '%s' (%s, workflow module '%s') is implemented \
                    as an external task (camunda:topic '%s'), which VanillaBP does not serve! \
                    Wire the task by 'camunda:expression' or 'camunda:delegateExpression' naming the \
                    @WorkflowTask method's task definition, e.g. ${%s}. %s"""
                    .formatted(
                        task.getId(),
                        bpmnProcessId,
                        describedSource,
                        workflowModuleId,
                        topic,
                        topic,
                        io.vanillabp.integration.adapter.spi.workflowtask.ImplementedExternally
                            .howToMark(workflowModuleId, bpmnProcessId, externalTask)));
          }
          // a business rule task which calls a DECISION is served by the engine, not by the
          // application. The decision was deployed with this process. Asking for a
          // @WorkflowTask method would make DMN unusable on this adapter. A business rule
          // task wired by an expression is an ordinary VanillaBP task. It falls through to
          // the branches below
          final var decisionRef = task.getAttributeValueNs(CAMUNDA_NS, "decisionRef");
          if ((decisionRef != null) && !decisionRef.isBlank()) {
            return;
          }
          final String rawExpression;
          final Camunda7TaskConnectable.Type type;
          if ((delegateExpression != null) && !delegateExpression.isBlank()) {
            rawExpression = delegateExpression;
            type = Camunda7TaskConnectable.Type.DELEGATE_EXPRESSION;
          } else if ((expression != null) && !expression.isBlank()) {
            rawExpression = expression;
            type = Camunda7TaskConnectable.Type.EXPRESSION;
          } else {
            // no implementation given. The wiring validation reports it with a guiding
            // message (task definition null, so it is matched by activity ID only)
            specs
                .add(new BpmnTaskSpec(
                    task.getId(), null, false, null, Camunda7MultiInstanceItems
                        .elementsWithoutAnItemAround(task)));
            return;
          }
          final String taskDefinition;
          if (connectables == null) {
            // a model which is only being READ. If VanillaBP cannot read an expression, the
            // check loses its view of THIS task. The boot never fails over it
            try {
              taskDefinition = unwrapExpression(
                  rawExpression, task.getId(), bpmnProcessId, describedSource, workflowModuleId);
            } catch (final IllegalStateException e) {
              log.warn(
                  """
                      Camunda7[{}]: the expression '{}' of task '{}' of BPMN process '{}' ({}, \
                      workflow module '{}') cannot be read by VanillaBP. The model is already in \
                      the engine, so nothing here can change that - the report about older \
                      versions says nothing about this task.""",
                  adapterId,
                  rawExpression,
                  task.getId(),
                  bpmnProcessId,
                  describedSource,
                  workflowModuleId);
              return;
            }
          } else {
            taskDefinition = unwrapExpression(
                rawExpression, task.getId(), bpmnProcessId, describedSource, workflowModuleId);
          }
          // which rounds this task iterates in without being handed their value. The core
          // checks this against the methods which serve a version the engine still holds. A
          // method which reads the item of such an element would be given null
          specs
              .add(new BpmnTaskSpec(
                  task.getId(), taskDefinition, false, null, Camunda7MultiInstanceItems
                      .elementsWithoutAnItemAround(task)));
          if (connectables != null) {
            connectables.add(new Camunda7TaskConnectable(
                workflowModuleId, bpmnProcessId, scopedBpmnProcessId, task.getId(), taskDefinition, type));
          }
        });

    // user tasks: the task definition is the camunda:formKey. A user task needs a
    // @WorkflowTask method like every other task, or the property which says that
    // something else serves it. The core asks for one of the two, as version 1 asked for
    // the method
    model
        .getModelElementsByType(org.camunda.bpm.model.bpmn.instance.UserTask.class)
        .stream()
        .filter(task -> scopedBpmnProcessId.equals(owningProcessId(task)))
        .forEach(task -> {
          // what a user task is called, read the way this adapter's published rule reads
          // it: the form key AS WRITTEN, an expression included. See
          // io.vanillabp.camunda7.api.Camunda7TaskDefinitions
          final var formKey = io.vanillabp.camunda7.api.Camunda7TaskDefinitions.formKeyOf(task);
          specs
              .add(new BpmnTaskSpec(
                  task.getId(), formKey, true, null, Camunda7MultiInstanceItems
                      .elementsWithoutAnItemAround(task)));
          if (connectables != null) {
            connectables.add(new Camunda7TaskConnectable(
                workflowModuleId, bpmnProcessId, scopedBpmnProcessId, task
                    .getId(), formKey, Camunda7TaskConnectable.Type.USER_TASK));
          }
        });

    // the listeners somebody modelled. From here on they are tasks like any other one,
    // where the application asked for them
    collectModelledListeners(
        model,
        workflowModuleId,
        bpmnProcessId,
        scopedBpmnProcessId,
        describedSource,
        specs,
        connectables,
        context);

  }

  /**
   * Reads the execution listeners somebody modelled out of one process. It turns the ones the
   * application asked for into tasks like any other.
   * <p>
   * They go through the core's task specs on purpose. A listener which nothing serves ends the
   * boot, because {@code validateTaskWiring} asks for a method. A method which serves no
   * listener of any wired process is caught by {@code validateNoUnwiredWorkflowTaskMethods}.
   * Version 1 wired its listeners privately and had neither direction.
   *
   * @param model The BPMN model, carrying the identifiers the ENGINE knows
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID, which is what a property key names
   * @param scopedBpmnProcessId The BPMN process ID as the engine knows it
   * @param describedSource What to name in a message about the model
   * @param specs Collects the task specs
   * @param connectables Collects the connectables, or <code>null</code> for a model which is
   *          only being READ: such a model is never refused
   * @param context What the module's startup report is assembled in, or <code>null</code>
   */
  private void collectModelledListeners(
      final BpmnModelInstance model,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final String describedSource,
      final List<BpmnTaskSpec> specs,
      final List<Camunda7TaskConnectable> connectables,
      final Camunda7ProcessingContext context) {

    final var setting = listenersAllowedFor(workflowModuleId, bpmnProcessId);
    if (setting.allowed() && (context != null)) {
      // remembered even where the process carries no listener at all. So the report can say
      // that the key is on and nothing of this module uses it
      context.recordListenersAllowed(bpmnProcessId, setting.propertyKey());
    }
    final var served = io.vanillabp.camunda7.wiring.Camunda7Listeners
        .listenersOf(model, scopedBpmnProcessId, expression -> readTaskDefinitionOf(expression))
        .stream()
        .filter(listener -> listener.implementation().servable())
        .filter(listener -> listener.taskDefinition() != null)
        // and here is the line which decides. A listener is this application's business
        // where a @WorkflowTask method names its task definition, and nobody else's. The
        // engine itself resolves everything else a delegate expression can name: a Spring
        // bean, a CDI bean, a class, a script. That is an ordinary Camunda 7 model, and this
        // adapter has no business taking it away. Only the task-definition route counts.
        // @WorkflowTask(id = ...) names the ELEMENT, and an element may carry a task and a
        // listener at once
        .filter(listener -> workflowTaskInvoker
            .workflowTaskHandlerExists(workflowModuleId, bpmnProcessId, listener.taskDefinition()))
        .toList();
    if (served.isEmpty()) {
      return;
    }
    if (!setting.allowed()) {
      refuseOrSayItIsTooLate(
          connectables != null,
          refuseTheListenersNobodyAllowed(workflowModuleId, bpmnProcessId, describedSource, served));
      return;
    }
    final var sharing = io.vanillabp.camunda7.wiring.Camunda7Listeners
        .listenersSharingATaskDefinition(served);
    if (!sharing.isEmpty()) {
      refuseOrSayItIsTooLate(
          connectables != null,
          refuseListenersSharingATaskDefinition(workflowModuleId, bpmnProcessId, describedSource, sharing));
      return;
    }
    served
        .forEach(listener -> {
          // the rounds which the listener's element runs in without being handed their value.
          // The core warns about a method which reads that item on a version the engine still
          // holds. A listener method reads it the same way as a task's method does. It is read
          // off the element, not passed along with the listener. The collection above reads
          // the BPMN for the id, and the id is all it needs
          specs
              .add(BpmnTaskSpec.listener(
                  listener.elementId(), listener.taskDefinition(), Camunda7MultiInstanceItems
                      .elementsWithoutAnItemAround(model.getModelElementById(listener.elementId()))));
          if (context != null) {
            context.recordModelledListener(listener);
          }
          if ((connectables != null) && (taskRegistry != null) && !io.vanillabp.camunda7.wiring.Camunda7Listeners
              .isACancellation(listener)) {
            // the element may be taken away before this listener's own moment arrives. So
            // VanillaBP tells the method itself. The parse listener attaches the engine's end
            // listener to the element. This is where it learns which elements need one
            taskRegistry
                .registerListenerNeedingACancellation(
                    workflowModuleId, scopedBpmnProcessId, listener.elementId(), listener.taskDefinition());
          }
          if (connectables != null) {
            connectables.add(new Camunda7TaskConnectable(
                workflowModuleId, bpmnProcessId, scopedBpmnProcessId, listener.elementId(), listener
                    .taskDefinition(), listener
                        .implementation() == io.vanillabp.camunda7.wiring.Camunda7Listeners.Implementation.DELEGATE_EXPRESSION
                            ? Camunda7TaskConnectable.Type.EXECUTION_LISTENER_DELEGATE_EXPRESSION
                            : Camunda7TaskConnectable.Type.EXECUTION_LISTENER_EXPRESSION));
          }
        });

  }

  /**
   * Ends the boot over a model which this application is about to deploy. It says the same
   * words about a model which the engine already holds.
   * <p>
   * Both findings about a listener have that shape. A modeller can act on the model of this
   * boot and on no other. A version which the engine holds is read for a check, and that check
   * may not refuse what nobody can change any more.
   *
   * @param modelIsBeingDeployed Whether this boot brings the model
   * @param finding What is wrong, in words which work as a refusal and as a warning
   */
  private void refuseOrSayItIsTooLate(
      final boolean modelIsBeingDeployed,
      final String finding) {

    if (modelIsBeingDeployed) {
      throw new IllegalStateException(finding);
    }
    log.warn("Camunda7[{}]: {}", adapterId, finding);

  }

  /**
   * The task definition an expression of the model names, or <code>null</code> where VanillaBP
   * cannot read the expression. This is the tolerant twin of {@link #unwrapExpression}. The
   * caller judges a listener's expression, because it knows whether the model can still be
   * changed.
   *
   * @param rawExpression The expression as it stands in the model
   * @return The unwrapped text, or <code>null</code>
   */
  private static String readTaskDefinitionOf(
      final String rawExpression) {

    final var matcher = EL_PATTERN.matcher(rawExpression.trim());
    return matcher.matches()
        ? matcher.group(1).trim()
        : null;

  }

  /**
   * The message which ends a boot over a model whose listeners nobody allowed.
   * <p>
   * The adapter refuses here itself. It does not leave this to the core's wiring validation,
   * as the Camunda 8 adapter's guidance about connectors does. A connector is an element
   * which VanillaBP is asked to LEAVE ALONE. So the validation finds a task which nothing
   * serves, and it ends the boot by itself. A listener is the other way round. The key asks
   * VanillaBP to serve something. Without the key, there is no task spec and nothing for the
   * validation to miss. The engine would then evaluate the listener's expression itself. It
   * would fail at the first workflow which reaches the element, and nobody reads that
   * message at boot.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param describedSource What the model is
   * @param listeners The listeners a method could serve
   * @return The message
   */
  private String refuseTheListenersNobodyAllowed(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String describedSource,
      final List<io.vanillabp.camunda7.wiring.Camunda7Listeners.ModelledListener> listeners) {

    return """
        BPMN process '%s' of workflow module '%s' (%s) carries %d execution listener(s) somebody \
        modelled whose expression names a @WorkflowTask method of this application: %s. VanillaBP 1 \
        served such a listener and said nothing about it. This version does not serve it until you \
        ask for it, because the engine would evaluate the expression itself and run that method at a \
        moment nobody wired it for. Ask for it at one of three levels, the most specific configured \
        one winning:
        %s
        What it costs: %s
        %s
        Only a listener a @WorkflowTask method names is this message about. A listener whose \
        expression names something else - a Spring bean, a CDI bean, a class, a script - is resolved \
        by the engine and VanillaBP says nothing about it; and a listener VanillaBP or one of its \
        extensions attaches is never written into the BPMN at all."""
        .formatted(
            bpmnProcessId,
            workflowModuleId,
            describedSource,
            listeners.size(),
            listeners
                .stream()
                .map(io.vanillabp.camunda7.wiring.Camunda7Listeners.ModelledListener::describe)
                .collect(java.util.stream.Collectors.joining("; ")),
            io.vanillabp.camunda7.wiring.Camunda7Listeners
                .levelsOf(adapterId, workflowModuleId, bpmnProcessId),
            io.vanillabp.camunda7.wiring.Camunda7Listeners.WHAT_IT_COSTS,
            io.vanillabp.camunda7.wiring.Camunda7Listeners.WHICH_METHOD_SERVES_WHICH);

  }

  /**
   * The message which ends a boot where one element carries two served listeners under one task
   * definition.
   * <p>
   * This is the defect which version 1 left open. There, two delegate listeners of one element
   * became two entries with the same identity. A {@code findFirst} decided which of them ran.
   * Here they would become one task, served by one method and called for two events. Nothing
   * the method could ask would say which event it is in, because {@code TaskEvent.Event} has
   * no value for a listener's event. The fix is an expression per event. Naming the case is
   * better than picking one of them.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param describedSource What the model is
   * @param sharing Per element and task definition the listeners sharing it
   * @return The message
   */
  private String refuseListenersSharingATaskDefinition(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String describedSource,
      final List<List<io.vanillabp.camunda7.wiring.Camunda7Listeners.ModelledListener>> sharing) {

    return """
        BPMN process '%s' of workflow module '%s' (%s) has one element carrying several execution \
        listeners under ONE expression: %s. One @WorkflowTask method would serve all of them and \
        nothing would tell it which event it is being called for, because the event is part of a \
        listener's identity and TaskEvent.Event has no value for it. Give every listener of an element \
        an expression of its own and write a method per expression."""
        .formatted(
            bpmnProcessId,
            workflowModuleId,
            describedSource,
            sharing
                .stream()
                .map(listeners -> listeners
                    .stream()
                    .map(io.vanillabp.camunda7.wiring.Camunda7Listeners.ModelledListener::describe)
                    .collect(java.util.stream.Collectors.joining(" and ")))
                .collect(java.util.stream.Collectors.joining("; ")));

  }

  /**
   * The report which a workflow module with served modelled listeners writes on EVERY boot. It
   * is written once, after the module's deployment went through.
   * <p>
   * It is framed, and nothing else this adapter logs is framed. A WARN level alone gets lost in
   * a boot log where every other line is one line high. A second framed message would take
   * away this one's effect. No key silences it. What it says stays true for as long as the
   * listener is in the model. So a key which turns it off would only make the loss invisible.
   * See decision 20 in the repository's DECISIONS.md.
   *
   * @param workflowModuleId The workflow module
   * @param context The module's accumulated pipeline state
   */
  void reportWhatListenersCost(
      final String workflowModuleId,
      final Camunda7ProcessingContext context) {

    final var allowedBy = context.getListenersAllowedBy();
    if (allowedBy.isEmpty()) {
      return;
    }
    final var switchedOnBy = allowedBy
        .values()
        .stream()
        .filter(java.util.Objects::nonNull)
        .distinct()
        .collect(java.util.stream.Collectors.joining(", "));
    if (context.getModelledListeners().isEmpty()) {
      log.warn(
          "Camunda7[{}]: the listeners of workflow module '{}' are served ({}), and no model of it "
              + "carries one. Set '{}: false' where you do not need the switch.",
          adapterId,
          workflowModuleId,
          switchedOnBy,
          io.vanillabp.camunda7.wiring.Camunda7Listeners.propertyKeyOf(adapterId));
      return;
    }
    log.warn(
        """

            {}
            MODELLED LISTENERS ARE SERVED: WORKFLOW MODULE '{}', CAMUNDA 7 ADAPTER '{}'
            {}
            Switched on by: {}
            VanillaBP serves the following listener(s) with a @WorkflowTask method, one method per \
            listener:
            {}
            {}
            {}
            {}
            The way back: move what the listener does into a task of the model with a @WorkflowTask \
            method behind it, or set '{}: false'.
            {}""",
        io.vanillabp.camunda7.wiring.Camunda7Listeners.FRAME_LINE,
        workflowModuleId,
        adapterId,
        io.vanillabp.camunda7.wiring.Camunda7Listeners.FRAME_LINE,
        switchedOnBy,
        context
            .getModelledListeners()
            .stream()
            .map(listener -> "  "
                + listener.describe())
            .collect(java.util.stream.Collectors.joining("\n")),
        io.vanillabp.camunda7.wiring.Camunda7Listeners.WHAT_IT_COSTS,
        io.vanillabp.camunda7.wiring.Camunda7Listeners.WHICH_METHOD_SERVES_WHICH,
        io.vanillabp.camunda7.wiring.Camunda7Listeners.HOW_A_CANCELLATION_IS_REPORTED,
        io.vanillabp.camunda7.wiring.Camunda7Listeners.propertyKeyOf(adapterId),
        io.vanillabp.camunda7.wiring.Camunda7Listeners.FRAME_LINE);

  }

  /**
   * The service-like tasks (service, send, business-rule tasks) of the given
   * executable process, including tasks inside embedded subprocesses.
   */
  private static Stream<Task> serviceLikeTasksOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    return Stream
        .of(ServiceTask.class, SendTask.class, BusinessRuleTask.class)
        .flatMap(type -> model.getModelElementsByType(type).stream())
        .map(Task.class::cast)
        .filter(task -> bpmnProcessId.equals(owningProcessId(task)));

  }

  static String owningProcessId(
      final FlowElement element) {

    ModelElementInstance current = element;
    while (current != null) {
      if (current instanceof Process process) {
        return process.getId();
      }
      current = current.getParentElement();
    }
    return null;

  }

  private static String unwrapExpression(
      final String rawExpression,
      final String elementId,
      final String bpmnProcessId,
      final String describedSource,
      final String workflowModuleId) {

    final var matcher = EL_PATTERN.matcher(rawExpression.trim());
    if (!matcher.matches()) {
      throw new IllegalStateException(
          """
              The expression '%s' of task '%s' of BPMN process '%s' (%s, workflow module \
              '%s') is not supported by VanillaBP! Use a simple expression naming the @WorkflowTask \
              method's task definition, e.g. ${myTaskDefinition}."""
              .formatted(rawExpression, elementId, bpmnProcessId, describedSource, workflowModuleId));
    }
    return matcher.group(1).trim();

  }

  /**
   * Reports every expression of the model which reads a path of workflow-aggregate
   * attributes that the BPMS is not given. It reports the segment where the path stops.
   * <p>
   * It is a WARN, not a failed deployment. The check reads expressions, and an expression
   * which it misreads must not keep an application from starting. What it finds is precise
   * enough to act on: the element, the expression, the segment, what the engine will do with
   * the null, and the way out. A model which works produces nothing at all.
   * <p>
   * The severity is the same for every placement, but the SENTENCE is not. A conditional
   * event waits forever without a trace, while a timer raises an incident. The reader needs
   * to know which of the two they are looking at. A second severity would only invite
   * filtering.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID as the application knows it
   * @param origins What the expressions of the model read, keyed by the path
   */
  private void warnAboutUnsharedAggregatePaths(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Map<String, io.vanillabp.camunda7.sync.Camunda7ExpressionIdentifiers.Origin> origins) {

    if (origins.isEmpty()) {
      return;
    }
    workflowTaskWiring
        .unsharedWorkflowAggregatePaths(
            workflowModuleId,
            bpmnProcessId,
            origins.keySet(),
            io.vanillabp.camunda7.processservice.Camunda7ProcessService.SYNC_MODE)
        .forEach((
            path,
            verdict) -> reportOneExpression(
                workflowModuleId,
                bpmnProcessId,
                path,
                origins.get(path),
                verdict));

  }

  /**
   * Says that the compensation handlers of a throw event share one transaction with that
   * event. This is not what this adapter promises for a service-like task.
   * <p>
   * The parse listener writes <code>asyncBefore</code> on every service-like task, the
   * handlers included. But the engine ignores it there. It starts a compensation handler
   * outside the normal flow, where no job is created. {@code Camunda7CompensationTokensTest}
   * measured this on 2026-10-01 against the pinned engine 7.24.0. Both handlers of one throw
   * event ran in the same command context, and one commit covered both of them. The two
   * activities they compensated had a transaction each. A handler which fails rolls the
   * whole compensation back. So the handlers which had already returned run again.
   * <p>
   * This is a hint, not a refusal. It is what the engine does with a correct model, not a
   * mistake somebody made, and no flag changes it. What somebody can change is the handler.
   * That is why the message asks for a handler which may run twice. Decision 5 in the
   * repository's DECISIONS.md names the promise, and the exception beside it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param compensation The throw events of this model with the handlers they start
   */
  private void warnAboutCompensationSharingOneTransaction(
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<io.vanillabp.integration.adapter.spi.workflowtask.CompensationSpec> compensation) {

    compensation
        .stream()
        .filter(thrown -> !thrown.handlerIds().isEmpty())
        .forEach(thrown -> log
            .warn(
                """
                    Camunda7[{}]: the compensation throw event '{}' of BPMN process '{}' (workflow \
                    module '{}') starts {} compensation handler(s) ('{}'), and this engine runs all \
                    of them in the transaction of the throw event. A service-like task otherwise \
                    gets a job and therefore a transaction of its own here, and this adapter writes \
                    the flags for it, but the engine starts a compensation handler outside the normal \
                    flow, where it makes no job of it. So those handlers and their side effects share \
                    one transaction, and a handler which fails rolls back what the handlers before it \
                    wrote and sends them back to work on the next attempt. Write a compensation handler so that running it twice does no harm, and \
                    keep work this engine cannot roll back, a call to another system above all, out \
                    of it.""",
                adapterId,
                thrown.throwEventId(),
                bpmnProcessId,
                workflowModuleId,
                thrown.handlerIds().size(),
                String.join("', '", thrown.handlerIds())));

  }

  /**
   * Says where a call activity of this model calls a process of ANOTHER workflow module. It
   * also says what that costs a BPMN error on its way back.
   * <p>
   * This is a warning, not a refusal. Such a call runs. A called process which raises no
   * BPMN error may well be what somebody meant. But the error cannot be left silent. The
   * error code is composed from the module of the process which raises it. The catcher looks
   * for the module of the process which waits. These are two different prefixes. The engine
   * answers with an incident in the called workflow, not with a word about either.
   * <p>
   * The code is not bent to fit instead. A code which travels between modules would have to
   * be composed from the CALLER's module. That would be a second rule for the same
   * identifier, and both processes would carry a name which neither of them asked for. Saying
   * it at the boot costs nothing and hides nothing. At that point somebody can still model
   * the error differently.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param scopedBpmnProcessId The BPMN process ID as the model spells it now
   * @param model The model this boot deploys
   */
  private void warnAboutCallActivitiesLeavingTheWorkflowModule(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final BpmnModelInstance model) {

    io.vanillabp.camunda7.wiring.Camunda7CallActivities
        .callActivitiesLeavingTheWorkflowModule(model, scopedBpmnProcessId, tenantIdOf(workflowModuleId))
        .forEach((
            callActivityId,
            tenantId) -> log
                .warn(
                    """
                        Camunda7[{}]: call activity '{}' of BPMN process '{}' (workflow module '{}') \
                        names the tenant '{}', so the process it calls belongs to another workflow \
                        module. A BPMN error raised in that process carries the prefix of ITS \
                        workflow module, and an error boundary event on this call activity waits \
                        for the prefix of this one - the error finds no catcher and the called \
                        workflow fails with an incident, which is the first thing anybody hears \
                        about it. Two ways out: let the called process end normally and report the \
                        outcome in a variable this process branches on, or move the called process \
                        into this workflow module, where an error code means the same on both \
                        sides.""",
                    adapterId,
                    callActivityId,
                    bpmnProcessId,
                    workflowModuleId,
                    tenantId));

  }

  /**
   * Reports every value the models read whose type the configured serialization format
   * cannot carry unchanged.
   * <p>
   * A value which Camunda 7 has no variable type for keeps its class in an object variable.
   * What an expression reads back is then the serializer's answer. That answer must not
   * depend on the format. Where this adapter cannot prevent that, it says so instead of
   * staying quiet. It says it here, while the application boots. Otherwise somebody would
   * find it in a rendered form months later.
   * <p>
   * Only what the MODELS read is asked about. This is the same list which the unshared check
   * walks. A value which nothing reads costs nobody a wrong decision. Nobody could act on a
   * message about it. The price is a value which is only rendered into a form or an email.
   * That is not this check's subject.
   * <p>
   * The silence where no format is configured is on purpose. Every one of these types
   * round-trips exactly through Java serialization, so there would be nothing to report.
   * And this adapter does not read back what an application set on the engine's own
   * <code>defaultSerializationFormat</code>. Such an application hears about the blob in
   * Cockpit from the missing-format warning instead. That is the other half of the same
   * story.
   * <p>
   * Decision 16 in the repository's DECISIONS.md says two things. A value which the engine
   * has no type for keeps its class. And a format which cannot carry it is reported, not
   * worked around.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID as the application knows it
   * @param origins What the expressions of the model read, keyed by the path
   */
  private void warnAboutTypesTheFormatCannotCarry(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Map<String, io.vanillabp.camunda7.sync.Camunda7ExpressionIdentifiers.Origin> origins) {

    if ((serializationRoundTrip == null) || (serializationFormats == null) || origins.isEmpty()) {
      return;
    }
    final var serializationFormat = serializationFormats.formatFor(workflowModuleId, bpmnProcessId);
    if ((serializationFormat == null) || serializationFormat.isBlank()) {
      return;
    }
    workflowTaskWiring
        .declaredTypesOfWorkflowAggregatePaths(
            workflowModuleId,
            bpmnProcessId,
            origins.keySet(),
            io.vanillabp.camunda7.processservice.Camunda7ProcessService.SYNC_MODE)
        .forEach((
            path,
            declaredType) -> serializationRoundTrip
                // a path with a dot in it reaches the engine inside the map which the sync
                // model built. There a format loses the TYPE, not a digit
                .whatTheFormatChangesAbout(serializationFormat, declaredType, path.contains("."))
                .ifPresent(whatComesBack -> reportLossyFormat(
                    workflowModuleId,
                    bpmnProcessId,
                    path,
                    declaredType,
                    serializationFormat,
                    whatComesBack)));

  }

  /**
   * One WARN about one value the format changes.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID as the application knows it
   * @param path The path the expression reads, segments separated by dots
   * @param declaredType The type the application declares that value as
   * @param serializationFormat The format configured for this workflow
   * @param whatComesBack What the measurement found
   */
  private void reportLossyFormat(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String path,
      final Class<?> declaredType,
      final String serializationFormat,
      final io.vanillabp.camunda7.sync.Camunda7SerializationRoundTrip.WhatComesBack whatComesBack) {

    log.warn(
        """
            Camunda7[{}]: BPMN process '{}' of workflow module '{}' shares '{}' as a {}, and the \
            serialization format '{}' configured for it cannot carry that type without loss: the \
            engine reads a value of {}. An expression rendering that value, or comparing it for \
            equality, therefore answers something other than your code holds, while a comparison \
            (${amount > 100}) is unaffected, because EL coerces both sides to BigDecimal. Three \
            ways out, pick the one which applies: keep the value out of the BPMS \
            (@NoSyncWithBPMS on its getter) and let the model decide on what your code decided; \
            share it as its text (a getter returning String) where an operator only has to read \
            it; or configure a format which carries the type, at the price the missing-format \
            warning names.""",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        path,
        declaredType.getName(),
        serializationFormat,
        whatTheFormatMadeOfIt(declaredType, whatComesBack));

  }

  /**
   * What the round trip did to the sample, as the middle of a sentence. The class is named
   * only where it changed. This tells a nested value apart from a top-level one. A format
   * which drops a digit is a different problem than a format which drops the type.
   *
   * @param declaredType The type the application declares the value as
   * @param whatComesBack What the measurement found
   * @return A phrase reading "120.50 back as 120.5"
   */
  private static String whatTheFormatMadeOfIt(
      final Class<?> declaredType,
      final io.vanillabp.camunda7.sync.Camunda7SerializationRoundTrip.WhatComesBack whatComesBack) {

    if (declaredType.equals(whatComesBack.readBackType())) {
      return "%s back as %s".formatted(whatComesBack.written(), whatComesBack.readBack());
    }
    return "%s back as a %s of %s"
        .formatted(
            whatComesBack.written(),
            whatComesBack
                .readBackType()
                .getName(),
            whatComesBack.readBack());

  }

  /**
   * One WARN about one expression. The top-level case and the path case are two texts, not
   * one text with a hole in it. The last sentence differs in what it PROMISES. The migration
   * fallback of the EL resolver answers a top-level name. It stops as soon as something
   * stands before the dot. So a reported path must not be offered the fallback.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID as the application knows it
   * @param path The path the expression reads, segments separated by dots
   * @param origin Where in the model it was read
   * @param verdict What the core's walk found
   */
  private void reportOneExpression(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String path,
      final io.vanillabp.camunda7.sync.Camunda7ExpressionIdentifiers.Origin origin,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict verdict) {

    if (verdict.segmentIndex() == 0) {
      log.warn(
          """
              Camunda7[{}]: the expression '{}' of element '{}' (BPMN process '{}' of workflow \
              module '{}') reads '{}', which IS an attribute of the workflow aggregate but is \
              NOT shared with the BPMS, so the engine holds no value of that name. {} Three \
              ways out, pick the one which applies: share the attribute (@SyncWithBPMS on its \
              getter); give it a readable getter if it has none, because the shared values are \
              read from getX() and from isX() returning boolean, never from a field and never \
              from an isX() returning something else (VanillaBP 1 read those, this version \
              does not); or let the expression read something the aggregate does share. Until \
              you do, VanillaBP 2.0 still answers this expression by reading the aggregate \
              directly, and that fallback will be removed.""",
          adapterId,
          origin.expression(),
          origin.elementId(),
          bpmnProcessId,
          workflowModuleId,
          verdict.segment(),
          whatTheEngineDoesWithTheNull(origin.placement()));
      return;
    }
    log.warn(
        """
            Camunda7[{}]: the expression '{}' of element '{}' (BPMN process '{}' of workflow \
            module '{}') reads the path '{}', and the BPMS holds nothing at that path: {} {} \
            Nothing answers this expression in the meantime: the migration fallback of \
            VanillaBP 2.0 reads the workflow aggregate for a top-level name only, so an \
            expression reading past the first dot is already answered with null.""",
        adapterId,
        origin.expression(),
        origin.elementId(),
        bpmnProcessId,
        workflowModuleId,
        path,
        whereThePathStops(verdict),
        whatTheEngineDoesWithTheNull(origin.placement()));

  }

  /**
   * Where the path stops finding anything, and what to do about it. Reads as one or two
   * sentences in the middle of the warning.
   *
   * @param verdict What the core's walk found
   * @return The sentences naming the segment and the way out
   */
  private static String whereThePathStops(
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.PathVerdict verdict) {

    return switch (verdict.kind()) {
      case NOT_SHARED -> """
          '%s' IS a readable attribute of '%s' and is NOT shared with the BPMS. Share it \
          (@SyncWithBPMS on its getter in '%s'), give it a readable getter if it has none, or let \
          the expression read something the aggregate does share.""".formatted(
          verdict.segment(),
          verdict.segmentOwner(),
          verdict.segmentOwner());
      case NO_SUCH_ATTRIBUTE -> """
          '%s' has no readable attribute '%s', so the values it shares carry no such member. \
          Either the model spells the name differently than the aggregate does, or the attribute \
          needs a getter ('%s' shares what getX() and isX() returning boolean answer, never a \
          field).""".formatted(
          verdict.segmentOwner(),
          verdict.segment(),
          verdict.segmentOwner());
      default -> """
          the segment before '%s' is a '%s', which reaches the BPMS as ONE value, a number or a \
          text, and therefore carries nothing below it (an enum arrives as its name, which is a \
          text as well). Let the expression read that value itself, or give the aggregate a \
          getter which answers what the expression wants and share that.""".formatted(
          verdict.segment(),
          verdict.segmentOwner());
    };

  }

  /**
   * What Camunda 7 does with the <code>null</code> which such an expression produces. The
   * placement decides it. Every sentence here was measured on an embedded engine 7.24.0.
   * {@code Camunda7NestedExpressionsIT} keeps the silent ones honest.
   *
   * @param placement Where in the model the expression sits
   * @return One sentence about the outcome
   */
  private static String whatTheEngineDoesWithTheNull(
      final io.vanillabp.camunda7.sync.Camunda7ExpressionIdentifiers.Placement placement) {

    return switch (placement) {
      case CONDITIONAL_EVENT -> """
          This is the condition of a CONDITIONAL EVENT, the placement Camunda 7 says nothing \
          about at all: the engine answers a condition it cannot evaluate with false, so the \
          event keeps waiting for good, without an incident and without a log line.""";
      case MULTI_INSTANCE_COMPLETION_CONDITION -> """
          This is the completion condition of a multi-instance element: a condition which is \
          never true lets every instance run, so the element ends the way it would without one \
          and nothing says why.""";
      case SEQUENCE_FLOW_CONDITION -> """
          This is a sequence flow condition and the element it leaves declares a default flow, \
          so the workflow quietly continues along that flow.""";
      case SEQUENCE_FLOW_CONDITION_WITHOUT_DEFAULT_FLOW -> """
          This is a sequence flow condition and the element it leaves declares no default flow, \
          so the engine finds no outgoing flow to continue on and raises an incident.""";
      case TIMER -> """
          This is a timer definition, which refuses the null out loud: the engine raises an \
          incident saying the timer was not configured with a valid duration or time.""";
      case MULTI_INSTANCE_CARDINALITY -> """
          This is the cardinality of a multi-instance element, which refuses the null out loud: \
          the engine raises an incident saying the expression has to be a number.""";
      case MULTI_INSTANCE_COLLECTION -> """
          This is the collection of a multi-instance element, which refuses the null out loud: \
          the engine raises an incident saying the expression did not resolve to a collection.""";
    };

  }

  /**
   * Reports a <code>&#64;WorkflowEnded</code> method which this adapter id will never
   * call. Camunda 7 CAN report the end of a workflow. So the only way to get here is a
   * missing wire between the platform module and the engine. This happened once: the
   * Quarkus producer did not hand the invoker over, and nothing said so. The deployment
   * does not fail over it. The workflow itself runs, only the notification is missing.
   * <p>
   * It is asked for every process this boot deploys. It is also asked for every BPMN
   * process id which the application only declares and the engine holds versions under. A
   * workflow of a renamed process ends like any other. Nothing else would speak about the
   * method kept for the old id.
   *
   * Visible for tests.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   */
  void warnAboutUnservedWorkflowEndedHandlers(
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (engineDeliversWorkflowEnded || (workflowEndedInvoker == null) || !workflowEndedInvoker
        .workflowEndedHandlerExists(workflowModuleId, bpmnProcessId)) {
      return;
    }
    log
        .warn(
            """
                A @WorkflowEnded method serves BPMN process '{}' of workflow module '{}', but the \
                Camunda 7 adapter '{}' did not attach its end listener - the method will never be \
                called although this engine could report the end of a workflow. This is a wiring \
                defect of the adapter, not of your application: please report it naming the \
                platform you run on (Spring Boot or Quarkus) and this adapter's version.""",
            bpmnProcessId,
            workflowModuleId,
            adapterId);

  }

  /**
   * Reports every start event of the process to the core, each with its kind, not only
   * the ones the engine fires on its own (timer, signal, conditional). The core validates the application's
   * <code>&#64;WorkflowStartedByBpms</code> methods against them. This method also remembers
   * the PLAIN signal names for the listener attached at parse time.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param scopedBpmnProcessId The process definition key the engine will know
   * @param model The BPMN model
   */
  private void wireBpmsInitiatedStarts(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final BpmnModelInstance model) {

    if (bpmsInitiatedStartInvoker == null) {
      return;
    }

    final var startEvents = startEventsOf(workflowModuleId, scopedBpmnProcessId, model);
    registerSignalStartEventsOf(workflowModuleId, scopedBpmnProcessId, startEvents);

    // throwing here follows the deployment-failure policy, like the task wiring
    bpmsInitiatedStartInvoker.validateBpmsInitiatedStarts(workflowModuleId, bpmnProcessId, startEvents);
    reportStartMessages(workflowModuleId, bpmnProcessId, scopedBpmnProcessId, model);

    if (!startEvents.isEmpty()) {
      log
          .info(
              "Camunda7[{}]: BPMN process '{}' (workflow module '{}') is started by the BPMS itself: {}",
              adapterId,
              bpmnProcessId,
              workflowModuleId,
              startEvents);
    }

  }

  /**
   * Tells the core which messages start this process. So the core can refuse a
   * <code>startWorkflowByMessage</code> whose message starts another process, before
   * anything is saved.
   * <p>
   * The names are reported PLAIN, the way the application passes them. Only the start events
   * which the process itself holds count. A message start event of an event subprocess
   * starts no workflow. A name which is an expression cannot be compared with what the
   * application passes. So a process with such a name is not reported at all, and the core
   * then does not check it. The correlation still names the process definition. So even
   * then this engine never starts another process (see {@code Camunda7ProcessService}).
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param scopedBpmnProcessId The process definition key the engine will know
   * @param model The BPMN model
   */
  private void reportStartMessages(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final BpmnModelInstance model) {

    final var messageNames = new java.util.LinkedHashSet<String>();
    final var messageStartEvents = model
        .getModelElementsByType(org.camunda.bpm.model.bpmn.instance.StartEvent.class)
        .stream()
        .filter(startEvent -> scopedBpmnProcessId.equals(owningProcessId(startEvent)))
        .filter(io.vanillabp.camunda7.wiring.Camunda7StartEvents::startsTheWorkflow)
        .filter(startEvent -> io.vanillabp.camunda7.wiring.Camunda7StartEvents
            .kindOf(startEvent) == io.vanillabp.spi.service.BpmsStartTrigger.Kind.MESSAGE)
        .toList();
    for (final var startEvent : messageStartEvents) {
      final var scopedMessageName = startEvent
          .getEventDefinitions()
          .stream()
          .filter(org.camunda.bpm.model.bpmn.instance.MessageEventDefinition.class::isInstance)
          .map(org.camunda.bpm.model.bpmn.instance.MessageEventDefinition.class::cast)
          .findFirst()
          .map(definition -> definition.getMessage() == null
              ? null
              : definition.getMessage().getName())
          .orElse(null);
      if ((scopedMessageName == null) || scopedMessageName.contains("${") || scopedMessageName.contains("#{")) {
        log
            .info(
                "Camunda7[{}]: message start event '{}' of BPMN process '{}' (workflow module '{}') has no "
                    + "message name this adapter can read, so the messages which start this process are not "
                    + "reported",
                adapterId,
                startEvent.getId(),
                bpmnProcessId,
                workflowModuleId);
        return;
      }
      messageNames.add(plainIdentifier(workflowModuleId, scopedMessageName));
    }

    bpmsInitiatedStartInvoker.reportStartMessages(adapterId, workflowModuleId, bpmnProcessId, messageNames);

  }

  /**
   * The start events of one BPMN process, read from a model. This works for a model which
   * this boot brings and for one which the engine holds.
   * <p>
   * EVERY start event of the process is reported, the plain one included. What a start of a
   * workflow means is read from the state of that workflow, not from the kind of its start
   * event (see decision 28 in the repository's DECISIONS.md). The kind travels along,
   * because the core demands a <code>&#64;WorkflowStartedByBpms</code> method only where
   * the engine fires the event by itself.
   * <p>
   * There is one walk for both directions. The core validates the
   * <code>&#64;WorkflowStartedByBpms</code> methods of a deployed process against it. It asks
   * the same of a version which the engine holds under an id nothing was deployed under. So
   * the two cannot disagree about what a start event is. A signal name is reported PLAIN,
   * because the application above this boundary knows nothing about name-clash avoidance.
   * <p>
   * Only the start events which the process itself holds are read. An event subprocess
   * starts no workflow, which
   * {@link io.vanillabp.camunda7.wiring.Camunda7StartEvents#startsTheWorkflow(org.camunda.bpm.model.bpmn.instance.StartEvent)}
   * says more about.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @param model The BPMN model
   * @return The start events, in the order the model lists them
   */
  private List<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec> startEventsOf(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final BpmnModelInstance model) {

    final var startEvents = new LinkedList<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec>();
    model
        .getModelElementsByType(org.camunda.bpm.model.bpmn.instance.StartEvent.class)
        .stream()
        .filter(startEvent -> scopedBpmnProcessId.equals(owningProcessId(startEvent)))
        .filter(io.vanillabp.camunda7.wiring.Camunda7StartEvents::startsTheWorkflow)
        .forEach(startEvent -> {
          final var kind = io.vanillabp.camunda7.wiring.Camunda7StartEvents.kindOf(startEvent);
          if (kind != io.vanillabp.spi.service.BpmsStartTrigger.Kind.SIGNAL) {
            startEvents
                .add(
                    io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec
                        .of(startEvent.getId(), kind));
            return;
          }
          // the model carries the SCOPED signal name where identifiers are prefixed. The
          // application is told the plain one
          final var scopedSignalName = startEvent
              .getEventDefinitions()
              .stream()
              .filter(org.camunda.bpm.model.bpmn.instance.SignalEventDefinition.class::isInstance)
              .map(org.camunda.bpm.model.bpmn.instance.SignalEventDefinition.class::cast)
              .findFirst()
              .map(definition -> definition.getSignal() == null
                  ? null
                  : definition.getSignal().getName())
              .orElse(null);
          startEvents
              .add(
                  new io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec(
                      startEvent.getId(), kind, plainIdentifier(workflowModuleId, scopedSignalName)));
        });
    return startEvents;

  }

  /**
   * The start events of a model which the engine still holds. The core reads them to judge
   * the <code>&#64;WorkflowStartedByBpms</code> methods kept for a BPMN process id which the
   * application declares without deploying anything under it.
   * <p>
   * Nothing wires such an id while this application boots. So nothing judges those methods
   * unless the old models are read. Meanwhile the engine keeps firing the old version's
   * timer and keeps matching its signal subscription.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The version the engine assigned
   * @param model The model of that version
   * @return The start events of that version
   */
  private java.util.Collection<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec> startEventsOfHeldModel(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final BpmnModelInstance model) {

    return startEventsOf(workflowModuleId, scopedProcessId(workflowModuleId, bpmnProcessId), model);

  }

  /**
   * Remembers the PLAIN signal names of the signal start events of a model. The listener
   * attached at parse time asks for them once such a start fires.
   * <p>
   * A model which the engine holds needs this as much as one which this boot deploys.
   * Nothing was deployed under a declared id, so nobody registered its signals. But a
   * workflow which the engine starts under that id has to be told which signal fired.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedBpmnProcessId The process definition key the engine knows
   * @param startEvents What {@link #startEventsOf} read from that model
   */
  private void registerSignalStartEventsOf(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final List<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec> startEvents) {

    startEvents
        .stream()
        .filter(startEvent -> startEvent.kind() == io.vanillabp.spi.service.BpmsStartTrigger.Kind.SIGNAL)
        .forEach(startEvent -> taskRegistry
            .registerSignalStartEvent(
                workflowModuleId, scopedBpmnProcessId, startEvent.elementId(), startEvent.signalName()));

  }

  /**
   * Removes the workflow module's prefix from an identifier the model carries. So the
   * application sees what it modelled. Without scoping, or without a prefix, the
   * identifier is returned unchanged.
   *
   * @param workflowModuleId The workflow module ID
   * @param scopedIdentifier The identifier as the model carries it
   * @return The plain identifier
   */
  private String plainIdentifier(
      final String workflowModuleId,
      final String scopedIdentifier) {

    if ((scoping == null) || (scopedIdentifier == null)) {
      return scopedIdentifier;
    }
    return scoping.plainIdentifier(workflowModuleId, scopedIdentifier, adapterId);

  }

  @Override
  public void deployResources(
      final String workflowModuleId,
      final Camunda7ProcessingContext bpmsProcessingContext) throws IllegalStateException {

    // the core skips a module without an executable BPMN process before it gets here, and
    // it never passes null. The check stays for a caller which does not keep to that
    if (bpmsProcessingContext == null || bpmsProcessingContext.isEmpty()) {
      log.debug(
          "Camunda7[{}]: no BPMN resources to deploy for workflow module '{}'",
          adapterId,
          workflowModuleId);
      return;
    }

    // one deployment per workflow module. Duplicate filtering avoids redeploying unchanged
    // models. Whether the module is isolated by a tenant is the mode's decision: only
    // by-adapter uses one, named after the workflow module unless tenant-id renames it
    validateTenantConfiguration(workflowModuleId);
    final var tenantId = tenantIdOf(workflowModuleId);
    if (tenantId != null) {
      Camunda7TenantCheck.warnAboutUnregisteredTenant(adapterId, workflowModuleId, tenantId, identityService);
    }
    if (scoping != null) {
      scoping.validateNoCollidingProcessIds(
          adapterId,
          bpmsProcessingContext
              .getDeployedProcessIds()
              .stream()
              .map(processId -> new io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.DeployedProcess(
                  workflowModuleId, processId))
              .toList());
    }
    var deploymentBuilder = repositoryService
        .createDeployment()
        .name(workflowModuleId)
        .source(ADAPTER_TYPE
            + ":"
            + adapterId)
        .enableDuplicateFiltering(true);
    if (tenantId != null) {
      deploymentBuilder = deploymentBuilder.tenantId(tenantId);
    }

    bpmsProcessingContext
        .getResourcesByFilename()
        .forEach(deploymentBuilder::addModelInstance);
    // the module's decision tables go into the SAME deployment. So a business rule task
    // which binds its decision to the deployment finds it, and both are versioned together
    final var builderWithProcesses = deploymentBuilder;
    bpmsProcessingContext
        .getDecisionsByFilename()
        .forEach((
            filename,
            dmn) -> builderWithProcesses
                .addInputStream(filename, new java.io.ByteArrayInputStream(dmn)));

    // deployWithResult reports the definitions which the engine created, i.e. the version
    // it assigned to every model deployed now. They feed the version catalog. So the
    // version deployed by THIS boot needs no query at all
    final var deployment = deploymentBuilder.deployWithResult();
    final var deployedDefinitions = deployment.getDeployedProcessDefinitions();
    if (deployedDefinitions != null) {
      deployedDefinitions
          .forEach(definition -> {
            final var plainBpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, definition.getKey());
            processVersions
                .recordDeployed(
                    workflowModuleId,
                    plainBpmnProcessId,
                    definition.getId(),
                    definition.getVersion(),
                    definition.getVersionTag());
            // The border between the model this boot brought and the older
            // versions the engine still holds
            workflowTaskWiring
                .registerDeployedVersion(
                    adapterId,
                    workflowModuleId,
                    plainBpmnProcessId,
                    String.valueOf(definition.getVersion()));
            // and whether the engine holds workflows of it where this
            // configuration will never look
            Camunda7TenantCheck
                .warnAboutWorkflowsOutOfScope(
                    adapterId,
                    workflowModuleId,
                    plainBpmnProcessId,
                    definition.getKey(),
                    tenantId,
                    runtimeService);
          });
    }

    // Camunda deploys nothing when the resources did not change. So a restart without a
    // model change reports no definitions at all. The version this application runs on is
    // then the engine's latest one. The old-versions check needs it on EVERY boot, not only
    // on the one which changed something.
    bpmsProcessingContext
        .getDeployedProcessIds()
        .stream()
        .filter(bpmnProcessId -> processVersions.deployedVersionOf(workflowModuleId, bpmnProcessId) == null)
        .forEach(bpmnProcessId -> {
          final var latest = latestVersionOf(workflowModuleId, bpmnProcessId);
          if (latest != null) {
            processVersions
                .recordDeployed(
                    workflowModuleId,
                    bpmnProcessId,
                    latest.getId(),
                    latest.getVersion(),
                    latest.getVersionTag());
            workflowTaskWiring
                .registerDeployedVersion(
                    adapterId, workflowModuleId, bpmnProcessId, String.valueOf(latest.getVersion()));
          }
        });

    final var deployedDecisions = deployment.getDeployedDecisionDefinitions();
    log.info(
        "Camunda7[{}]: deployed {} BPMN resource(s) and {} decision(s) of workflow module '{}' "
            + "(tenant '{}') as deployment '{}'",
        adapterId,
        bpmsProcessingContext.getResourcesByFilename().size(),
        bpmsProcessingContext.getDecisionsByFilename().size(),
        workflowModuleId,
        tenantId != null
            ? tenantId
            : "<none>",
        deployment.getId());
    if ((deployedDecisions != null) && !deployedDecisions.isEmpty()) {
      // the ids the ENGINE knows. A business rule task has to name these
      log.info(
          "Camunda7[{}]: the decisions of workflow module '{}' are known to the engine as {}",
          adapterId,
          workflowModuleId,
          deployedDecisions
              .stream()
              .map(decision -> "%s (version %d)".formatted(decision.getKey(), decision.getVersion()))
              .toList());
    }

    reportAboutTheNamesThisModuleDeploys(workflowModuleId, bpmsProcessingContext, tenantId);

    // and what the listeners somebody modelled cost this workflow module
    reportWhatListenersCost(workflowModuleId, bpmsProcessingContext);

  }

  /**
   * Says which of this workflow module's identifiers the engine held before this deployment.
   * It also says which of them a second workflow module of this application declares as well.
   * <p>
   * Both are diagnostics of the start. Nothing is kept, and no runtime path reads any of it.
   * A finding is a warning which the core words. The deployment on the other side may belong
   * to an application which runs correctly (see decision 17 in the repository's
   * DECISIONS.md). It runs after the deploy command returned. At that point the process
   * definition keys of this module are settled, and the tenant checks run there as well.
   *
   * @param workflowModuleId The workflow module which was just deployed
   * @param bpmsProcessingContext What the deployment pipeline collected for it
   * @param tenantId The tenant deployed into, <code>null</code> for none
   */
  private void reportAboutTheNamesThisModuleDeploys(
      final String workflowModuleId,
      final Camunda7ProcessingContext bpmsProcessingContext,
      final String tenantId) {

    if (scoping == null) {
      return;
    }
    scoping
        .reportIdentifiersTheModelsDeclare(
            adapterId, workflowModuleId, bpmsProcessingContext.getDeclaredIdentifiers());

    final var processIdsByKey = new java.util.LinkedHashMap<String, String>();
    bpmsProcessingContext
        .getDeployedProcessIds()
        .forEach(
            bpmnProcessId -> processIdsByKey
                .put(scopedProcessId(workflowModuleId, bpmnProcessId), bpmnProcessId));
    final var decisionIdsByKey = new java.util.LinkedHashMap<String, String>();
    bpmsProcessingContext
        .getDecisionIds()
        .forEach(
            decisionId -> decisionIdsByKey
                .put(scoping.scopedIdentifier(workflowModuleId, decisionId, adapterId), decisionId));
    Camunda7IdentifiersTheEngineHolds
        .reportWhatTheEngineAlreadyHolds(
            adapterId,
            workflowModuleId,
            processIdsByKey,
            decisionIdsByKey,
            tenantId,
            repositoryService,
            scoping);

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Camunda7ProcessingContext bpmsProcessingContext) {

    // the workflows of a renamed BPMN process are served by the models which the engine
    // still holds under the old id. This has to happen before the executor may hand any of
    // their tasks out
    wireTheProcessesNobodyDeployed(workflowModuleId);

    // a version deployed by an earlier generation of the application may carry a standard
    // loop. The workflows on it repeat nothing
    warnAboutStandardLoopsOfHeldVersions(workflowModuleId);

    // asynchronous continuations (async-before/after, timers) run on the engine's job
    // executor. It is only started at this point. The platform builds the engine with the
    // executor inactive
    log.info(
        "Camunda7[{}]: starting workflow processing of module '{}'",
        adapterId,
        workflowModuleId);
    workflowProcessingLifecycle.startWorkflowProcessing(workflowModuleId);

  }

  /**
   * Wires the tasks of the models which the engine still holds under a BPMN process id that
   * the application DECLARES without deploying anything under it. This is the old id of a
   * renamed process, with the workflows which still run on it.
   * <p>
   * Camunda 7 evaluates the expressions of the model a workflow was STARTED with. So such a
   * workflow does not need a subscription. It needs the connectables of ITS model: the
   * expression text of every task, keyed by the process id the engine reports and by the
   * element the expression is evaluated at. That model is right here, in the engine's own
   * repository. Reading it tells a task which completes when its expression returns from one
   * which stays open. Nothing outside a model can be asked about that difference. So what
   * the core names as served is not enough on its own here. It says what to compose an
   * identifier from, and a connectable is more than an identifier.
   * <p>
   * Every version which the engine holds is wired, because a workflow may sit on any of
   * them. A task which two versions share is registered once. This is also where the id gets
   * the warning about a <code>&#64;WorkflowEnded</code> method which this engine cannot
   * serve, because no model of this boot passes by such an id. The wiring validation is NOT
   * run over those models. They were deployed by an earlier generation of this application.
   * A task which the application dropped in the meantime is for the core's startup check to
   * report. Ending the boot over a model which nobody can change any more would be the wrong
   * answer.
   *
   * @param workflowModuleId The workflow module which is about to process workflows
   */
  private void wireTheProcessesNobodyDeployed(
      final String workflowModuleId) {

    workflowTaskWiring
        .taskWiringOfProcessesNobodyDeployed(workflowModuleId)
        .keySet()
        .forEach(bpmnProcessId -> wireTheVersionsHeldUnder(workflowModuleId, bpmnProcessId));

  }

  /**
   * Wires every version the engine holds under one declared BPMN process id and says what
   * came of it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID nothing was deployed under
   */
  private void wireTheVersionsHeldUnder(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
    final var definitionIdsByVersion = processVersions.definitionIdsHeldUnder(workflowModuleId, bpmnProcessId);
    if (definitionIdsByVersion.isEmpty()) {
      // either the last workflow of the old id ended and the engine forgot the
      // definitions, or the declared id is misspelled. The core's check says which, and it
      // names the ids this module deploys
      log.debug(
          "Camunda7[{}]: the engine holds no process definition under the declared BPMN process '{}' "
              + "of workflow module '{}', so there is nothing of it to wire",
          adapterId,
          bpmnProcessId,
          workflowModuleId);
      return;
    }
    // the way back from the engine's definition key has to exist BEFORE any of those
    // models is read. Reading one makes the engine PARSE the definition. The parse
    // listener uses exactly this registration to decide whether the end of such a
    // workflow is reported (Camunda7AsyncBpmnParseListener#parseProcess). It also serves
    // a process without any task. The version of an execution and the workflow module it
    // belongs to are read from here
    registerTheWayBackFromTheEngine(workflowModuleId, bpmnProcessId, scopedBpmnProcessId);

    // the workflows of those versions end like any other. So the application is told here
    // as well when this engine cannot deliver the end to a @WorkflowEnded method which it
    // kept for the old id. No model of this boot passes by such an id, so nothing else
    // says it
    warnAboutUnservedWorkflowEndedHandlers(workflowModuleId, bpmnProcessId);

    final var distinctConnectables = new java.util.LinkedHashMap<String, Camunda7TaskConnectable>();
    definitionIdsByVersion
        .forEach((
            version,
            definitionId) -> wireTheModelOf(
                workflowModuleId,
                bpmnProcessId,
                scopedBpmnProcessId,
                version,
                definitionId,
                distinctConnectables));
    distinctConnectables.values().forEach(taskRegistry::register);
    log.info(
        "Camunda7[{}]: wired {} task(s) of the {} version(s) the engine holds under the declared BPMN "
            + "process '{}' (workflow module '{}'), so the workflows still running on them keep being "
            + "served",
        adapterId,
        distinctConnectables.size(),
        definitionIdsByVersion.size(),
        bpmnProcessId,
        workflowModuleId);

  }

  /**
   * The BPMN processes of each workflow module this boot wired a model for, by their PLAIN
   * ids. The versions the engine holds under them are read once the module starts.
   */
  private final Map<String, java.util.Set<String>> processesWiredByModule = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Warns about each version which the engine holds, carries a standard loop and still has
   * running workflows. Such a model was deployed before this adapter refused it, by version 1
   * or by an older snapshot. Nobody can change it any more. So this is a warning, not a
   * refusal. A version which no workflow runs on any more is passed over, because it cannot
   * do any harm.
   * <p>
   * The models are read first. Workflows are counted only where a model carries the marker.
   * The engine caches a model it was asked for, and the core's startup check reads the same
   * versions anyway.
   *
   * @param workflowModuleId The workflow module which is about to process workflows
   */
  private void warnAboutStandardLoopsOfHeldVersions(
      final String workflowModuleId) {

    final var bpmnProcessIds = new java.util.TreeSet<String>(
        processesWiredByModule.getOrDefault(workflowModuleId, java.util.Set.of()));
    bpmnProcessIds.addAll(workflowTaskWiring.taskWiringOfProcessesNobodyDeployed(workflowModuleId).keySet());
    for (final var bpmnProcessId : bpmnProcessIds) {
      final var scopedBpmnProcessId = scopedProcessId(workflowModuleId, bpmnProcessId);
      processVersions
          .definitionIdsHeldUnder(workflowModuleId, bpmnProcessId)
          .forEach((
              version,
              definitionId) -> {
            final var standardLoops = Camunda7StandardLoops
                .elementIdsOf(repositoryService.getBpmnModelInstance(definitionId), scopedBpmnProcessId);
            if (standardLoops.isEmpty()) {
              return;
            }
            final var running = processVersions.activeInstanceCountOf(workflowModuleId, bpmnProcessId, version);
            if ((running != null) && (running == 0L)) {
              return;
            }
            log.warn(
                "Camunda7[{}]: {}",
                adapterId,
                Camunda7StandardLoops
                    .warningAboutAHeldVersion(standardLoops, version, bpmnProcessId, workflowModuleId, running));
          });
    }

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId,
      final Camunda7ProcessingContext bpmsProcessingContext) {

    // graceful shutdown (reverse start order). The executor stops once the LAST started
    // module stops (see Camunda7WorkflowProcessingLifecycle)
    log.info(
        "Camunda7[{}]: stopping workflow processing of module '{}'",
        adapterId,
        workflowModuleId);
    workflowProcessingLifecycle.stopWorkflowProcessing(workflowModuleId);

  }

}
