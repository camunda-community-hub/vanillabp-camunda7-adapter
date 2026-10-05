package io.vanillabp.camunda7.springboot.it;

import org.h2.Driver;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Unit test of the {@link Camunda7EngineHolder}'s job-executor lifecycle:
 * the engine is built with the executor INACTIVE (deferred activation); the executor
 * is engine-global while start/stop is notified per workflow module, so the started
 * modules are reference-counted - the executor stops only when the LAST started
 * module stops, and unconditionally on {@code close()} (which is idempotent). The
 * holder is built on a NAMED, application-provided datasource bean
 * (<code>data-source-name</code>), which also covers the named-bean resolution path
 * (VanillaBP never builds its own pool - datasources are the application's concern).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7EngineHolderTest {

  private static Camunda7EngineProperties namedDataSourceProperties(
      final String dataSourceName) {

    final var properties = new Camunda7EngineProperties();
    properties.setDataSourceName(dataSourceName);
    return properties;

  }

  @Test
  @DisplayName("The job executor starts with the first module, stops with the last and on close")
  public void jobExecutorLifecycleIsReferenceCounted() {

    final var holder = new Camunda7EngineHolder(
        "holder-test", namedDataSourceProperties("holderTestDataSource"), null, null, null, null, null);
    // Spring lifecycle done manually: SpringProcessEngineConfiguration needs an
    // ApplicationContext (Spring-bean resolution in scripting/expressions), and the
    // named datasource bean is resolved from it
    try (var applicationContext = new StaticApplicationContext()) {
      applicationContext
          .getBeanFactory()
          .registerSingleton(
              "holderTestDataSource",
              new SimpleDriverDataSource(new Driver(), "jdbc:h2:mem:holder-test;DB_CLOSE_DELAY=-1"));
      holder.setApplicationContext(applicationContext);
      holder.afterPropertiesSet();
    }
    try {

      Assertions.assertTrue(holder.usesSeparateDataSource());
      Assertions.assertEquals("vanillabp-camunda7-holder-test", holder.getProcessEngine().getName());

      // deferred activation: building the engine must not start the executor
      Assertions.assertFalse(holder.isJobExecutorActive(), "executor must be inactive after engine build");

      // first module starts the engine-global executor
      holder.startWorkflowProcessing("module-a");
      Assertions.assertTrue(holder.isJobExecutorActive());

      // a second module keeps it running...
      holder.startWorkflowProcessing("module-b");
      holder.stopWorkflowProcessing("module-a");
      Assertions.assertTrue(
          holder.isJobExecutorActive(),
          "stopping the FIRST module must not starve the remaining modules");

      // ...and only the last module's stop shuts it down
      holder.stopWorkflowProcessing("module-b");
      Assertions.assertFalse(holder.isJobExecutorActive());

      // restartable (e.g. tests or future lifecycle features)
      holder.startWorkflowProcessing("module-a");
      Assertions.assertTrue(holder.isJobExecutorActive());

    } finally {
      holder.close();
    }

    // close stops the executor unconditionally, before the engine closes - and is
    // safe to call more than once
    Assertions.assertFalse(holder.isJobExecutorActive());
    holder.close();

  }

  @Test
  @DisplayName("An unknown data-source-name fails with a guiding message listing the available beans")
  public void unknownDataSourceNameFailsWithGuidingMessage() {

    final var holder = new Camunda7EngineHolder(
        "holder-test", namedDataSourceProperties("not-there"), null, null, null, null, null);
    try (var applicationContext = new StaticApplicationContext()) {
      applicationContext
          .getBeanFactory()
          .registerSingleton(
              "someOtherDataSource",
              new SimpleDriverDataSource(new Driver(), "jdbc:h2:mem:holder-guide-test;DB_CLOSE_DELAY=-1"));
      holder.setApplicationContext(applicationContext);

      final var exception = Assertions.assertThrows(
          IllegalStateException.class,
          holder::afterPropertiesSet);
      Assertions.assertTrue(
          exception.getMessage().contains("references the datasource bean 'not-there'"),
          "expected the guiding message but got: "
              + exception.getMessage());
      Assertions.assertTrue(exception.getMessage().contains("vanillabp.adapters.holder-test.data-source-name"));
      Assertions.assertTrue(exception.getMessage().contains("someOtherDataSource"));
    }

  }

}
