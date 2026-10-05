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
 * A table prefix together with the promise that its tables are already there, made by an
 * application whose database has none of them. An engine which believed that would fail on
 * its first query, at a moment nobody connects with the configuration, so the check reads
 * the database while the application boots and names the tables it did not find.
 * <p>
 * The other half of the prefix rule, that the engine may not create prefixed tables itself,
 * is {@code Camunda7TablePrefixValidationTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7PrefixWithoutItsTablesTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("prefix-without-tables/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        final var message = Camunda7BootFailure.messagesOf(throwable);
        Assertions.assertTrue(
            message.contains("table prefix 'OLD_'"),
            () -> "expected the guiding message naming the prefix but got: "
                + message);
        Assertions.assertTrue(message.contains("these tables of it are missing"), () -> message);
        Assertions.assertTrue(message.contains("OLD_ACT_RU_EXECUTION"), () -> message);
      });

  @Test
  public void aPrefixWhoseTablesAreMissingFailsTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
