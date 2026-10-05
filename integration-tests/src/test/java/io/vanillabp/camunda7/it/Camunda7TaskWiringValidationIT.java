package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Guiding wiring validation on a real engine boot: a BPMN task whose expression
 * matches no {@code @WorkflowTask} method aborts the boot naming the task and the fix,
 * and so does a task which has to stay open but is wired in a way that
 * completes it on return.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7TaskWiringValidationIT {

  @Test
  @DisplayName("A BPMN task without matching @WorkflowTask method aborts the boot with guiding messages")
  public void incompleteWiringAbortsBoot() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> new SpringApplicationBuilder(TestApplication.class)
            // command-line args take precedence over the module's application.yaml
            .run(
                // the broken BPMN redefines 'TaskProcess' with an unimplemented task
                "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/broken",
                "--spring.datasource.url=jdbc:h2:mem:c7-wiring-validation;DB_CLOSE_DELAY=-1")
            .close());

    final var message = rootMessage(failure);
    assertTrue(
        message.contains("Task wiring of BPMN process 'TaskProcess' of workflow module 'c7-it' is incomplete!"),
        "unexpected message: "
            + message);
    assertTrue(message.contains("'BR_Task'"), "unexpected message: "
        + message);
    assertTrue(
        message.contains("@WorkflowTask(taskDefinition = \"notImplemented\")"),
        "unexpected message: "
            + message);
  }

  @Test
  @DisplayName("An asynchronous task wired by 'camunda:expression' aborts the boot naming the fix")
  public void asynchronousTaskWiredByExpressionAbortsBoot() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> new SpringApplicationBuilder(TestApplication.class)
            .run(
                // AsyncProcess, whose handler declares @TaskId, wired by an
                // expression which completes the task when it returns
                "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/async-by-expression",
                "--spring.datasource.url=jdbc:h2:mem:c7-async-wiring-validation;DB_CLOSE_DELAY=-1")
            .close());

    final var message = rootMessage(failure);
    assertTrue(message.contains("'asyncTask'"), "unexpected message: "
        + message);
    assertTrue(message.contains("'AsyncProcess'"), "unexpected message: "
        + message);
    assertTrue(message.contains("'c7-it'"), "unexpected message: "
        + message);
    assertTrue(message.contains("@TaskId"), "unexpected message: "
        + message);
    assertTrue(
        message.contains("'camunda:delegateExpression'"),
        "unexpected message: "
            + message);
  }

  @Test
  @DisplayName("The same defect aborts the boot where the method is wired by the element id")
  public void asynchronousTaskWiredByExpressionAbortsBootForAMethodWiredByTheElementId() {

    // a method names its task by the task definition or by the element id, and the
    // wiring validation accepts both - so both reach this check
    final var failure = assertThrows(
        RuntimeException.class,
        () -> new SpringApplicationBuilder(TestApplication.class)
            .run(
                "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/async-by-element-id",
                "--spring.profiles.active=async-by-element-id",
                "--spring.datasource.url=jdbc:h2:mem:c7-async-by-element-id;DB_CLOSE_DELAY=-1")
            .close());

    final var message = rootMessage(failure);
    assertTrue(message.contains("'AsyncByElementIdProcess'"), "unexpected message: "
        + message);
    assertTrue(message.contains("@TaskId"), "unexpected message: "
        + message);
    assertTrue(
        message.contains("'camunda:delegateExpression'"),
        "unexpected message: "
            + message);

  }

  @Test
  @DisplayName("A @WorkflowTask method matching no task of the module aborts the boot")
  public void orphanWorkflowTaskMethodAbortsBoot() {

    // the other direction of the wiring check, which this adapter never called: the
    // core runs it itself since story 158, once every adapter of the module deployed
    final var failure = assertThrows(
        RuntimeException.class,
        () -> new SpringApplicationBuilder(TestApplication.class)
            .run(
                "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/orphan-method",
                "--spring.profiles.active=orphan-method",
                "--spring.datasource.url=jdbc:h2:mem:c7-orphan-method;DB_CLOSE_DELAY=-1")
            .close());

    final var message = rootMessage(failure);
    assertTrue(message.contains("orphanTypo"), "unexpected message: "
        + message);
    assertTrue(message.contains("activityNobodyModelled"), "unexpected message: "
        + message);
    assertTrue(message.contains("fix the annotation"), "unexpected message: "
        + message);

  }

  private static String rootMessage(
      final Throwable throwable) {

    var cause = throwable;
    while ((cause.getCause() != null) && (cause.getCause() != cause)) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

}
