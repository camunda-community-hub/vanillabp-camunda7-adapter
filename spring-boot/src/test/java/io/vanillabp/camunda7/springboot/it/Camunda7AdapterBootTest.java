package io.vanillabp.camunda7.springboot.it;

import org.camunda.bpm.engine.ProcessEngine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.vanillabp.camunda7.deployment.Camunda7DeploymentService;
import io.vanillabp.camunda7.springboot.Camunda7AdapterConfiguration;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.camunda7.springboot.processservice.Camunda7ProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Boot smoke test proving that the Camunda 7 adapter is discovered purely by
 * configuration ({@code vanillabp.adapters.c7.type: camunda7}). The Camunda 7 beans
 * resolve the embedded engine's DataSource lazily, so the test wires a plain-JDBC H2
 * setup ({@code DataSourceAutoConfiguration} +
 * {@code DataSourceTransactionManagerAutoConfiguration}, deliberately NO JPA). There
 * are no BPMN files and no {@code @WorkflowService} classes, so no workflow is
 * deployed.
 * <p>
 * The deployment lifecycle ({@code DeploymentAutoConfiguration}) is deliberately NOT
 * loaded: the core deployment pipeline would invoke {@code deployResources} once per
 * (workflow module x prioritized adapter); deploying real BPMN is covered by the
 * integration-tests module. This test proves discovery/registration only.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7AdapterBootTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  @Test
  public void adapterIsDiscoveredByConfiguration() {

    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-boot-test;DB_CLOSE_DELAY=-1")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");

          // the embedded engine was wired (plain JDBC, no JPA), named after the
          // adapter id, with the job executor still INACTIVE (deferred activation:
          // startWorkflowProcessing starts it)
          final var engine = context.getBean(ProcessEngine.class);
          Assertions.assertEquals("vanillabp-camunda7-c7", engine.getName());
          final var holder = context.getBean(Camunda7EngineHolder.class);
          Assertions.assertFalse(
              holder.isJobExecutorActive(),
              "the job executor must not be active before startWorkflowProcessing");
          Assertions.assertFalse(holder.usesSeparateDataSource());

          // element-bean convention: one AdapterDeploymentService bean per adapter
          // (never a List bean) so several adapter types can coexist
          final var deploymentService = context.getBean(AdapterDeploymentService.class);
          Assertions.assertEquals("c7", deploymentService.getAdapterId());
          Assertions.assertEquals(
              Camunda7DeploymentService.ADAPTER_TYPE,
              deploymentService.getAdapterType());
          Assertions.assertEquals("camunda7", deploymentService.getAdapterType());

        });

  }

  @Test
  public void twoAdapterIdsOfTypeCamunda7YieldPerIdEnginesAndBeans() {

    // per-adapter-id convention: TWO ids of type camunda7 yield
    // one ENGINE, one process service and one deployment service PER id - the
    // second id needs its own datasource (two embedded engines must never share
    // one schema, see the validation test below), referenced BY BEAN NAME: setting
    // up datasources is the application's concern, VanillaBP never builds a pool
    this.contextRunner
        .withBean(
            "c7TwoDataSource",
            javax.sql.DataSource.class,
            () -> new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                new org.h2.Driver(), "jdbc:h2:mem:camunda7-two-ids-own;DB_CLOSE_DELAY=-1"),
            // keep the bean out of by-type injection so Spring Boot's
            // default-datasource auto-configuration stays active (the standard
            // pattern for additional application datasources)
            beanDefinition -> ((org.springframework.beans.factory.support.AbstractBeanDefinition) beanDefinition)
                .setDefaultCandidate(false))
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-two-ids-test;DB_CLOSE_DELAY=-1",
            "vanillabp.prioritized-adapters=c7,c7-two",
            "vanillabp.adapters.c7-two.type=camunda7",
            "vanillabp.adapters.c7-two.data-source-name=c7TwoDataSource",
            "vanillabp.workflow-modules.c7-smoke-test.adapters.c7-two.resources-location=classpath*:c7-smoke-test/processes-two")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");

          // one engine per adapter id, named after the id
          final var engineNames = context
              .getBeansOfType(ProcessEngine.class)
              .values()
              .stream()
              .map(ProcessEngine::getName)
              .collect(java.util.stream.Collectors.toSet());
          Assertions.assertEquals(
              java.util.Set.of("vanillabp-camunda7-c7", "vanillabp-camunda7-c7-two"),
              engineNames);

          // the id with its own datasource is marked accordingly (its starts use
          // the two-phase pattern - see Camunda7ProcessService)
          final var holders = context.getBeansOfType(Camunda7EngineHolder.class);
          Assertions.assertFalse(holders.get("Camunda7_Engine_c7").usesSeparateDataSource());
          Assertions.assertTrue(holders.get("Camunda7_Engine_c7-two").usesSeparateDataSource());

          final var deploymentServiceIds = context
              .getBeanProvider(AdapterDeploymentService.class)
              .stream()
              .map(service -> ((AdapterDeploymentService<?, ?>) service).getAdapterId())
              .collect(java.util.stream.Collectors.toSet());
          Assertions.assertEquals(java.util.Set.of("c7", "c7-two"), deploymentServiceIds);

          final var processServices = context
              .getBeanProvider(io.vanillabp.integration.adapter.spi.MigratableProcessService.class)
              .stream()
              .map(service -> (io.vanillabp.integration.adapter.spi.MigratableProcessService<?>) service)
              .collect(java.util.stream.Collectors
                  .toMap(io.vanillabp.integration.adapter.spi.MigratableProcessService::getAdapterId,
                      service -> service));
          Assertions.assertEquals(java.util.Set.of("c7", "c7-two"), processServices.keySet());

          // an embedded engine answers the election by asking its own tables, so this
          // adapter never guesses and may be combined with a second BPMS
          processServices
              .values()
              .forEach(service -> Assertions.assertTrue(
                  service.canLocateWorkflows(),
                  "Camunda 7 can be asked which workflows it holds"));

        });

  }

  @Test
  public void engineFactsAreRegisteredPerAdapterIdAndAnswerTheAdaptersOwnAnswer() {

    // what an extension of this adapter asks about the engine it runs in: one
    // Camunda7EngineFacts bean per configured adapter id, and the id with a data source of
    // its own is the one reported as NOT joining the application's transaction
    this.contextRunner
        .withBean(
            "c7FactsDataSource",
            javax.sql.DataSource.class,
            () -> new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                new org.h2.Driver(), "jdbc:h2:mem:camunda7-facts-own;DB_CLOSE_DELAY=-1"),
            beanDefinition -> ((org.springframework.beans.factory.support.AbstractBeanDefinition) beanDefinition)
                .setDefaultCandidate(false))
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-facts-test;DB_CLOSE_DELAY=-1",
            "vanillabp.prioritized-adapters=c7,c7-own",
            "vanillabp.adapters.c7-own.type=camunda7",
            "vanillabp.adapters.c7-own.data-source-name=c7FactsDataSource",
            "vanillabp.workflow-modules.c7-smoke-test.adapters.c7-own.resources-location=classpath*:c7-smoke-test/processes-two")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");

          final var facts = context.getBeansOfType(io.vanillabp.camunda7.api.Camunda7EngineFacts.class);
          Assertions.assertEquals(
              java.util.Set.of("Camunda7_EngineFacts_c7", "Camunda7_EngineFacts_c7-own"),
              facts.keySet(),
              "one Camunda7EngineFacts bean per configured adapter id");

          final var shared = facts.get("Camunda7_EngineFacts_c7");
          Assertions.assertEquals("c7", shared.adapterId());
          Assertions.assertTrue(
              shared.joinsTheApplicationTransaction(),
              "an engine on the application's data source commits with it");
          Assertions.assertEquals(
              "c7-smoke-test",
              shared.tenantIdOf("c7-smoke-test"),
              "without a configured name the tenant is the workflow module");
          Assertions.assertSame(
              context.getBean("Camunda7_Engine_c7", Camunda7EngineHolder.class).getTaskRegistry(),
              shared.taskRegistry(),
              "the registry handed out is the engine's own");

          final var own = facts.get("Camunda7_EngineFacts_c7-own");
          Assertions.assertEquals("c7-own", own.adapterId());
          Assertions.assertFalse(
              own.joinsTheApplicationTransaction(),
              "an engine on a data source of its own writes to a resource the application does not join");

        });

  }

  @Test
  public void twoAdapterIdsSharingTheSameDataSourceFailWithGuidingMessage() {

    // two embedded engines on one schema are the same engine state - configuring
    // them as two adapters must fail at startup with a guiding message (26c style)
    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-same-ds-test;DB_CLOSE_DELAY=-1",
            "vanillabp.prioritized-adapters=c7,c7-two",
            "vanillabp.adapters.c7-two.type=camunda7",
            "vanillabp.workflow-modules.c7-smoke-test.adapters.c7-two.resources-location=classpath*:c7-smoke-test/processes-two")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class,
                // the distinctness of two ids of one type is
                // validated through the adapter SPI hook, which the deployment
                // pipeline calls at startup
                io.vanillabp.integration.deployment.DeploymentAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNotNull(context.getStartupFailure(), "boot has to fail with a guiding message");

          var cause = context.getStartupFailure();
          while (cause.getCause() != null) {
            cause = cause.getCause();
          }
          final var message = String.valueOf(cause.getMessage());
          Assertions.assertTrue(
              message.contains("'c7', 'c7-two'"),
              "expected the guiding message naming both adapter ids but got: "
                  + message);
          Assertions.assertTrue(
              message.contains("run on the SAME engine database"),
              "expected the guiding message but got: "
                  + message);
          Assertions.assertTrue(message.contains("data-source-name"));
          Assertions.assertTrue(
              message.contains("table-prefix"),
              "the message has to name the second way of making two ids distinct");

        });

  }

  @Test
  public void aTablePrefixWithSchemaUpdateFailsWithGuidingMessage() {

    // Camunda's schema management ignores the prefix and would create a set
    // of unprefixed ACT_* tables in the shared database. The check runs before the
    // engine is built, so the application learns about it instead of collecting stray
    // tables and a MyBatis stack trace
    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-prefix-schema-update-test;DB_CLOSE_DELAY=-1",
            "vanillabp.adapters.c7.table-prefix=NEW_")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          final var message = rootCauseMessage(context.getStartupFailure());
          Assertions.assertTrue(message.contains("'NEW_'"), () -> message);
          Assertions.assertTrue(message.contains("'database-schema-update: true'"), () -> message);
          Assertions
              .assertTrue(
                  message.contains("vanillabp.adapters.c7.database-schema-update: false"),
                  () -> message);
          Assertions.assertTrue(message.contains("vanillabp.adapters.c7.data-source-name"), () -> message);

          // and nothing was created while failing - the check runs before the engine
          try (var connection = java.sql.DriverManager
              .getConnection("jdbc:h2:mem:camunda7-prefix-schema-update-test;DB_CLOSE_DELAY=-1")) {
            try (var tables = connection.getMetaData().getTables(null, null, "ACT%", new String[]{
                "TABLE"
            })) {
              Assertions.assertFalse(tables.next(), "no ACT_* table may have been created");
            }
          }

        });

  }

  @Test
  public void missingPrefixedTablesFailWithGuidingMessage() {

    // The prefixed engine is switched to 'the tables exist already', and
    // they do not - the message names them and both ways on
    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "spring.datasource.url=jdbc:h2:mem:camunda7-prefix-missing-test;DB_CLOSE_DELAY=-1",
            "vanillabp.adapters.c7.table-prefix=NEW_",
            "vanillabp.adapters.c7.database-schema-update=false")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          final var message = rootCauseMessage(context.getStartupFailure());
          Assertions.assertTrue(message.contains("NEW_ACT_RU_EXECUTION"), () -> message);
          Assertions.assertTrue(message.contains("the application's default datasource"), () -> message);
          Assertions.assertTrue(message.contains("vanillabp.adapters.c7.data-source-name"), () -> message);

        });

  }

  /**
   * @param startupFailure The failure of a context which had to fail
   * @return The message of its innermost cause
   */
  private static String rootCauseMessage(
      final Throwable startupFailure) {

    Assertions.assertNotNull(startupFailure, "boot has to fail with a guiding message");
    var cause = startupFailure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

  @Test
  public void configuredAdapterWithoutDataSourceFailsWithGuidingMessage() {

    // a configured camunda7 adapter WITHOUT any DataSource: the boot fails with a
    // guiding message naming the remedy - not with a NoSuchBeanDefinitionException
    // (configuration defects surface at startup)
    this.contextRunner
        .withPropertyValues("spring.config.location=classpath:application.yaml")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withConfiguration(
            AutoConfigurations.of(
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNotNull(context.getStartupFailure(), "boot has to fail with a guiding message");

          var cause = context.getStartupFailure();
          while (cause.getCause() != null) {
            cause = cause.getCause();
          }
          final var message = String.valueOf(cause.getMessage());
          Assertions.assertTrue(
              message.contains("Camunda 7 adapter 'c7' is configured"),
              "expected the guiding message but got: "
                  + message);
          Assertions.assertTrue(message.contains("no DataSource/PlatformTransactionManager is available"));
          Assertions.assertTrue(message.contains("spring.datasource."));

        });

  }

  @Test
  public void withAnotherBpmsConfiguredNoCamunda7EngineIsCreated() {

    // the adapter jar is on the classpath but ANOTHER BPMS is configured: the gate
    // must prevent the engine (and with it the ACT_* tables and the job executor)
    // entirely. (Configuring NOTHING is a different case: the single
    // adapter type of the classpath IS the configuration - see
    // withoutAnyConfigurationTheClasspathAdapterIsUsed.)
    this.contextRunner
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:camunda7-gate-test;DB_CLOSE_DELAY=-1",
            "vanillabp.adapters.other-bpms.type=other-bpms")
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");
          Assertions.assertTrue(
              context.getBeansOfType(ProcessEngine.class).isEmpty(),
              "no engine may be created without a configured camunda7 adapter");
          Assertions.assertTrue(
              context.getBeansOfType(Camunda7DeploymentService.class).isEmpty());

          // and no ACT_* tables were created in the business database
          final var dataSource = context.getBean(javax.sql.DataSource.class);
          try (var connection = dataSource.getConnection(); var tables = connection.getMetaData().getTables(null, null,
              "ACT_%", null)) {
            Assertions.assertFalse(tables.next(), "no ACT_* tables may exist");
          }

        });

  }

  @Test
  public void withoutAnyConfigurationTheClasspathAdapterIsUsed() {

    // The adapter jar on the classpath IS the configuration - the engine
    // of the derived adapter id (which is the adapter type) is created
    this.contextRunner
        .withPropertyValues(
            "spring.datasource.url=jdbc:h2:mem:camunda7-convention-test;DB_CLOSE_DELAY=-1")
        // the task invoker is contributed by the platform integration, which is not
        // part of this minimal runner - the engine needs it to be built
        .withBean(
            io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker.class,
            () -> org.mockito.Mockito
                .mock(io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker.class))
        // ...and its wiring half, which the deployment service takes since the SPI was
        // split into the two duties
        .withBean(
            io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring.class,
            () -> org.mockito.Mockito
                .mock(io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring.class))
        // the sync model is contributed by the platform integration,
        // which is not part of this minimal runner either
        .withBean(
            io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.class,
            () -> org.mockito.Mockito
                .mock(io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.class))
        // the name-clash-avoidance support is contributed by the platform
        // integration, too
        .withBean(
            io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.class,
            () -> org.mockito.Mockito
                .mock(io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.class))
        // since story 159 the adapter takes the whole set of collaborators in its
        // constructor, so a runner mocking them one by one has to name this one as well
        .withBean(
            io.vanillabp.integration.adapter.spi.PreCommitRegistrar.class,
            () -> org.mockito.Mockito.mock(io.vanillabp.integration.adapter.spi.PreCommitRegistrar.class))
        .withConfiguration(
            AutoConfigurations.of(
                org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class,
                org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration.class,
                Camunda7AdapterConfiguration.class,
                Camunda7ProcessServiceConfiguration.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");
          Assertions.assertTrue(
              context.containsBean("Camunda7_Engine_camunda7"),
              () -> "expected the engine of the derived adapter id 'camunda7' but got the beans: "
                  + java.util.Arrays.toString(context.getBeanDefinitionNames()));

        });

  }

}
