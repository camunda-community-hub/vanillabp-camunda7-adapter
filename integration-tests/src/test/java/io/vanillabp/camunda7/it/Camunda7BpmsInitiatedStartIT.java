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
 * End-to-end test of a workflow the engine starts on its own, against a real embedded
 * Camunda 7 engine. A timer start event fires, the application's
 * <code>&#64;WorkflowStartedByBpms</code> method builds the workflow aggregate, and the
 * adapter stores its ID as the instance's business key. The task following the start event
 * then finds the aggregate under exactly that key.
 * <p>
 * The application has to serve such a start: a process the engine starts by itself does not
 * boot without that method.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: contexts are cached and live in parallel - a foreign engine
    // (and job executor) on the same H2 database would compete for this test's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-timer-start-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, Spring would keep every context until the JVM exits, and an engine outliving its
// test keeps its job executor running against a database the next classes work on
@DirtiesContext
public class Camunda7BpmsInitiatedStartIT {

  @Autowired
  private TimerStartTestRepository repository;

  @Autowired
  private RuntimeService runtimeService;

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

    // the timer fires one second after the deployment, and the job executor is
    // running because the workflow module started its processing
    awaitUntil(() -> !repository.findAll().isEmpty(), "the timer to fire and the aggregate to be created");

    final var aggregates = repository.findAll();
    assertEquals(1, aggregates.size(), "one workflow, one aggregate");
    final var aggregate = aggregates.getFirst();

    // the application named the workflow, and the engine holds that name as the
    // business key from here on
    assertNotNull(aggregate.getId());
    assertTrue(
        aggregate.getId().startsWith("timer-start-"),
        "the aggregate carries the name the application gave it: "
            + aggregate.getId());
    assertEquals("TIMER", aggregate.getStartedBy(), "the trigger says which start event fired");

    // the task after the start event ran against exactly that aggregate, which
    // proves the business key was set from it
    awaitUntil(
        () -> "recordStart".equals(
            repository
                .findById(aggregate.getId())
                .map(TimerStartTestAggregate::getProcessedBy)
                .orElse(null)),
        "the task following the timer start event to be processed");

    // the instance carries the aggregate's ID as its business key, which is how
    // every other operation of this adapter addresses a workflow
    awaitUntil(
        () -> runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(aggregate.getId())
            .count() == 0,
        "the workflow started by the timer to run to its end");

    // The end is reported to the application, in the transaction which
    // ended the workflow - so it is visible as soon as the instance is gone
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
