package io.vanillabp.camunda7.quarkus.runtime;

import java.util.List;
import java.util.Map;

import io.vanillabp.camunda7.Camunda7Adapter;
import io.vanillabp.camunda7.deployment.Camunda7DeploymentService;
import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces the Camunda 7 adapter's per-adapter-id beans on Quarkus - ONE
 * {@link Camunda7ProcessService} and ONE {@link Camunda7DeploymentService} per
 * configured adapter id of type {@code camunda7}, each wired to ITS engine from the
 * {@link Camunda7QuarkusEngineRegistry} (the per-adapter-id shape: a CDI producer
 * cannot yield N element beans for N runtime-configured ids, so ONE bean of type
 * <code>List&lt;...&gt;</code> is produced per SPI).
 * <p>
 * Platform contract: the List's element type is the SPI interface with the type
 * parameters literally {@code Object} - CDI's parameterized-type matching of
 * differing type arguments is not reliable across modes, so the platform looks the
 * beans up with the exact type. The producer methods are {@code @Singleton}
 * (deployment services are not client-proxyable).
 */
@ApplicationScoped
public class Camunda7AdapterProducer {

  /**
   * Quarkus builds the bean to call the producers below. It keeps no state: what they
   * return are beans of their own and live as long as the application does.
   */
  public Camunda7AdapterProducer() {

  }

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
      .getLogger(Camunda7AdapterProducer.class);

  /**
   * What the platform hands the adapter, built the same way for both services of an
   * adapter id.
   */
  private static io.vanillabp.integration.adapter.spi.AdapterCollaborators collaboratorsOf(
      final String adapterId,
      final io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry workflowTaskRegistry,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync,
      final io.vanillabp.integration.adapter.spi.PreCommitRegistrar preCommitRegistrar,
      final jakarta.enterprise.inject.Instance<io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker> workflowEndedInvoker,
      final jakarta.enterprise.inject.Instance<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker> bpmsInitiatedStartInvoker) {

    return io.vanillabp.integration.runtime.support.AdapterCollaboratorsSupport
        .collaborators(
            adapterId, workflowTaskRegistry, workflowTaskRegistry, scoping, aggregateSync, preCommitRegistrar,
            workflowEndedInvoker, bpmsInitiatedStartInvoker);

  }

  /**
   * One process service per configured adapter id of this type, each on the engine that id
   * owns.
   *
   * @param properties The platform's own configuration, which is where the adapter ids
   *          come from
   * @param engineRegistry The engines built at startup, one per adapter id
   * @param aggregateSync Which aggregate values travel to the engine
   * @param overlay This adapter's own keys of the shared configuration tree
   * @param scoping How an identifier is kept apart from the one of another workflow module
   * @param preCommitRegistrar Where a phase-one check is run right before the commit
   * @param workflowTaskRegistry What the core knows about the application's methods
   * @param workflowEndedInvoker The core's notification of a workflow which ended, if the
   *          application has a method for it
   * @param bpmsInitiatedStartInvoker The core's notification of a workflow the engine
   *          started on its own
   * @return The process services, one per configured adapter id
   */
  @Produces
  @Singleton
  public List<MigratableProcessService<Object>> camunda7MigratableProcessServices(
      final MigrationAdapterProperties properties,
      final Camunda7QuarkusEngineRegistry engineRegistry,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync,
      final VanillaBpCamunda7Properties overlay,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping,
      final io.vanillabp.integration.adapter.spi.PreCommitRegistrar preCommitRegistrar,
      final io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry workflowTaskRegistry,
      @jakarta.enterprise.inject.Any final jakarta.enterprise.inject.Instance<io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker> workflowEndedInvoker,
      @jakarta.enterprise.inject.Any final jakarta.enterprise.inject.Instance<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker> bpmsInitiatedStartInvoker) {

    return camunda7AdapterIds(properties)
        .stream()
        .<MigratableProcessService<Object>>map(adapterId -> {
          final var engine = engineRegistry.engineFor(adapterId);
          final var processService = new Camunda7ProcessService<>(
              adapterId, engine.getRuntimeService(), engine.getTaskService(), engine.getRepositoryService(), engine
                  .getHistoryService(), collaboratorsOf(
                      adapterId, workflowTaskRegistry, scoping, aggregateSync, preCommitRegistrar,
                      workflowEndedInvoker, bpmsInitiatedStartInvoker));
          // an engine on a datasource of its own commits separately from the
          // application, which makes its deliveries repeatable
          processService.setEngineRunsOnItsOwnDataSource(engine.usesSeparateDataSource());
          // a start reports the version of its process definition from the cache the
          // deliveries of this engine read it from
          processService.setTaskRegistry(engine.getTaskRegistry());
          processService.setConfiguredTenants(configuredTenantsOf(overlay, adapterId));
          // Which serialization format nested shared values are stored in,
          // resolved per workflow with a fallback to the module and the adapter
          final io.vanillabp.camunda7.sync.Camunda7SerializationFormats formats = (
              workflowModuleId,
              bpmnProcessId) -> serializationFormatOf(overlay, adapterId, workflowModuleId, bpmnProcessId);
          processService.setSerializationFormats(formats);
          engine
              .getTaskRegistry()
              .setSerializationFormats(formats);
          return processService;
        })
        .toList();

  }

