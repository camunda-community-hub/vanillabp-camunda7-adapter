package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A call activity which names the process to call in an EXPRESSION, against a real
 * embedded Camunda 7 engine.
 * <p>
 * Camunda 7 does not pass its business key - which carries the workflow aggregate's id -
 * to a called process, so the task of a called process finds the aggregate of its caller
 * only because this adapter arranges for the key to travel. Where the process to call is
 * named by an expression, the model being deployed does not say which process that is,
 * and these tests measure what the called process gets then. The same model runs on
 * Camunda 8, so both ways of naming the called process have to end in the same place.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: contexts are cached and live in parallel, and a second engine with a
    // job executor on the same H2 database would compete for this test's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-call-by-expression-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, and an engine outliving its test keeps its job executor running against a database
// the next classes work on
@DirtiesContext
public class Camunda7CallByExpressionIT {

  @Autowired
  private CallByExpressionWorkflowService workflowService;

  @Autowired
  private CallByExpressionRepository repository;

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

  /**
   * What the called process wrote into the aggregate the caller was started with, read
   * fresh from the database because the task runs in the engine's own transaction.
   */
  private String whatTheCalledProcessWrote(
      final Long aggregateId) {

    return repository
        .findById(aggregateId)
        .map(CallByExpressionAggregate::getWhatTheCalledProcessWrote)
        .orElse(null);

  }

  private Long startCaller(
      final boolean callByExpression) {

    final var aggregate = new CallByExpressionAggregate();
    aggregate.setCallByExpression(callByExpression);
    aggregate.setProcessToCall("CallByExpressionChild");
    return workflowService.startCaller(aggregate).getId();

  }

  @Test
  @DisplayName("The task of a process called by its id runs on the aggregate of its caller")
  public void aProcessCalledByItsIdReachesTheCallersAggregate() throws Exception {

    final var aggregateId = startCaller(false);

    awaitUntil(
        () -> whatTheCalledProcessWrote(aggregateId) != null,
        "the task of the called process to run");

    assertNotNull(
        whatTheCalledProcessWrote(aggregateId),
        "the called process reached the aggregate its caller was started with");

  }

  @Test
  @DisplayName("The task of a process called by an expression runs on the aggregate of its caller")
  public void aProcessCalledByAnExpressionReachesTheCallersAggregate() throws Exception {

    final var aggregateId = startCaller(true);

    awaitUntil(
        () -> whatTheCalledProcessWrote(aggregateId) != null,
        "the task of the called process to run");

    assertNotNull(
        whatTheCalledProcessWrote(aggregateId),
        "the called process reached the aggregate its caller was started with");

  }

}
