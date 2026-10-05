package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
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
 * Workflow modules kept apart by prefixed identifiers instead of a Camunda tenant. The mode
 * exists for engines an operator does not want tenants on, and it changes what the engine
 * holds: the deployed process carries the module in its own id and no tenant at all.
 * <p>
 * That is a different deployment AND a different runtime - every query for a definition,
 * every start and every task has to ask for the prefixed id - so proving it on Spring Boot
 * says nothing about Quarkus. This is the Quarkus half.
 * <p>
 * The id itself is not written down here. What matters is that the engine no longer holds
 * the plain name and that a workflow still runs from end to end; how the core builds a
 * prefixed identifier is the core's business and its own tests hold it.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7PrefixedIdentifiersTest {

  private static final String MODULE_ID = "c7-test";

  private static final String BPMN_PROCESS_ID = "TestProcess";

  /**
   * How long the workflow may take. Generous: what is asserted is never a moment.
   */
  private static final long PATIENCE = 15000;

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("prefixed-identifiers/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  TestWorkflowService workflowService;

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  @Inject
  EntityManager entityManager;

  @Inject
  UserTransaction userTransaction;

  @Test
  @DisplayName("the module is deployed without a tenant, under an id carrying the module")
  public void theDeployedProcessCarriesTheModuleInsteadOfATenant() {

    final var definitions = engineRegistry
        .engineFor("c7")
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .list();

    Assertions.assertEquals(1, definitions.size(), "the module deploys one process");
    final var definition = definitions.getFirst();
    Assertions.assertNull(definition.getTenantId(), "prefixed identifiers ARE the isolation, so no tenant");
    Assertions
        .assertNotEquals(
            BPMN_PROCESS_ID,
            definition.getKey(),
            "the plain name is what the prefix exists to avoid");
    Assertions
        .assertTrue(
            definition
                .getKey()
                .contains(MODULE_ID),
            () -> "the prefixed id has to carry the workflow module: "
                + definition.getKey());
    Assertions
        .assertTrue(
            definition
                .getKey()
                .endsWith(BPMN_PROCESS_ID),
            () -> "the prefixed id has to end in the modelled name: "
                + definition.getKey());

  }

  @Test
  @DisplayName("a workflow of a prefixed module runs from start to end")
  public void aWorkflowStillRunsUnderAPrefixedId() throws Exception {

    userTransaction.begin();
    final Long aggregateId;
    try {
      aggregateId = workflowService
          .startWorkflow("start")
          .getId();
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    final var deadline = System.currentTimeMillis() + PATIENCE;
    while (!"task-done".equals(contentOf(aggregateId))) {
      Assertions
          .assertTrue(
              System.currentTimeMillis() < deadline,
              () -> "the task of the prefixed process never ran; content: "
                  + contentOf(aggregateId));
      Thread.sleep(100);
    }

  }

  private String contentOf(
      final Long aggregateId) {

    try {
      userTransaction.begin();
      try {
        final var aggregate = entityManager.find(TestAggregate.class, aggregateId);
        return aggregate == null
            ? null
            : aggregate.getContent();
      } finally {
        userTransaction.rollback();
      }
    } catch (final Exception e) {
      throw new IllegalStateException(e);
    }

  }

}
