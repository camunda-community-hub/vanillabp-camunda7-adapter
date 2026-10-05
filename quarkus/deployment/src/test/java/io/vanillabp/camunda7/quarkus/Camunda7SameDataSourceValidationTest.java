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
 * Startup validation on Quarkus (26c style, mirroring the Spring Boot module): two
 * embedded engines on one schema are the same engine state - two {@code camunda7}
 * adapter ids WITHOUT distinct datasources must fail the boot with a guiding message
 * naming the <code>data-source-name</code> remedy.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7SameDataSourceValidationTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("same-datasource/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        final var message = rootCauseMessage(throwable);
        Assertions.assertTrue(
            message.contains("'c7', 'c7b'"),
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

  private static String rootCauseMessage(
      final Throwable throwable) {

    var cause = throwable;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

  @Test
  public void twoAdapterIdsSharingTheSameDataSourceFailTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
