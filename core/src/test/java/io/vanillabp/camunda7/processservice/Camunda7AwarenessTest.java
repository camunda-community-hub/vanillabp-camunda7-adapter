package io.vanillabp.camunda7.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.camunda.bpm.engine.BadUserRequestException;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.TaskService;
import org.camunda.bpm.engine.runtime.ExecutionQuery;
import org.camunda.bpm.engine.task.TaskQuery;
import org.camunda.bpm.impl.juel.jakarta.el.ELException;
import org.camunda.bpm.impl.juel.jakarta.el.MethodNotFoundException;
import org.camunda.bpm.impl.juel.jakarta.el.PropertyNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the adapter answers when its engine cannot answer at all, and how it classifies a
 * failed phase-two attempt. Both decisions are invisible in a green system and expensive
 * when they are wrong: an engine on its own datasource which is down must never look like
 * "this workflow is not mine" (the election would hand the workflow to the next
 * adapter), and an outbox entry the engine rejects as wrong must not be retried forever.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7AwarenessTest {

  /**
   * What a probe is asked about.
   */
  private static final io.vanillabp.integration.adapter.spi.WorkflowScope SCOPE = io.vanillabp.integration.adapter.spi.WorkflowScope
      .of("test-module", "TestProcess");

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
   * A process service whose engine throws on every query - what an engine with its own
   * datasource does while the database is unreachable.
   */
  private static Camunda7ProcessService<String> withUnreachableEngine() {

    final var failure = new ProcessEngineException("the engine's datasource is unreachable");

    final var executionQuery = Mockito.mock(ExecutionQuery.class, Mockito.RETURNS_SELF);
    Mockito.when(executionQuery.singleResult()).thenThrow(failure);
    final var runtimeService = Mockito.mock(RuntimeService.class);
    Mockito.when(runtimeService.createExecutionQuery()).thenReturn(executionQuery);
    Mockito.when(runtimeService.createProcessInstanceQuery()).thenThrow(failure);

    final var taskQuery = Mockito.mock(TaskQuery.class, Mockito.RETURNS_SELF);
    Mockito.when(taskQuery.singleResult()).thenThrow(failure);
    final var taskService = Mockito.mock(TaskService.class);
    Mockito.when(taskService.createTaskQuery()).thenReturn(taskQuery);

    final var historyService = Mockito.mock(HistoryService.class);

    return new Camunda7ProcessService<>(
        "camunda7", runtimeService, taskService, null, historyService, io.vanillabp.camunda7.TestCollaborators
            .complete());

  }

  @Test
  @DisplayName("An engine which cannot answer reports BPMS_UNAVAILABLE, never 'not mine'")
  public void anUnreachableEngineReportsBpmsUnavailable() {

    final var testee = withUnreachableEngine();

    // UNKNOWN_TO_BPMS would let the election move on to the next adapter and start or
    // complete the workflow a second time somewhere else
    assertEquals(WorkflowAwareness.BPMS_UNAVAILABLE, testee.awarenessOfTask(SCOPE, "4711", "execution-1"));
    assertEquals(WorkflowAwareness.BPMS_UNAVAILABLE, testee.awarenessOfUserTask(SCOPE, "4711", "user-task-1"));
    assertEquals(WorkflowAwareness.BPMS_UNAVAILABLE, testee.awarenessOfWorkflow(SCOPE, persistence(), "4711"));

  }

  @Test
  @DisplayName("A request the engine rejects as wrong is not repeated, everything else is")
  public void onlyARejectedRequestStopsBeingRetried() {

    final var testee = withUnreachableEngine();

    // a task id which does not exist looks the same on every attempt, so repeating it
    // would keep the outbox entry moving without ever getting anywhere
    assertFalse(testee.isPhaseTwoFailureRepeatable(new BadUserRequestException("no such task")));
    assertFalse(
        testee
            .isPhaseTwoFailureRepeatable(
                new IllegalStateException("wrapped", new BadUserRequestException("no such task"))));

    // the conflict this adapter went two-phase for: the next attempt simply wins
    assertTrue(
        testee
            .isPhaseTwoFailureRepeatable(
                new org.camunda.bpm.engine.OptimisticLockingException("another transaction was faster")));
    assertTrue(testee.isPhaseTwoFailureRepeatable(new ProcessEngineException("the database hiccupped")));

  }

  @Test
  @DisplayName("An expression the engine cannot evaluate is not repeated")
  public void aBrokenExpressionStopsBeingRetried() {

    final var testee = withUnreachableEngine();

    // How the engine reports a model which calls a method the value has not got: the
    // JUEL exception is the cause of the ProcessEngineException the command throws
    // (JuelExpression#getValue). A conditional start event of an event subprocess is
    // evaluated while the instance is created, so this arrives as a failed START and
    // repeating it produces the same sentence every time.
    assertFalse(
        testee
            .isPhaseTwoFailureRepeatable(
                new ProcessEngineException(
                    "Unknown method used in expression: ${order.getTotal() > 100}", new MethodNotFoundException("Method not found: class java.util.LinkedHashMap.getTotal()"))));

    // the second shape of the same thing: the value is there and has no such property
    assertFalse(
        testee
            .isPhaseTwoFailureRepeatable(
                new ProcessEngineException(
                    "Unknown property used in expression: ${order.dueDate.year}", new PropertyNotFoundException(
                        "The class 'java.lang.String' does not have the property 'year'"))));

    // the ancestor both of those carry, which is what the adapter actually looks for
    assertFalse(
        testee.isPhaseTwoFailureRepeatable(new ProcessEngineException("evaluation failed", new ELException("no"))));

    // whatever wrapped the engine's exception on the way through the outbox must not
    // hide the verdict, so the whole cause chain is read and not just the first link
    assertFalse(
        testee
            .isPhaseTwoFailureRepeatable(
                new IllegalStateException(
                    "dispatching failed", new ProcessEngineException(
                        "Unknown method used in expression: ${order.getTotal() > 100}", new MethodNotFoundException("Method not found")))));

  }

  @Test
  @DisplayName("A failure whose cause is itself does not stop the classification")
  public void aSelfReferencingCauseTerminates() {

    final var testee = withUnreachableEngine();

    final var selfReferencing = new RuntimeException("looping") {

      private static final long serialVersionUID = 1L;

      @Override
      public synchronized Throwable getCause() {
        return this;
      }

    };

    assertTrue(testee.isPhaseTwoFailureRepeatable(selfReferencing));

  }

}
