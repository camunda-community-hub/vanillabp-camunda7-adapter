package io.vanillabp.camunda7.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.TaskEvent;

/**
 * Which kind of task this adapter says an id is.
 * <p>
 * Both contexts of this adapter answer that from what they are rather than from the
 * model: one is built for an execution the engine pushed to the application, the other for
 * an event of a user task. So the answer is asserted on the contexts themselves, next to
 * the two ids it is about. The id of an execution and the id of a row in ACT_RU_TASK are
 * different names, and a caller using one where the other belongs is the mistake the kind
 * is reported for.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7ReportedTaskKindTest {

  private static final Camunda7TaskConnectable A_SERVICE_TASK = new Camunda7TaskConnectable(
      "the-module", "TheProcess", "The_Task", "theTask", Camunda7TaskConnectable.Type.DELEGATE_EXPRESSION);

  private static final Camunda7TaskConnectable A_USER_TASK = new Camunda7TaskConnectable(
      "the-module", "TheProcess", "The_User_Task", "approveRequest", Camunda7TaskConnectable.Type.USER_TASK);

  @Test
  @DisplayName("A task the engine pushed to the application is reported as a task")
  public void aPushedTaskIsReportedAsATask() {

    final var execution = mock(DelegateExecution.class);
    when(execution.getId()).thenReturn("an-execution-id");

    final var context = new Camunda7WorkflowTaskBehavior.Camunda7TaskInvocationContext(
        A_SERVICE_TASK, execution, null);

    assertEquals("an-execution-id", context.getTaskId(), "the id a completion names later");
    assertEquals(
        TaskKind.TASK,
        context.getTaskKind(),
        "an execution is the work the engine pushes, so this context is only ever a task");

  }

  @Test
  @DisplayName("A cancelled task is reported as a task as well")
  public void aCancelledTaskIsReportedAsATask() {

    final var execution = mock(DelegateExecution.class);
    when(execution.getId()).thenReturn("an-execution-id");

    // the cancellation listener builds the same context with the other event, and the
    // kind of an id does not change because the task is going away
    final var context = new Camunda7WorkflowTaskBehavior.Camunda7TaskInvocationContext(
        A_SERVICE_TASK, execution, TaskEvent.Event.CANCELED, null);

    assertEquals(TaskKind.TASK, context.getTaskKind());

  }

  @Test
  @DisplayName("A user-task event is reported as a user task")
  public void aUserTaskEventIsReportedAsAUserTask() {

    final var delegateTask = mock(DelegateTask.class);
    when(delegateTask.getId()).thenReturn("a-task-id");

    final var context = new Camunda7UserTaskEventListener.Camunda7UserTaskInvocationContext(
        A_USER_TASK, delegateTask, TaskEvent.Event.CREATED, null, "c7", false);

    assertEquals("a-task-id", context.getTaskId(), "the row of ACT_RU_TASK, not an execution");
    assertEquals(
        TaskKind.USER_TASK,
        context.getTaskKind(),
        "this context exists only for a user task, so no reading of the model is needed");

  }

  @Test
  @DisplayName("A cancelled user task is reported as a user task as well")
  public void aCancelledUserTaskIsReportedAsAUserTask() {

    final var delegateTask = mock(DelegateTask.class);
    when(delegateTask.getId()).thenReturn("a-task-id");

    final var context = new Camunda7UserTaskEventListener.Camunda7UserTaskInvocationContext(
        A_USER_TASK, delegateTask, TaskEvent.Event.CANCELED, null, "c7", false);

    assertEquals(TaskKind.USER_TASK, context.getTaskKind());

  }

}
