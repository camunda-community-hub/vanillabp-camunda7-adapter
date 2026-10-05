package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which modelled paths this engine reports as a canceled workflow, measured against a
 * real embedded engine. The kind comes from the delete reason the execution carries when
 * the process scope ends, and the question is which paths set one.
 * <p>
 * The two paths here are the ones a modeller would call a cancelation while looking at
 * the model, and neither of them is one. Both end the instance without a delete reason,
 * so the application hears a completion. Camunda 8 answers the same, which is why the
 * SPI promises no mapping of modelled paths any more and every adapter measures its own
 * engine.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: contexts are cached and live in parallel, and a foreign job executor
    // on the same H2 database would compete for the timers this test waits for
    "spring.datasource.url=jdbc:h2:mem:c7-end-kind-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@DirtiesContext
public class Camunda7WorkflowEndKindIT {

  @Autowired
  private EndKindTerminateWorkflowService terminateWorkflowService;

  @Autowired
  private EndKindTerminateRepository terminateRepository;

  @Autowired
  private EndKindEventSubprocessWorkflowService eventSubprocessWorkflowService;

  @Autowired
  private EndKindEventSubprocessRepository eventSubprocessRepository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(100);
    }

  }

  @Test
  @DisplayName("A terminate end event completes the workflow, it does not cancel it")
  public void aTerminateEndEventCompletesTheWorkflow() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> terminateWorkflowService.startWorkflow().getId());

    awaitUntil(
        () -> endedAs(() -> terminateRepository.findById(aggregateId).orElseThrow().getEndedAs()) != null,
        "the workflow to run into its terminate end event");

    // measured twice on 2026-09-20 against the embedded engine of this build: the
    // execution carries NO delete reason here, so the application hears a completion and
    // the terminate end event is named as the end event reached
    assertEquals(
        "COMPLETED/Event_Terminate",
        endedAs(() -> terminateRepository.findById(aggregateId).orElseThrow().getEndedAs()),
        "the engine ends a terminated instance without a delete reason");

  }

  @Test
  @DisplayName("An interrupting event subprocess completes the workflow, it does not cancel it")
  public void anInterruptingEventSubprocessCompletesTheWorkflow() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> eventSubprocessWorkflowService.startWorkflow().getId());

    awaitUntil(
        () -> endedAs(
            () -> eventSubprocessRepository.findById(aggregateId).orElseThrow().getEndedAs()) != null,
        "the event subprocess to interrupt the waiting workflow");

    // measured twice on 2026-09-20, same build and same engine: the waiting token is
    // taken away and the instance still ends without a delete reason. The element named
    // is the one the instance ended at, which is the event subprocess and not an end
    // event, so a reader of that id may not assume it is one
    assertEquals(
        "COMPLETED/Activity_TakeOver",
        endedAs(() -> eventSubprocessRepository.findById(aggregateId).orElseThrow().getEndedAs()),
        "an interrupted instance ends without a delete reason too");

  }

  private String endedAs(
      final Supplier<String> read) {

    return transactionTemplate.execute(status -> read.get());

  }

}
