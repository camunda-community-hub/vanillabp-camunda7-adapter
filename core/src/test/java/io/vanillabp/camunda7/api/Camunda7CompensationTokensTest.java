package io.vanillabp.camunda7.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What Camunda 7 really does with two compensation handlers of one workflow. The startup
 * warning about two writers on one workflow aggregate says what an engine can do, so what
 * this engine does had to be measured rather than assumed.
 * <p>
 * Measured on 2026-09-27 against the embedded engine of Camunda 7.24, on H2, with the model
 * of <code>api/compensation-facts.bpmn</code>: a throw event which compensates two finished
 * service tasks creates both compensating executions first and then signals them ONE AT A
 * TIME. The second handler starts once the first one returned, so no two handlers are inside
 * their delegate at the same moment.
 * <p>
 * The ORDER is not asserted, because it is not stable. The engine sorts the compensation
 * subscriptions by their creation time, which is a <code>java.util.Date</code>, and sorts
 * stably - so two activities whose subscriptions were created in the same millisecond are
 * compensated in the order they ran in rather than in the reverse one. Both orders were
 * observed in this very test within an hour, which is exactly why VanillaBP promises nothing
 * about the order of compensation.
 * <p>
 * That holds for the model as a modeller writes it AND for the model as this adapter deploys
 * it. The adapter sets <code>asyncBefore</code> on every service task, the flag is set on the
 * handlers too, and the engine ignores it: it starts a compensation handler outside the
 * normal flow, where no job is created. One job exists at a time, and with the job executor
 * running both handlers ran on one thread.
 * <p>
 * Why the adapter reports compensation to the core all the same is the decision this test
 * belongs to: the check asks whether the process can hold more than one token, and both
 * compensating executions exist from the moment the throw event runs. A handler which WAITS,
 * a user task among them, also keeps its token while the next handler is started.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7CompensationTokensTest {

  private static final String FIRST_COMPENSATED = "CancelBooking";

  private static final String SECOND_COMPENSATED = "RefundPayment";

  /**
   * Both handlers, as a set: WHICH of them ran is a fact of this engine, in which order is
   * not. See the class comment.
   */
  private static final Set<String> BOTH_HANDLERS = Set.of(FIRST_COMPENSATED, SECOND_COMPENSATED);

  @Test
  @DisplayName("Two compensation handlers run one after the other, never at the same time")
  public void twoHandlersRunOneAfterTheOther() {

    CompensationRecorder.reset();
    try (var engine = AnEngineRunningCompensation.plain("compensation-plain")) {

      engine.start();

      assertEquals(
          BOTH_HANDLERS, Set.copyOf(CompensationRecorder.handlersInTheOrderTheyWereEntered()));
      assertEquals(2, CompensationRecorder.handlersInTheOrderTheyWereEntered().size());
      assertEquals(
          1,
          CompensationRecorder.mostHandlersInsideAtOnce(),
          "each handler waited for the other one and never met it");
      assertEquals(1, CompensationRecorder.threadsTheHandlersRanOn().stream().distinct().count());
      assertEquals(0, engine.runningWorkflows(), "the workflow ran to its end");

    }

  }

  @Test
  @DisplayName("Both compensating executions exist while the first handler runs")
  public void bothTokensExistWhileTheFirstHandlerRuns() {

    CompensationRecorder.reset();
    try (var engine = AnEngineRunningCompensation.plain("compensation-tokens")) {

      engine.start();

      // the engine spawns a concurrent execution per handler before it signals any of them,
      // so the workflow holds two tokens although only one of them is being worked on
      assertEquals(List.of(2, 1), CompensationRecorder.siblingExecutions());

    }

  }

  @Test
  @DisplayName("The asyncBefore this adapter sets does not turn a handler into a job")
  public void aCompensationHandlerIsNoJobOfItsOwn() {

    CompensationRecorder.reset();
    try (var engine = AnEngineRunningCompensation.asThisAdapterDeploysIt("compensation-async", false)) {

      assertTrue(engine.asyncBeforeOf(FIRST_COMPENSATED), "the parse listener set the flag");
      assertTrue(engine.asyncBeforeOf(SECOND_COMPENSATED), "the parse listener set the flag");

      engine.start();
      var mostJobsAtOnce = 0;
      for (var round = 0; round < 20; round++) {
        final var jobs = engine.jobs();
        mostJobsAtOnce = Math.max(mostJobsAtOnce, jobs.size());
        if (jobs.isEmpty()) {
          break;
        }
        assertTrue(
            AnEngineRunningCompensation.isExclusive(jobs.getFirst()),
            "an exclusive job is what makes two jobs of one workflow run one after the other");
        engine.executeJob(jobs.getFirst().getId());
      }

      assertEquals(1, mostJobsAtOnce, "the engine never held two jobs of this workflow at once");
      assertEquals(
          BOTH_HANDLERS, Set.copyOf(CompensationRecorder.handlersInTheOrderTheyWereEntered()));
      assertEquals(1, CompensationRecorder.mostHandlersInsideAtOnce());
      assertEquals(0, engine.runningWorkflows());

    }

  }

  @Test
  @DisplayName("The job executor runs both handlers on one thread")
  public void theJobExecutorRunsBothHandlersOnOneThread() throws Exception {

    CompensationRecorder.reset();
    try (var engine = AnEngineRunningCompensation.asThisAdapterDeploysIt("compensation-executor", true)) {

      engine.start();
      for (var waited = 0; (waited < 30000) && (engine.runningWorkflows() > 0); waited += 200) {
        Thread.sleep(200);
      }

      assertEquals(0, engine.runningWorkflows(), "the job executor finished the workflow");
      assertEquals(
          BOTH_HANDLERS, Set.copyOf(CompensationRecorder.handlersInTheOrderTheyWereEntered()));
      assertEquals(1, CompensationRecorder.mostHandlersInsideAtOnce());
      assertEquals(1, CompensationRecorder.threadsTheHandlersRanOn().stream().distinct().count());

    }

  }

}
