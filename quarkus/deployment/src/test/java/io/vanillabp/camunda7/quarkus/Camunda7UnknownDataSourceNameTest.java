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
 * Startup validation on Quarkus (26c style): a {@code camunda7} adapter id
 * referencing an UNDECLARED datasource name
 * (<code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>) must fail the boot
 * with a guiding message naming the <code>quarkus.datasource.&lt;name&gt;.*</code>
 * remedy and the declared datasource names.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7UnknownDataSourceNameTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("unknown-datasource/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        final var message = rootCauseMessage(throwable);
        Assertions.assertTrue(
            message.contains("references the datasource 'not-declared'"),
            "expected the guiding message naming the unknown datasource but got: "
                + message);
        Assertions.assertTrue(message.contains("quarkus.datasource.not-declared"));
        Assertions.assertTrue(message.contains("Declared datasources:"));
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
  public void unknownDataSourceNameFailsTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
