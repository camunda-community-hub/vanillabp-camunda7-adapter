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
 * An application which declares more than one datasource and an adapter id which names
 * none of them. Camunda 7 runs embedded and needs a database, so guessing which of them is
 * meant would put the engine's tables wherever the alphabet decides. The boot stops and says
 * how to name one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7SeveralDataSourcesTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("several-datasources/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        final var message = Camunda7BootFailure.messagesOf(throwable);
        Assertions.assertTrue(
            message.contains("declares SEVERAL datasources"),
            () -> "expected the guiding message about several datasources but got: "
                + message);
        Assertions.assertTrue(message.contains("vanillabp.adapters.c7.data-source-name"), () -> message);
        Assertions.assertTrue(message.contains("table-prefix"), () -> message);
      });

  @Test
  public void anAdapterWithoutADataSourceNameFailsTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