  /**
   * One deployment service per configured adapter id of this type, as the list the platform
   * looks the beans up as.
   *
   * @param properties The platform's own configuration, which is where the adapter ids
   *          come from
   * @param engineRegistry The engines built at startup, one per adapter id
   * @param workflowTaskRegistry What the core knows about the application's methods
   * @param overlay This adapter's own keys of the shared configuration tree
   * @param aggregateSync Which aggregate values travel to the engine
   * @param scoping How an identifier is kept apart from the one of another workflow module
   * @param preCommitRegistrar Where a phase-one check is run right before the commit
   * @param workflowEndedInvoker The core's notification of a workflow which ended, if the
   *          application has a method for it
   * @param bpmsInitiatedStartInvoker The core's notification of a workflow the engine
   *          started on its own
   * @return The deployment services, one per configured adapter id
   */
  @Produces
  @Singleton
  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  public List<AdapterDeploymentService<Object, Object>> camunda7AdapterDeploymentServices(
      final MigrationAdapterProperties properties,
      final Camunda7QuarkusEngineRegistry engineRegistry,
      final WorkflowTaskRegistry workflowTaskRegistry,
      final VanillaBpCamunda7Properties overlay,
      final io.vanillabp.integration.adapter.spi.WorkflowAggregateSync aggregateSync,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping,
      final io.vanillabp.integration.adapter.spi.PreCommitRegistrar preCommitRegistrar,
      @jakarta.enterprise.inject.Any final jakarta.enterprise.inject.Instance<io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker> workflowEndedInvoker,
      @jakarta.enterprise.inject.Any final jakarta.enterprise.inject.Instance<io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker> bpmsInitiatedStartInvoker) {

    return (List) camunda7AdapterIds(properties)
        .stream()
        .map(adapterId -> {
          final var engine = engineRegistry.engineFor(adapterId);
          final var deploymentService = new Camunda7DeploymentService(
              adapterId, engine.getRepositoryService(), engine, collaboratorsOf(
                  adapterId, workflowTaskRegistry, scoping, aggregateSync, preCommitRegistrar, workflowEndedInvoker,
                  bpmsInitiatedStartInvoker), engine.getTaskRegistry(), id -> instanceIdentityOf(overlay, id));
          deploymentService.setEngineDeliversWorkflowEnded(engine.deliversWorkflowEnded());
          deploymentService.setConfiguredTenants(configuredTenantsOf(overlay, adapterId));
          deploymentService.setIdentityService(
              engine
                  .getProcessEngine()
                  .getIdentityService());
          // How many workflows still run on an older version
          deploymentService.setRuntimeService(
              engine
                  .getProcessEngine()
                  .getRuntimeService());
          deploymentService.setAcceptUnscopedIdentifiers(acceptUnscopedIdentifiersOf(overlay, adapterId));
          // Which format a workflow's values travel in, and what this engine's
          // serializers make of them - the startup check reads both
          deploymentService.setSerializationFormats(
              (
                  workflowModuleId,
                  bpmnProcessId) -> serializationFormatOf(overlay, adapterId, workflowModuleId, bpmnProcessId));
          deploymentService.setSerializationRoundTrip(
              io.vanillabp.camunda7.sync.Camunda7SerializationRoundTrip.of(engine.getProcessEngine()));
          // Whether the listeners somebody modelled are served by this application, resolvable
          // down to the workflow
          deploymentService.setAllowListenersResolver(
              (
                  workflowModuleId,
                  bpmnProcessId) -> overlay.allowListenersFor(adapterId, workflowModuleId, bpmnProcessId));
          // a key at a level which does not resolve it changes nothing and would be silent,
          // which is worse than a line saying where the key is read
          io.vanillabp.camunda7.wiring.Camunda7Listeners.reportKeysSetAtTaskLevel(
              adapterId,
              overlay.allowListenersKeysAtTaskLevel(adapterId),
              log::warn);
          return deploymentService;
        })
        .toList();

  }

