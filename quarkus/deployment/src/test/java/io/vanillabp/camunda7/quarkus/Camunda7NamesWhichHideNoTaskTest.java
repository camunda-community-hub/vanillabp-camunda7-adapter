package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.namehiding.QNameHidingAggregate;
import io.vanillabp.camunda7.quarkus.namehiding.QNameHidingPersistence;
import io.vanillabp.camunda7.quarkus.namehiding.QNameHidingWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * The model which has to keep working next to the check about hidden task definitions. Its
 * aggregate keeps the attribute named like the task definition to itself, and it shares an
 * attribute named like the element id of a task wired by that id. The start goes on, and a
 * workflow runs both methods.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7NamesWhichHideNoTaskTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(QNameHidingAggregate.class)
          .addClass(QNameHidingPersistence.class)
          .addClass(QNameHidingWorkflowService.class)
          .addAsResource("name-hiding/application.yaml", "application.yaml")
          .addAsResource("c7-name-hiding/processes/name-hiding.bpmn", "c7-test/processes/name-hiding.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey(
          "quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:c7-name-hiding-test;DB_CLOSE_DELAY=-1");

  @Inject
  QNameHidingWorkflowService workflowService;

  @Inject
  EntityManager entityManager;

  @Inject
  UserTransaction userTransaction;

  @Test
  @DisplayName("An unshared attribute and an attribute named like an element id leave the start alone")
  public void namesWhichHideNoTaskLeaveTheStartAlone() throws Exception {

    userTransaction.begin();
    try {
      workflowService.startWorkflow("4711");
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    final var deadline = System.currentTimeMillis() + 30_000;
    while (!bothMethodsRan("4711")) {
      Assertions.assertTrue(
          System.currentTimeMillis() < deadline,
          "both methods should run: the one of the task definition and the one of the element id");
      Thread.sleep(100);
    }

  }

  private boolean bothMethodsRan(
      final String id) throws Exception {

    userTransaction.begin();
    try {
      final var aggregate = entityManager.find(QNameHidingAggregate.class, id);
      return (aggregate != null) && aggregate.isCharged() && aggregate.isReviewed();
    } finally {
      userTransaction.rollback();
    }

  }

}
