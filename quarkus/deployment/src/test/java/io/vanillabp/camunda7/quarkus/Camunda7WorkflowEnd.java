package io.vanillabp.camunda7.quarkus;

import org.junit.jupiter.api.Assertions;

import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineHolder;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * Runs the test process on one engine and waits until it ended. The task handler can commit
 * while the engine's job rolls back afterwards. So a test which waits for the handler only
 * does not see whether the engine's transaction works.
 */
final class Camunda7WorkflowEnd {

  private static final String MODULE_ID = "c7-test";

  private static final String BPMN_PROCESS_ID = "TestProcess";

  private Camunda7WorkflowEnd() {
  }

  /**
   * Saves an aggregate, starts the test process for it in the engine directly, and fails the
   * test if the workflow did not end within 20 seconds.
   *
   * @param engine The engine to run the workflow on
   * @param entityManager The application's persistence
   * @param userTransaction The transaction the aggregate is saved in
   * @throws Exception If saving the aggregate fails
   */
  static void runsToTheEnd(
      final Camunda7QuarkusEngineHolder engine,
      final EntityManager entityManager,
      final UserTransaction userTransaction) throws Exception {

    userTransaction.begin();
    final var aggregate = new TestAggregate();
    aggregate.setContent("started");
    entityManager.persist(aggregate);
    entityManager.flush();
    final var businessKey = String.valueOf(aggregate.getId());
    userTransaction.commit();

    engine
        .getRuntimeService()
        .createProcessInstanceByKey(BPMN_PROCESS_ID)
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(businessKey)
        .execute();

    final var deadline = System.currentTimeMillis() + 20_000;
    while (engine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(businessKey)
        .finished()
        .count() == 0) {
      Assertions
          .assertTrue(
              System.currentTimeMillis() < deadline,
              "the workflow on engine '%s' did not end within 20s".formatted(engine.getAdapterId()));
      Thread.sleep(50);
    }

  }

}
