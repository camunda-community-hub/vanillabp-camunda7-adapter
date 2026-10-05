package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.callactivity.CallActivityAggregate;
import io.vanillabp.camunda7.quarkus.callactivity.CallActivityPersistence;
import io.vanillabp.camunda7.quarkus.callactivity.CallActivityWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * Camunda 7 does not pass its business key to a called process, and the
 * business key is where VanillaBP keeps the workflow aggregate's ID. Without it the
 * first task of the called process failed while resolving the aggregate, with the
 * persistence's own message - naming neither the call activity nor the business key
 * nor VanillaBP.
 * <p>
 * The model here deliberately declares NO {@code camunda:in businessKey}: VanillaBP
 * injects it while preparing the BPMN, because both processes work on the same
 * aggregate.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7CallActivityTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(CallActivityAggregate.class)
          .addClass(CallActivityPersistence.class)
          .addClass(CallActivityWorkflowService.class)
          .addAsResource("application.yaml")
          .addAsResource("c7-callactivity/processes/calling.bpmn", "c7-test/processes/calling.bpmn")
          .addAsResource("c7-callactivity/processes/called.bpmn", "c7-test/processes/called.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:c7-callactivity-test;DB_CLOSE_DELAY=-1");

  @Inject
  CallActivityWorkflowService workflowService;

  @Inject
  EntityManager entityManager;

  @Inject
  UserTransaction userTransaction;

  @Test
  @DisplayName("A called process on the same aggregate reaches it - the business key is passed")
  public void calledProcessFindsTheWorkflowAggregate() throws Exception {

    userTransaction.begin();
    final Long aggregateId;
    try {
      aggregateId = workflowService
          .startWorkflow()
          .getId();
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    // call activity and the task of the called process run in the job executor
    final var deadline = System.currentTimeMillis() + 30000;
    String calledProcessDid = null;
    while ((calledProcessDid == null) && (System.currentTimeMillis() < deadline)) {
      Thread.sleep(100);
      userTransaction.begin();
      try {
        calledProcessDid = entityManager
            .find(CallActivityAggregate.class, aggregateId)
            .getCalledProcessDid();
      } finally {
        userTransaction.commit();
      }
    }

    Assertions.assertEquals(
        "its-work",
        calledProcessDid,
        "the called process did not reach the workflow aggregate - it ran without the business key");

  }

}
