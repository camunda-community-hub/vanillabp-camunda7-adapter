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
 * The table-prefix check on Quarkus: an adapter id configured with
 * <code>table-prefix</code> and the default <code>database-schema-update: true</code>
 * ends the boot with the same guiding message as on Spring Boot, because Camunda's
 * schema management ignores the prefix and would create a set of unprefixed
 * <code>ACT_*</code> tables instead.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7TablePrefixValidationTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("table-prefix/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        final var message = rootCauseMessage(throwable);
        Assertions.assertTrue(
            message.contains("table prefix 'NEW_'"),
            "expected the guiding message naming the prefix but got: "
                + message);
        Assertions.assertTrue(message.contains("'database-schema-update: true'"), () -> message);
        Assertions
            .assertTrue(message.contains("vanillabp.adapters.c7.database-schema-update: false"), () -> message);
        Assertions.assertTrue(message.contains("vanillabp.adapters.c7.data-source-name"), () -> message);
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
  public void aTablePrefixWithSchemaUpdateFailsTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
