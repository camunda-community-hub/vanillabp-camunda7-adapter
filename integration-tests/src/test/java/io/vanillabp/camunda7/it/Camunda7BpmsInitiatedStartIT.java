package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * End-to-end test of a workflow which the engine starts on its own, against a real embedded
 * Camunda 7 engine. A timer start event fires. The <code>&#64;WorkflowStartedByBpms</code>
 * method of the application builds the workflow aggregate. The adapter stores its ID as the
 * business key of the instance. The task after the start event then finds the aggregate
 * under exactly that key.
 * <p>
 * The application has to serve such a start: a process the engine starts by itself does not
 * boot without that method. This test does not check that half. It only runs the case where
 * the method is there.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database. Contexts are cached and live in parallel. A foreign engine and its job
    // executor on the same H2 database would compete for the jobs of this test.
    "spring.datasource.url=jdbc:h2:mem:c7-timer-start-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done. This IT has a database of its own, and so a context of its
// own. Spring would keep every context until the JVM exits. An engine which outlives its test
// keeps its job executor running against a database which the next classes work on.
@DirtiesContext
public class Camunda7BpmsInitiatedStartIT {

  @Autowired
  private TimerStartTestRepository repository;

  @Autowired
  private RuntimeService runtimeService;

  /**
   * The adapter publishes no bean for the history. So it is taken from the engine itself.
   */
  @Autowired
  private org.camunda.bpm.engine.ProcessEngine processEngine;

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
  @DisplayName("a timer start event creates the workflow aggregate and the following task finds it")
  public void timerStartCreatesTheAggregate() throws Exception {

    // the timer fires one second after the deployment. The job executor runs, because the
    // workflow module started its processing.
    awaitUntil(() -> !repository.findAll().isEmpty(), "the timer to fire and the aggregate to be created");

    final var aggregates = repository.findAll();
    assertEquals(1, aggregates.size(), "one workflow, one aggregate");
    final var aggregate = aggregates.getFirst();

    // the application named the workflow, and from here on the engine holds that name as
    // the business key
    assertNotNull(aggregate.getId());
    assertTrue(
        aggregate.getId().startsWith("timer-start-"),
        "the aggregate carries the name the application gave it: "
            + aggregate.getId());
    assertEquals("TIMER", aggregate.getStartedBy(), "the trigger says which start event fired");

    // the task after the start event ran against exactly that aggregate. This proves that
    // the business key was set from it.
    awaitUntil(
        () -> "recordStart".equals(
            repository
                .findById(aggregate.getId())
                .map(TimerStartTestAggregate::getProcessedBy)
                .orElse(null)),
        "the task following the timer start event to be processed");

    // the workflow runs to its end
    awaitUntil(
        () -> runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(aggregate.getId())
            .count() == 0,
        "the workflow started by the timer to run to its end");
    // and the instance carried the aggregate's ID as its business key, which is how
    // every other operation of this adapter addresses a workflow
    assertEquals(
        1,
        processEngine
            .getHistoryService()
            .createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(aggregate.getId())
            .count(),
        "the instance the timer started carries the aggregate's id as its business key");

    // The end is reported to the application in the transaction which ended the workflow.
    // So it is visible as soon as the instance is gone.
    awaitUntil(
        () -> repository
            .findById(aggregate.getId())
            .map(TimerStartTestAggregate::getEndedAs)
            .filter(endedAs -> endedAs.startsWith("COMPLETED/"))
            .isPresent(),
        "the end of the workflow to be reported: "
            + repository.findById(aggregate.getId()).map(TimerStartTestAggregate::getEndedAs).orElse(null));

  }

}