  /**
   * What this adapter knows about its engines, for an extension running inside one: ONE
   * {@link io.vanillabp.camunda7.api.Camunda7EngineFacts} per configured adapter id, built
   * from what the engine wiring computed anyway rather than from a second reading of the
   * configuration. The list shape is the per-adapter-id shape of this platform (see the
   * class comment); an extension picks the entry whose
   * {@code Camunda7EngineFacts#adapterId()} is the one it is dealing with.
   *
   * @param properties The core's adapter configuration
   * @param engineRegistry The engines of this application
   * @param overlay This adapter's overlay of the shared <code>vanillabp</code> tree
   * @param scoping The core's name-clash avoidance
   * @return One entry per configured <code>camunda7</code> adapter id
   */
  @Produces
  @Singleton
  public List<io.vanillabp.camunda7.api.Camunda7EngineFacts> camunda7EngineFacts(
      final MigrationAdapterProperties properties,
      final Camunda7QuarkusEngineRegistry engineRegistry,
      final VanillaBpCamunda7Properties overlay,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping) {

    return camunda7AdapterIds(properties)
        .stream()
        .map(adapterId -> new io.vanillabp.camunda7.api.Camunda7EngineFacts(
            adapterId, scoping, configuredTenantsOf(overlay, adapterId), engineRegistry
                .engineFor(adapterId)
                .getTaskRegistry()))
        .toList();

  }

  private static List<String> camunda7AdapterIds(
      final MigrationAdapterProperties properties) {

    return properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> Camunda7Adapter.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();

  }


  /**
   * What makes an adapter id a distinct engine: its datasource and table prefix
   * (see {@code Camunda7InstanceIdentity}) - the adapter SPI hook
   * {@code validateDistinctAdapterInstances} compares them.
   */
  private static io.vanillabp.camunda7.engine.Camunda7InstanceIdentity instanceIdentityOf(
      final VanillaBpCamunda7Properties overlay,
      final String adapterId) {

    final var keys = overlay
        .adapters()
        .get(adapterId);
    return new io.vanillabp.camunda7.engine.Camunda7InstanceIdentity(
        keys == null
            ? null
            : keys
                .dataSourceName()
                .orElse(null), keys == null
                    ? null
                    : keys
                        .tablePrefix()
                        .orElse(null));

  }


  /**
   * The serialization format configured for one workflow, most specific first: the
   * workflow, its workflow module, the adapter.
   *
   * @param overlay The adapter's configuration overlay
   * @param adapterId The adapter id
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The format or <code>null</code> where none is configured
   */
  private static String serializationFormatOf(
      final VanillaBpCamunda7Properties overlay,
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var module = workflowModuleId != null
        ? overlay
            .workflowModules()
            .get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module
            .workflows()
            .get(bpmnProcessId)
        : null;
    return io.vanillabp.camunda7.sync.Camunda7SerializationFormats
        .firstConfigured(
            scopedFormat(workflow != null
                ? workflow.adapters()
                : null, adapterId),
            scopedFormat(module != null
                ? module.adapters()
                : null, adapterId),
            overlay
                .adapters()
                .containsKey(adapterId)
                    ? overlay
                        .adapters()
                        .get(adapterId)
                        .serializationFormat()
                        .orElse(null)
                    : null);

  }

  /**
   * The format of one scope's adapter section, or <code>null</code>.
   */
  private static String scopedFormat(
      final java.util.Map<String, ? extends VanillaBpCamunda7Properties.Camunda7ScopedKeys> adapters,
      final String adapterId) {

    final var scoped = adapters != null
        ? adapters.get(adapterId)
        : null;
    return scoped != null
        ? scoped
            .serializationFormat()
            .orElse(null)
        : null;

  }

  /**
   * The acknowledgement that identifiers are unique across workflow modules
   * (<code>accept-unscoped-identifiers</code>), <code>false</code> if unset.
   */
  private static boolean acceptUnscopedIdentifiersOf(
      final VanillaBpCamunda7Properties overlay,
      final String adapterId) {

    final var adapter = overlay
        .adapters()
        .get(adapterId);
    return (adapter != null) && adapter
        .acceptUnscopedIdentifiers()
        .orElse(Boolean.FALSE)
        .booleanValue();

  }

  /**
   * What a workflow module's tenant is configured as, per workflow module
   * (<code>vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.tenant-id</code>)
   * with a fallback to the adapter (<code>vanillabp.adapters.&lt;id&gt;.tenant-id</code>) -
   * <code>null</code> for a module neither names one for, which the workflow module id then
   * names.
   */
  private static java.util.function.Function<String, io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant> configuredTenantsOf(
      final VanillaBpCamunda7Properties overlay,
      final String adapterId) {

    return workflowModuleId -> {
      final var module = workflowModuleId != null
          ? overlay
              .workflowModules()
              .get(workflowModuleId)
          : null;
      final var perWorkflowModule = module != null
          ? module
              .adapters()
              .get(adapterId)
          : null;
      final var adapter = overlay
          .adapters()
          .get(adapterId);
      return io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant
          .firstConfigured(
              adapterId,
              workflowModuleId,
              perWorkflowModule != null
                  ? perWorkflowModule
                      .tenantId()
                      .orElse(null)
                  : null,
              adapter != null
                  ? adapter
                      .tenantId()
                      .orElse(null)
                  : null);
    };

  }

}
