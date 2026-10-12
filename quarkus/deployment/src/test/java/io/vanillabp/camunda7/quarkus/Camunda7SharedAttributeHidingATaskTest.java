package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.namehiding.QSharedNameAggregate;
import io.vanillabp.camunda7.quarkus.namehiding.QSharedNamePersistence;
import io.vanillabp.camunda7.quarkus.namehiding.QSharedNameWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow aggregate which shares an attribute named like a task definition. The shared
 * attribute is a process variable, and the engine reads its variables before it asks
 * VanillaBP. So the variable would take the expression away from the method. The start ends
 * instead. Quarkus has no beans in the engine's expressions, so the attribute is the only
 * collision there is on this platform.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7SharedAttributeHidingATaskTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(QSharedNameAggregate.class)
          .addClass(QSharedNamePersistence.class)
          .addClass(QSharedNameWorkflowService.class)
          .addAsResource("name-hiding/application.yaml", "application.yaml")
          .addAsResource("c7-shared-name/processes/shared-name.bpmn", "c7-test/processes/shared-name.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey(
          "quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:c7-shared-name-test;DB_CLOSE_DELAY=-1")
      .assertException(throwable -> {
        final var message = Camunda7BootFailure.messagesOf(throwable);
        Assertions.assertTrue(
            message.contains("Task definition 'chargeCard'"),
            () -> "expected the message about the hidden task but got: "
                + message);
        Assertions.assertTrue(message.contains("'QSharedNameProcess'"), () -> message);
        Assertions.assertTrue(message.contains("element 'SN_Charge'"), () -> message);
        Assertions.assertTrue(message.contains("the attribute 'chargeCard' of the workflow aggregate"), () -> message);
        Assertions.assertTrue(message.contains("@NoSyncWithBPMS"), () -> message);
      });

  @Test
  public void aSharedAttributeNamedLikeATaskDefinitionEndsTheStart() {
    // the assertion happens on the startup exception (assertException above)
  }

}
