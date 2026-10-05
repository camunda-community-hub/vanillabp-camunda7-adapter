package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.task.QTaskAggregate;
import io.vanillabp.camunda7.quarkus.task.QTaskPersistence;
import io.vanillabp.camunda7.quarkus.task.QTaskWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A task whose handler declares {@code @TaskId}, wired by {@code camunda:expression}. Such a
 * task completes when the expression returns, so the handler's whole reason for the parameter
 * is gone: nothing stays open and the id it was handed names a task which no longer exists.
 * The boot stops and names the attribute to write instead.
 * <p>
 * The same model runs through {@code Camunda7TaskProcessingTest} with the correct wiring, so
 * what is measured here is the check and not the model.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7AsyncTaskWiredByExpressionTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(QTaskAggregate.class)
          .addClass(QTaskPersistence.class)
          .addClass(QTaskWorkflowService.class)
          .addAsResource("application.yaml")
          .addAsResource(
              "c7-async-by-expression/processes/task-matrix.bpmn",
              "c7-test/processes/task-matrix.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey(
          "quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:c7-async-by-expression-test;DB_CLOSE_DELAY=-1")
      .assertException(throwable -> {
        final var message = Camunda7BootFailure.messagesOf(throwable);
        Assertions.assertTrue(
            message.contains("declares a @TaskId parameter"),
            () -> "expected the guiding message about the wiring but got: "
                + message);
        Assertions.assertTrue(message.contains("'qAsync'"), () -> message);
        Assertions.assertTrue(message.contains("'QAsyncProcess'"), () -> message);
        Assertions.assertTrue(message.contains("camunda:delegateExpression"), () -> message);
      });

  @Test
  public void anOpenTaskWiredByExpressionFailsTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
