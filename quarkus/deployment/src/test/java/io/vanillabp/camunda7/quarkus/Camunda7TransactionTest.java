package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * THE critical assertion of the Camunda 7 adapter on Quarkus: the
 * embedded engine joins the caller's JTA transaction (the {@code REQUIRED} semantics
 * of the engine's {@code JakartaTransactionInterceptor} on the shared Agroal
 * datasource) - a workflow started inside a {@code UserTransaction} is visible
 * within the transaction (business key = aggregate id, tenant = workflow module id)
 * and is committed or ROLLED BACK together with the JPA workflow aggregate. This is
 * the phase-one guarantee proven by the plain-engine analysis probe, now asserted
 * through the VanillaBP user API.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7TransactionTest {

  private static final String MODULE_ID = "c7-test";

  private static final String BPMN_PROCESS_ID = "TestProcess";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // an own database: the module's shared H2 URL (DB_CLOSE_DELAY=-1) carries the
      // outbox table as well, so aggregate ids of other test classes would collide
      // with these - and an outbox entry of the same idempotency key is deduplicated
      // away, which means the workflow is never started
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:c7-transaction-test;DB_CLOSE_DELAY=-1");

  @Inject
  TestWorkflowService workflowService;

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  @Inject
  EntityManager entityManager;

  @Inject
  UserTransaction userTransaction;

  private long countInstances(
      final String businessKey) {

    return engineRegistry
        .engineFor("c7")
        .getRuntimeService()
        .createProcessInstanceQuery()
        .processDefinitionKey(BPMN_PROCESS_ID)
        .processInstanceBusinessKey(businessKey)
        .tenantIdIn(MODULE_ID)
        .count();

  }

  @Test
  public void startHappensAfterTheCallersTransactionCommitted() throws Exception {

    // pause job processing so started instances stay observable (the trivial
    // ${true} service task would complete them asynchronously)
    final var engine = engineRegistry.engineFor("c7");
    engine.stopWorkflowProcessing(MODULE_ID);
    try {

      // The instance is NOT created within the caller's transaction any
      // more - it is created right after the commit, by the phase-two outbox, so an
      // operation which loses a concurrency conflict can be repeated
      userTransaction.begin();
      final TestAggregate committedAggregate;
      try {
        committedAggregate = workflowService.startWorkflow("commit-test");
        Assertions.assertNotNull(committedAggregate.getId());
        Assertions.assertEquals(
            0,
            countInstances(String.valueOf(committedAggregate.getId())),
            "the process instance must not exist before the commit");
      } catch (final Exception e) {
        userTransaction.rollback();
        throw e;
      }
      userTransaction.commit();

      final var deadline = System.currentTimeMillis() + 15000;
      while (countInstances(String.valueOf(committedAggregate.getId())) == 0) {
        Assertions.assertTrue(
            System.currentTimeMillis() < deadline,
            "the process instance was not started after the commit");
        Thread.sleep(100);
      }

      // rolled-back start: aggregate AND outbox entry are gone, so no instance is
      // ever created - the guarantee the two-phase pattern exists for
      userTransaction.begin();
      final var rolledBackAggregate = workflowService.startWorkflow("rollback-test");
      final var rolledBackId = rolledBackAggregate.getId();
      Assertions.assertNotNull(rolledBackId);
      userTransaction.rollback();

      // reading the aggregate needs an active transaction/persistence context
      userTransaction.begin();
      try {
        Assertions.assertNull(
            entityManager.find(TestAggregate.class, rolledBackId),
            "the aggregate was rolled back with the transaction");
      } finally {
        userTransaction.rollback();
      }
      // long enough for the outbox poller to have run
      Thread.sleep(1500);
      Assertions.assertEquals(
          0,
          countInstances(String.valueOf(rolledBackId)),
          "no process instance was started for a rolled-back transaction");

    } finally {
      engine.startWorkflowProcessing(MODULE_ID);
    }

  }

}
