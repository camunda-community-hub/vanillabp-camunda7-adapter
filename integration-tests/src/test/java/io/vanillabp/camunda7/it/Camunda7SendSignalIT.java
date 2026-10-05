package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.function.Supplier;

import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * End-to-end test of broadcasting a BPMN signal against a real embedded
 * Camunda 7 engine: a workflow waits at an intermediate signal catch event, the
 * broadcast lets it continue, and a broadcast inside a rolled-back transaction
 * changes nothing - the embedded engine shares the caller's transaction, which is
 * exactly what makes that guarantee possible.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: contexts are cached and live in parallel - a foreign engine
    // (and job executor) on the same H2 database would compete for this test's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-signal-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, Spring would keep every context until the JVM exits, and an engine outliving its
// test keeps its job executor running against a database the next classes work on
@DirtiesContext
public class Camunda7SendSignalIT {

  @Autowired
  private SignalTestWorkflowService workflowService;

  @Autowired
  private SignalTestRepository repository;

  @Autowired
  private RuntimeService runtimeService;

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

  private Long startAndAwaitWaiting() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    // the instance itself is created by the phase-two outbox after the commit
    final var instance = AwaitPhaseTwo.untilAvailable(
        () -> runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(String.valueOf(aggregateId))
            .singleResult(),
        "the process instance of aggregate '%s' to be created".formatted(aggregateId));

    awaitUntil(
        () -> runtimeService
            .createEventSubscriptionQuery()
            .processInstanceId(instance.getId())
            .eventType("signal")
            .count() > 0,
        "the workflow to wait at the signal catch event");
    return aggregateId;

  }

  @Test
  @DisplayName("a broadcast signal continues every workflow waiting for it")
  public void broadcastContinuesTheWaitingWorkflow() throws Exception {

    final var first = startAndAwaitWaiting();
    final var second = startAndAwaitWaiting();

    // the application passes the PLAIN signal name - scoping is VanillaBP's business
    transactionTemplate.executeWithoutResult(status -> workflowService.broadcast("OrderReceived"));

    // a signal is a broadcast: BOTH workflows continue
    for (final var aggregateId : java.util.List.of(first, second)) {
      awaitUntil(
          () -> "recordSignal".equals(
              repository
                  .findById(aggregateId)
                  .map(SignalTestAggregate::getProcessedBy)
                  .orElse(null)),
          "the task behind the signal catch event of aggregate '%s' to run".formatted(aggregateId));
    }

  }

  @Test
  @DisplayName("a broadcast in a rolled-back transaction never happens")
  public void rollbackTakesTheBroadcastWithIt() throws Exception {

    final var aggregateId = startAndAwaitWaiting();

    final var exception = assertThrows(
        RuntimeException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          workflowService.broadcast("OrderReceived");
          throw new RuntimeException("test rollback");
        }));
    assertEquals("test rollback", exception.getMessage());

    // the broadcast is scheduled in the outbox, which rolls back with the caller's
    // transaction: the workflow still waits and its task never ran
    Thread.sleep(1500);
    assertNull(
        repository
            .findById(aggregateId)
            .map(SignalTestAggregate::getProcessedBy)
            .orElse(null));

  }

}
