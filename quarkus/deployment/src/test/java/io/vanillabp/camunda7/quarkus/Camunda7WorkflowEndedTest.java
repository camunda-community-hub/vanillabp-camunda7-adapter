package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.workflowended.EndedAggregate;
import io.vanillabp.camunda7.quarkus.workflowended.EndedPersistence;
import io.vanillabp.camunda7.quarkus.workflowended.EndedWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * A <code>&#64;WorkflowEnded</code> method has to be called when the
 * workflow reaches its end event - on Quarkus it never was, because the engine
 * producer built the engine without the core's invoker and the engine therefore
 * attached no end listener. Nothing failed and nothing was logged; the method simply
 * stayed silent.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7WorkflowEndedTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(EndedAggregate.class)
          .addClass(EndedPersistence.class)
          .addClass(EndedWorkflowService.class)
          .addAsResource("application.yaml")
          .addAsResource("c7-ended/processes/ended-process.bpmn", "c7-test/processes/ended-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:c7-ended-test;DB_CLOSE_DELAY=-1");

  @Inject
  EndedWorkflowService workflowService;

  @Inject
  EntityManager entityManager;

  @Inject
  UserTransaction userTransaction;

  @Test
  @DisplayName("The @WorkflowEnded method is called when the workflow reaches its end event")
  public void workflowEndedIsReported() throws Exception {

    userTransaction.begin();
    final Long aggregateId;
    try {
      aggregateId = workflowService
          .startWorkflow("ended")
          .getId();
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    // the service task and the end of the workflow happen in the job executor
    final var deadline = System.currentTimeMillis() + 30000;
    String endedWith = null;
    while ((endedWith == null) && (System.currentTimeMillis() < deadline)) {
      Thread.sleep(100);
      userTransaction.begin();
      try {
        endedWith = entityManager
            .find(EndedAggregate.class, aggregateId)
            .getEndedWith();
      } finally {
        userTransaction.commit();
      }
    }

    Assertions.assertEquals(
        "ended:COMPLETED/EndEvent_1",
        endedWith,
        "the @WorkflowEnded method was not called - the engine attached no end listener");

  }

}
