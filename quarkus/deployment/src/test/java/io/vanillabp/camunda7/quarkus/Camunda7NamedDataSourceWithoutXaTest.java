package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Startup validation on Quarkus. The engine of {@code c7b} runs on a named datasource, and
 * the application runs on the default one. Neither is an XA datasource. A transaction takes
 * in only one datasource without XA, so the engine's job would roll back after each task.
 * The boot fails instead, and the message names the key which fixes it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7NamedDataSourceWithoutXaTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("named-datasource-without-xa/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        final var message = Camunda7BootFailure.messagesOf(throwable);
        Assertions.assertTrue(
            message.contains("Camunda 7 adapter 'c7b' runs on the datasource 'c7b'"),
            () -> "expected the message about the missing XA datasource but got: "
                + message);
        Assertions.assertTrue(
            message.contains("quarkus.datasource.c7b.jdbc.transactions: xa"),
            () -> message);
        Assertions.assertTrue(
            message.contains("quarkus.datasource.jdbc.transactions: xa"),
            () -> message);
        Assertions.assertFalse(
            message.contains("Camunda 7 adapter 'c7' runs on"),
            () -> "the engine on the default datasource is fine: "
                + message);
      });

  @Test
  public void aNamedDataSourceWithoutXaFailsTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
