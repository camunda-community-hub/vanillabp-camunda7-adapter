package io.vanillabp.camunda7.processservice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.Execution;
import org.camunda.bpm.engine.runtime.ExecutionQuery;
import org.camunda.bpm.engine.task.TaskQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Camunda 7 progresses the workflow after the commit, so phase one only
 * ASKS - and an embedded engine answers from the caller's own transaction, exactly and
 * for free. What used to fail synchronously (a task which is gone, a message nobody
 * waits for) therefore still fails synchronously instead of turning into a log line
 * behind the commit.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7PhaseOneChecksTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static AggregatePersistenceAware<String> persistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<String> getAggregateClass() {
        return String.class;
      }

      @Override
      public Object getAggregateId(
          final String aggregate) {
        return aggregate;
      }

    };

  }

  /**
   * @param executions What an execution query counts (the task's parked execution
   *        respectively an execution waiting for a message)
   * @param userTasks What a task query counts
   * @param messageStartEvents Whether the model of the process starts by the message
   *        'LoanRequested' (any number above zero) or has a plain start event only
   */
  private static Camunda7ProcessService<String> processService(
      final long executions,
      final long userTasks,
      final long messageStartEvents) {

    return processService(executions, userTasks, messageStartEvents, null);

  }

  /**
   * @param executions What an execution query counts (the task's parked execution
   *        respectively an execution waiting for a message)
   * @param userTasks What a task query counts
   * @param messageStartEvents Whether the model of the process starts by the message
   *        'LoanRequested' (any number above zero) or has a plain start event only
   * @param expectedCorrelationId What the waiting executions hold in their local
   *        correlation-id variable
   */
  private static Camunda7ProcessService<String> processService(
      final long executions,
      final long userTasks,
      final long messageStartEvents,
      final String expectedCorrelationId) {

    // built BEFORE stubbing the query: mocking within a 'when' is unfinished stubbing
    final var waitingExecutions = new ArrayList<Execution>();
    for (var index = 0; index < executions; index++) {
      final var execution = Mockito.mock(Execution.class);
      Mockito.when(execution.getId()).thenReturn("execution-"
          + index);
      waitingExecutions.add(execution);
    }

    final var executionQuery = Mockito.mock(ExecutionQuery.class, Mockito.RETURNS_SELF);
    Mockito.when(executionQuery.count()).thenReturn(executions);
    Mockito.when(executionQuery.list()).thenReturn(waitingExecutions);
    final var runtimeService = Mockito.mock(RuntimeService.class);
    Mockito.when(runtimeService.createExecutionQuery()).thenReturn(executionQuery);
    Mockito
        .when(runtimeService.getVariableLocal(Mockito.anyString(), Mockito.anyString()))
        .thenReturn(expectedCorrelationId);

    final var taskQuery = Mockito.mock(TaskQuery.class, Mockito.RETURNS_SELF);
    Mockito.when(taskQuery.count()).thenReturn(userTasks);
    final var taskService = Mockito.mock(TaskService.class);
    Mockito.when(taskService.createTaskQuery()).thenReturn(taskQuery);

    // the newest definition of the process, and its model with or without the message
    final var definition = Mockito.mock(org.camunda.bpm.engine.repository.ProcessDefinition.class);
    Mockito.when(definition.getId()).thenReturn("definition-1");
    final var definitionQuery = Mockito
        .mock(org.camunda.bpm.engine.repository.ProcessDefinitionQuery.class, Mockito.RETURNS_SELF);
    Mockito.when(definitionQuery.singleResult()).thenReturn(definition);
    final var startEvent = org.camunda.bpm.model.bpmn.Bpmn
        .createExecutableProcess(PROCESS)
        .startEvent("LoanRequestStart");
    final var model = (messageStartEvents > 0
        ? startEvent.message("LoanRequested")
        : startEvent)
        .endEvent()
        .done();
    final var repositoryService = Mockito.mock(org.camunda.bpm.engine.RepositoryService.class);
    Mockito.when(repositoryService.createProcessDefinitionQuery()).thenReturn(definitionQuery);
    Mockito.when(repositoryService.getBpmnModelInstance("definition-1")).thenReturn(model);

    return new Camunda7ProcessService<>(
        "camunda7", runtimeService, taskService, repositoryService, null, io.vanillabp.camunda7.TestCollaborators
            .complete());

  }

  /**
   * Runs phase one of the given operation, the way the core does: through the handler
   * the adapter contributes for it.
   *
   * @param testee The process service under test
   * @param operation The operation to run
   * @param args The operation's arguments
   */
  private static void phaseOne(
      final Camunda7ProcessService<String> testee,
      final io.vanillabp.integration.spi.PhaseOperation operation,
      final java.util.Map<String, String> args) {

    testee
        .phaseOperations()
        .get(operation)
        .phaseOne(
            new io.vanillabp.integration.adapter.spi.PhaseOneRequest<String>(
                MODULE, PROCESS, persistence(), "4711", args));

  }

  @Test
  @DisplayName("Completing or canceling a task which is gone fails where the application called it")
  public void goneTaskFailsInPhaseOne() {

    final var testee = processService(0, 0, 0);

    // the type is the one the SPI documents for a task no BPMS knows any more, so an
    // application catches the same thing here as when the platform's probe found out
    final var completing = assertThrows(
        io.vanillabp.spi.process.TaskNotFoundException.class,
        () -> phaseOne(testee, io.vanillabp.integration.spi.PhaseOperation.COMPLETE_TASK,
            java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_TASK_ID, "task-1")));
    assertTrue(completing.getMessage().contains("task-1"), completing.getMessage());
    assertTrue(completing.getMessage().contains("completing"), completing.getMessage());

    // cancelling asks the same question about the same task, which is why the cancel
    // paths are held here rather than by a second run against an engine
    assertThrows(
        io.vanillabp.spi.process.TaskNotFoundException.class,
        () -> phaseOne(testee, io.vanillabp.integration.spi.PhaseOperation.CANCEL_TASK,
            java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_TASK_ID, "task-1")));
    assertThrows(
        io.vanillabp.spi.process.TaskNotFoundException.class,
        () -> phaseOne(testee, io.vanillabp.integration.spi.PhaseOperation.COMPLETE_USER_TASK,
            java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_TASK_ID, "user-task-1")));
    assertThrows(
        io.vanillabp.spi.process.TaskNotFoundException.class,
        () -> phaseOne(testee, io.vanillabp.integration.spi.PhaseOperation.CANCEL_USER_TASK,
            java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_TASK_ID, "user-task-1")));

  }

  @Test
  @DisplayName("A task which is still there passes phase one without advancing anything")
  public void existingTaskPassesPhaseOne() {

    final var testee = processService(1, 1, 0);

    assertDoesNotThrow(() -> phaseOne(testee, io.vanillabp.integration.spi.PhaseOperation.COMPLETE_TASK,
        java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_TASK_ID, "task-1")));
    assertDoesNotThrow(() -> phaseOne(testee, io.vanillabp.integration.spi.PhaseOperation.COMPLETE_USER_TASK,
        java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_TASK_ID, "user-task-1")));

  }

  @Test
  @DisplayName("Correlating a message nobody waits for fails where the application called it")
  public void messageWithoutSubscriptionFailsInPhaseOne() {

    final var testee = processService(0, 0, 0);

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> phaseOne(testee, io.vanillabp.integration.spi.PhaseOperation.CORRELATE_MESSAGE,
            java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_MESSAGE_NAME, "LoanApproved")));
    assertTrue(failure.getMessage().contains("LoanApproved"), failure.getMessage());
    assertTrue(failure.getMessage().contains("4711"), failure.getMessage());

    // with a waiting subscription the call passes - correlating itself happens after
    // the commit
    assertDoesNotThrow(
        () -> phaseOne(processService(1, 0, 0), io.vanillabp.integration.spi.PhaseOperation.CORRELATE_MESSAGE,
            java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_MESSAGE_NAME, "LoanApproved")));

  }

  @Test
  @DisplayName("A correlation id nobody waits for fails where the application called it")
  public void mismatchingCorrelationIdFailsInPhaseOne() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> phaseOne(
            processService(1, 0, 0, "payment-42"),
            io.vanillabp.integration.spi.PhaseOperation.CORRELATE_MESSAGE,
            java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_MESSAGE_NAME, "PaymentReceived",
                io.vanillabp.integration.spi.PhaseTwoCall.ARG_CORRELATION_ID, "wrong-id")));
    assertTrue(failure.getMessage().contains("wrong-id"), failure.getMessage());

    // the counter-check: the id the waiting execution expects passes
    assertDoesNotThrow(
        () -> phaseOne(
            processService(1, 0, 0, "payment-42"),
            io.vanillabp.integration.spi.PhaseOperation.CORRELATE_MESSAGE,
            java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_MESSAGE_NAME, "PaymentReceived",
                io.vanillabp.integration.spi.PhaseTwoCall.ARG_CORRELATION_ID, "payment-42")));

  }

  @Test
  @DisplayName("Starting by a message no start event listens to fails where the application called it")
  public void unknownMessageStartEventFailsInPhaseOne() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> phaseOne(
            processService(0, 0, 0),
            io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW_BY_MESSAGE,
            java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_MESSAGE_NAME, "LoanRequested")));
    assertTrue(failure.getMessage().contains("LoanRequested"), failure.getMessage());
    // the engine refuses such a model, see Camunda7MessageNamedByExpressionTest, so the
    // developer learns that an expression is no way out
    assertTrue(
        failure
            .getMessage()
            .contains("Camunda 7 does not support an expression as the name of a message start event"),
        failure.getMessage());

    assertDoesNotThrow(
        () -> phaseOne(
            processService(0, 0, 1),
            io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW_BY_MESSAGE,
            java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_MESSAGE_NAME, "LoanRequested")));

  }

  @Test
  @DisplayName("Starting a workflow has nothing to ask - it never fails in phase one")
  public void startingAWorkflowPassesPhaseOne() {

    assertDoesNotThrow(
        () -> phaseOne(
            processService(0, 0, 0),
            io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW,
            java.util.Map.of()));

  }

}
