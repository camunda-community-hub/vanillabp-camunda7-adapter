package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.listeners.ListenerAggregate;
import io.vanillabp.camunda7.quarkus.listeners.ListenerPersistence;
import io.vanillabp.camunda7.quarkus.listeners.ListenerWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * A BPMN execution listener somebody modelled, served by a {@code @WorkflowTask} method on
 * Quarkus. The Spring Boot half of the feature had a test from the start; this is the half
 * which ran nowhere, so nothing said whether the adapter's EL resolver hands the engine a
 * listener on this platform at all.
 * <p>
 * Both forms are on one end event, because they take different routes through the resolver:
 * a {@code camunda:expression} is evaluated and the handler runs while it is, a
 * {@code camunda:delegateExpression} has to yield a listener object which the engine then
 * notifies. Each sets a flag of its own, so a half-working resolver cannot look complete.
 * <p>
 * The feature is off until an application asks for it, which is the second test here: the
 * same model without {@code allow-listeners} must not deploy at all.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7ModelledListenerTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(ListenerAggregate.class)
          .addClass(ListenerPersistence.class)
          .addClass(ListenerWorkflowService.class)
          .addAsResource("modelled-listeners/application.yaml", "application.yaml")
          .addAsResource(
              "c7-listeners/processes/listener-process.bpmn",
              "c7-listeners/processes/listener-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  /**
   * How long the workflow may take. Generous: what is asserted is never a moment, and a
   * build machine running other builds is allowed to be slow.
   */
  private static final long PATIENCE = 15000;

  @Inject
  ListenerWorkflowService workflowService;

  @Inject
  EntityManager entityManager;

  @Inject
  UserTransaction userTransaction;

  @Test
  @DisplayName("both modelled listener forms reach a @WorkflowTask method")
  public void bothListenerFormsReachTheApplication() throws Exception {

    userTransaction.begin();
    final Long aggregateId;
    try {
      aggregateId = workflowService
          .start()
          .getId();
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    // the workflow is created after the commit, so its end is the signal rather than the
    // instance ever having existed
    final var deadline = System.currentTimeMillis() + PATIENCE;
    var aggregate = reload(aggregateId);
    while (!aggregate.isTheEndWasDone()) {
      Assertions
          .assertTrue(
              System.currentTimeMillis() < deadline,
              "the listeners of the end event did not run in time");
      Thread.sleep(100);
      aggregate = reload(aggregateId);
    }

    Assertions.assertTrue(aggregate.isTheWorkWasDone(), "the service task has to have run");
    Assertions
        .assertTrue(
            aggregate.isTheEndWasReached(),
            "the listener written as a camunda:expression has to have run");
    Assertions
        .assertTrue(
            aggregate.isTheEndWasDone(),
            "the listener written as a camunda:delegateExpression has to have run");

  }

  private ListenerAggregate reload(
      final Long aggregateId) throws Exception {

    userTransaction.begin();
    try {
      return entityManager.find(ListenerAggregate.class, aggregateId);
    } finally {
      userTransaction.rollback();
    }

  }

}
