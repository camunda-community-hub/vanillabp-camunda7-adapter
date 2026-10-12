package io.vanillabp.camunda7.springboot;

import java.util.LinkedList;
import java.util.List;

import javax.sql.DataSource;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.camunda7.deployment.Camunda7DeploymentService;
import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.camunda7.engine.Camunda7InstanceIdentity;
import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;

/**
 * Registers the beans of the Camunda 7 adapter, per adapter id. Several ids of one BPMS
 * type are the migration scenario, e.g. two embedded engines side by side on separate
 * schemas. For EACH configured adapter id of type {@code camunda7} it registers:
 * <ul>
 * <li>one {@link Camunda7EngineHolder} bean. It owns THAT id's embedded engine, named
 * <code>vanillabp-camunda7-&lt;id&gt;</code>. It also runs the lifecycle of the job
 * executor and, if there is one, of the id's own datasource.</li>
 * <li>one {@link Camunda7ProcessService} <i>element</i> bean and one
 * {@link Camunda7DeploymentService} <i>element</i> bean.</li>
 * <li>named convenience beans {@link ProcessEngine}, {@link RuntimeService} and
 * {@link RepositoryService}.</li>
 * <li>one {@link io.vanillabp.camunda7.api.Camunda7EngineFacts} bean. An extension of this
 * adapter asks it about the engine it runs in.</li>
 * </ul>
 * It never registers beans of type {@code List<...>}. The platform collects element beans
 * through {@code ObjectProvider.stream()}.
 * <p>
 * The set of ids comes from the runtime configuration. So the beans are registered in
 * code, through {@link BeanRegistrar} and
 * {@link AdapterBeanRegistrarSupport#forEachConfiguredAdapterId}. The adapter id is a
 * CONSTRUCTOR parameter of each instance. Without a configured {@code camunda7} adapter id
 * no bean is registered at all. So an application which only has the adapter jar on the
 * classpath gets no {@code ACT_*} tables and no job executor.
 * <p>
 * <b>Startup validation:</b> two embedded engines on one schema share the same engine
 * state. The adapter SPI hook
 * {@code AdapterDeploymentService#validateDistinctAdapterInstances} checks that. It is
 * implemented ONCE, in {@link Camunda7DeploymentService}, for both platforms. Adapter ids
 * which share one datasource AND one table prefix fail the boot with a guiding message. An
 * application which provides SEVERAL datasources also has to name the one an adapter id
 * runs on (see {@link #applicationBean}).
 */
public class Camunda7AdapterBeanRegistrar implements BeanRegistrar {

  /**
   * Spring Boot builds the class when the auto-configuration imports it. It holds nothing.
   * It reads the adapter ids from the environment while it registers.
   */
  public Camunda7AdapterBeanRegistrar() {

  }

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
      .getLogger(Camunda7AdapterBeanRegistrar.class);

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    final var camunda7AdapterIds = new LinkedList<String>();
    AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(
        environment,
        Camunda7AdapterConfiguration.ADAPTER_TYPE,
        camunda7AdapterIds::add);

