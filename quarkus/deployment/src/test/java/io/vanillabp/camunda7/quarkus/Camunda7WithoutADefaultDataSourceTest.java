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
 * An application whose only datasource is a named one, and an adapter id which asks for the
 * default. There is no default to run on, and the message says the three ways out rather
 * than reporting a missing bean.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7WithoutADefaultDataSourceTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("no-default-datasource/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        final var message = Camunda7BootFailure.messagesOf(throwable);
        Assertions.assertTrue(
            message.contains("no default datasource is available"),
            () -> "expected the guiding message about the missing default datasource but got: "
                + message);
        Assertions.assertTrue(message.contains("quarkus.datasource.*"), () -> message);
        Assertions.assertTrue(message.contains("vanillabp.adapters.c7.data-source-name"), () -> message);
      });

  @Test
  public void anAdapterWithoutADefaultDataSourceFailsTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
