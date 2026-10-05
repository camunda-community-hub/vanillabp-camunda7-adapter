package io.vanillabp.camunda7.quarkus;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Camunda 7 progresses workflows after the commit, through the phase-two
 * outbox - so an application without one cannot work. It is told while booting, with
 * the message naming the ways out, instead of at the first workflow.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7WithoutOutboxTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:c7-no-outbox-test;DB_CLOSE_DELAY=-1")
      // the JDBC outbox would be there because a datasource is: switching it off is
      // how an application ends up without any
      .overrideConfigKey("vanillabp.outbox.jdbc.enabled", "false")
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          if ((current.getMessage() != null) && current.getMessage()
              .contains("is dispatched through a PhaseTwoOutbox")) {
            final var message = current.getMessage();
            assertTrue(message.contains("c7"), message);
            assertTrue(message.contains("TestProcess"), message);
            // the ways out are part of it
            assertTrue(message.contains("PhaseTwoOutbox"), message);
            return;
          }
          current = current.getCause();
        }
        fail("expected the guiding message about the missing outbox but got: "
            + throwable);
      });

  @Test
  @DisplayName("Without an outbox the application says so while booting")
  public void missingOutboxIsReportedWhileBooting() {
    // the assertion happens on the startup exception (assertException above)
  }

}