    camunda7AdapterIds
        .forEach(adapterId -> {

          registry.registerBean(
              "Camunda7_Engine_%s".formatted(adapterId),
              Camunda7EngineHolder.class,
              spec -> spec.supplier(supplierContext -> {
                final var engineProperties = enginePropertiesFor(environment, adapterId);
                // the application's DEFAULT DataSource and PlatformTransactionManager
                // are only needed, and only have to exist, if the id names no
                // datasource bean (data-source-name)
                final var usesNamedDataSource = engineProperties.usesSeparateDataSource();
                return new Camunda7EngineHolder(
                    adapterId, engineProperties, usesNamedDataSource
                        ? null
                        : applicationBean(supplierContext, DataSource.class, adapterId), usesNamedDataSource
                            ? null
                            : applicationBean(supplierContext, PlatformTransactionManager.class,
                                adapterId), supplierContext
                                    .bean(WorkflowTaskInvoker.class), supplierContext
                                        .beanProvider(
                                            io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker.class)
                                        .getIfAvailable(), supplierContext
                                            .beanProvider(
                                                io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker.class)
                                            .getIfAvailable());
              }));

          registry.registerBean(
              "Camunda7_ProcessService_%s".formatted(adapterId),
              Camunda7ProcessService.class,
              spec -> spec.supplier(supplierContext -> {
                final var engine = engineHolder(supplierContext, adapterId);
                final var processService = new Camunda7ProcessService<>(
                    adapterId, engine.getRuntimeService(), engine.getTaskService(), engine
                        .getRepositoryService(), engine.getHistoryService(), AdapterBeanRegistrarSupport
                            .collaborators(supplierContext, adapterId));
                // an engine on a datasource of its own commits apart from the
                // application. That makes its deliveries repeatable
                processService.setEngineRunsOnItsOwnDataSource(engine.usesSeparateDataSource());
                // a start reports the version of its process definition. It reads it
                // from the same cache the deliveries of this engine use
                processService.setTaskRegistry(engine.getTaskRegistry());
                processService
                    .setConfiguredTenants(
                        configuredTenantsOf(supplierContext.bean(VanillaBpCamunda7Properties.class), adapterId));
                // which serialization format nested shared values are stored in. It is
                // resolved per workflow, with a fallback to the module and the adapter
                final var overlay = supplierContext.bean(VanillaBpCamunda7Properties.class);
                processService.setSerializationFormats(
                    (
                        workflowModuleId,
                        bpmnProcessId) -> overlay
                            .serializationFormatFor(adapterId, workflowModuleId, bpmnProcessId));
                engine
                    .getTaskRegistry()
                    .setSerializationFormats(
                        (
                            workflowModuleId,
                            bpmnProcessId) -> overlay
                                .serializationFormatFor(adapterId, workflowModuleId, bpmnProcessId));
                return processService;
              }));

          registry.registerBean(
              "Camunda7_DeploymentService_%s".formatted(adapterId),
              Camunda7DeploymentService.class,
              spec -> spec.supplier(supplierContext -> {
                final var engine = engineHolder(supplierContext, adapterId);
                final var deploymentService = new Camunda7DeploymentService(
                    adapterId, engine.getRepositoryService(), engine, AdapterBeanRegistrarSupport
                        .collaborators(supplierContext, adapterId), engine.getTaskRegistry(), id -> instanceIdentityOf(
                            environment, id));
                deploymentService.setEngineDeliversWorkflowEnded(engine.deliversWorkflowEnded());
                deploymentService.setConfiguredTenants(
                    configuredTenantsOf(supplierContext.bean(VanillaBpCamunda7Properties.class), adapterId));
                deploymentService.setIdentityService(
                    engine
                        .getProcessEngine()
                        .getIdentityService());
                // How many workflows still run on an older version
                deploymentService.setRuntimeService(
                    engine
                        .getProcessEngine()
                        .getRuntimeService());
                deploymentService.setAcceptUnscopedIdentifiers(
                    supplierContext
                        .bean(VanillaBpCamunda7Properties.class)
                        .enginePropertiesFor(adapterId)
                        .isAcceptUnscopedIdentifiers());
                // which format a workflow's values travel in, and what this engine's
                // serializers make of them. The startup check reads both
                final var deploymentOverlay = supplierContext.bean(VanillaBpCamunda7Properties.class);
                deploymentService.setSerializationFormats(
                    (
                        workflowModuleId,
                        bpmnProcessId) -> deploymentOverlay
                            .serializationFormatFor(adapterId, workflowModuleId, bpmnProcessId));
                deploymentService.setSerializationRoundTrip(
                    io.vanillabp.camunda7.sync.Camunda7SerializationRoundTrip.of(engine.getProcessEngine()));
                // whether this application serves the listeners somebody modelled. It
                // can be set down to the workflow
                deploymentService.setAllowListenersResolver(
                    (
                        workflowModuleId,
                        bpmnProcessId) -> deploymentOverlay
                            .allowListenersFor(adapterId, workflowModuleId, bpmnProcessId));
                // a Spring bean named like a task definition takes the expression away
                // from the method. The startup check asks the context the engine asks
                deploymentService.setClassOfTheBeanNamed(engine::classOfTheBeanNamed);
                // a key at a level which does not resolve it changes nothing. Silence
                // would be worse than a line which says where the key is read
                io.vanillabp.camunda7.wiring.Camunda7Listeners.reportKeysSetAtTaskLevel(
                    adapterId,
                    deploymentOverlay.allowListenersKeysAtTaskLevel(adapterId),
                    log::warn);
                return deploymentService;
              }));

          // what this adapter knows about THIS engine, for an extension which runs in it.
          // One Camunda7EngineFacts per adapter id. It is built from what the engine
          // wiring computed anyway, not from a second reading of the configuration
          registry.registerBean(
              "Camunda7_EngineFacts_%s".formatted(adapterId),
              io.vanillabp.camunda7.api.Camunda7EngineFacts.class,
              spec -> spec.supplier(supplierContext -> new io.vanillabp.camunda7.api.Camunda7EngineFacts(
                  adapterId, AdapterBeanRegistrarSupport
                      .collaborators(supplierContext, adapterId)
                      .scoping(), configuredTenantsOf(
                          supplierContext.bean(VanillaBpCamunda7Properties.class), adapterId), engineHolder(
                              supplierContext, adapterId)
                              .getTaskRegistry())));

          // named convenience beans, e.g. for tests and applications integrating
          // with the engine directly (unique by type in single-adapter setups)
          registry.registerBean(
              "Camunda7_ProcessEngine_%s".formatted(adapterId),
              ProcessEngine.class,
              spec -> spec.supplier(supplierContext -> engineHolder(supplierContext, adapterId).getProcessEngine()));
          registry.registerBean(
              "Camunda7_RuntimeService_%s".formatted(adapterId),
              RuntimeService.class,
              spec -> spec.supplier(supplierContext -> engineHolder(supplierContext, adapterId).getRuntimeService()));
          registry.registerBean(
              "Camunda7_RepositoryService_%s".formatted(adapterId),
              RepositoryService.class,
              spec -> spec
                  .supplier(supplierContext -> engineHolder(supplierContext, adapterId).getRepositoryService()));

        });

  }

  private static Camunda7EngineHolder engineHolder(
      final BeanRegistry.SupplierContext supplierContext,
      final String adapterId) {

    return supplierContext.bean("Camunda7_Engine_%s".formatted(adapterId), Camunda7EngineHolder.class);

  }

  /**
   * Binds the adapter id's engine settings from the canonical per-adapter location
   * <code>vanillabp.adapters.&lt;id&gt;.*</code> (the overlay view, see
   * {@link VanillaBpCamunda7Properties}).
   */
  private static Camunda7EngineProperties enginePropertiesFor(
      final Environment environment,
      final String adapterId) {

    return Binder
        .get(environment)
        .bind("vanillabp.adapters.%s".formatted(adapterId), Camunda7EngineProperties.class)
        .orElseGet(Camunda7EngineProperties::new);

  }

  /**
   * Resolves a bean the application provides. If that fails, it fails with a GUIDING
   * message, not with a bean-wiring error. The embedded engine needs a {@code DataSource}
   * and a {@code PlatformTransactionManager}. A configured adapter without a database is a
   * configuration defect. The developer has to learn about it with the remedy named, not
   * through a {@code NoSuchBeanDefinitionException}.
   *
   * @param <S> The bean type
   * @param supplierContext The bean supplier context
   * @param beanType The bean type
   * @param adapterId The adapter ID (used in the guiding message)
   * @return The bean
   */
  private static <S> S applicationBean(
      final BeanRegistry.SupplierContext supplierContext,
      final Class<S> beanType,
      final String adapterId) {

    // several datasources available: VanillaBP does not guess which one the engine runs
    // on. Not even a @Primary bean decides it, because an embedded engine writes its
    // ACT_* tables into whatever database it gets
    final var availableBeanNames = availableBeanNames(supplierContext, beanType);
    if ((availableBeanNames.size() > 1) && DataSource.class.equals(beanType)) {
      throw new IllegalStateException(
          """
              Camunda 7 adapter '%s' runs embedded and needs a database, but the application \
              provides SEVERAL DataSource beans: '%s'. Name the one this adapter id runs on:
                vanillabp.adapters.%s.data-source-name: <bean name>
              Use the reserved value 'default' for the application's default (primary) datasource. \
              (Two adapter ids may also share one datasource if each uses its own \
              'vanillabp.adapters.<id>.table-prefix' - Camunda does not create prefixed tables, so \
              those have to exist beforehand.)"""
              .formatted(adapterId, String.join("', '", availableBeanNames), adapterId));
    }

    final var bean = supplierContext
        .beanProvider(beanType)
        .getIfAvailable();
    if (bean == null) {
      throw new IllegalStateException(
          """
              Camunda 7 adapter '%s' is configured ('vanillabp.adapters.%s.type: camunda7') but the \
              embedded engine cannot be created: no DataSource/PlatformTransactionManager is available. \
              Camunda 7 runs embedded and always needs a database - configure 'spring.datasource.*' \
              (and a transaction manager, e.g. via the JDBC or JPA starters), reference an \
              application-provided DataSource bean via 'vanillabp.adapters.%s.data-source-name' or \
              remove the adapter configuration."""
              .formatted(adapterId, adapterId, adapterId));
    }
    return bean;

  }

  /**
   * What makes an adapter id a distinct engine: its datasource and table prefix (see
   * {@link Camunda7InstanceIdentity}). It is read from the environment, because the check
   * runs before the engine beans exist and does not depend on them.
   */
  private static Camunda7InstanceIdentity instanceIdentityOf(
      final Environment environment,
      final String adapterId) {

    return new Camunda7InstanceIdentity(
        adapterProperty(environment, adapterId, "data-source-name"), adapterProperty(environment, adapterId,
            "table-prefix"));

  }

  private static String adapterProperty(
      final Environment environment,
      final String adapterId,
      final String key) {

    return Binder
        .get(environment)
        .bind(
            "vanillabp.adapters.%s.%s".formatted(adapterId, key),
            Bindable.of(String.class))
        .orElse(null);

  }


  /**
   * The NAMES of all beans of the given type. The lookup reads bean types only and creates
   * no bean. The list is empty if the bean factory cannot be reached. Then the ambiguity
   * check does not fire.
   */
  private static List<String> availableBeanNames(
      final BeanRegistry.SupplierContext supplierContext,
      final Class<?> beanType) {

    try {
      return List.of(
          supplierContext
              .bean(org.springframework.context.ApplicationContext.class)
              .getBeanNamesForType(beanType, true, false));
    } catch (final RuntimeException e) {
      return List.of();
    }

  }


  /**
   * The configured tenant of each workflow module. It is read per workflow module
   * (<code>vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.tenant-id</code>),
   * with a fallback to the adapter (<code>vanillabp.adapters.&lt;id&gt;.tenant-id</code>).
   * It is <code>null</code> for a module which neither names. Then the workflow module id
   * names the tenant.
   */
  private static java.util.function.Function<String, io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant> configuredTenantsOf(
      final VanillaBpCamunda7Properties overlay,
      final String adapterId) {

    return workflowModuleId -> overlay.configuredTenantFor(adapterId, workflowModuleId);

  }

}
