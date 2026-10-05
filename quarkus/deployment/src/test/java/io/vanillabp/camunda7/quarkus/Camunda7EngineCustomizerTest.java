package io.vanillabp.camunda7.quarkus;

import java.util.Set;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import io.vanillabp.camunda7.quarkus.sample.RecordingEngineCustomizer;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * The engine hooks an extension needs, on Quarkus: a {@code Camunda7EngineCustomizer}
 * bean is asked for the adapter id whose engine is being built, the parse listener it
 * contributes sees the deployed models, and the engine's history events reach the handler
 * it contributed - installed as a composite, so history is still written as before.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7EngineCustomizerTest {

  private static final String MODULE_ID = "c7-test";

  private static final String BPMN_PROCESS_ID = "TestProcess";

  private static final String ADAPTER_ID = "c7";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addClass(RecordingEngineCustomizer.class)
          .addAsResource("engine-customizer/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  RecordingEngineCustomizer customizer;

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  @Inject
  EntityManager entityManager;

  @Inject
  UserTransaction userTransaction;

  @Test
  @DisplayName("The customizer is asked for the adapter id whose engine is built")
  public void theCustomizerIsAskedPerAdapterId() {

    Assertions.assertEquals(Set.of(ADAPTER_ID), customizer.getAskedAdapterIds());

  }

  @Test
  @DisplayName("The parse listener the customizer contributes sees the deployed model")
  public void theParseListenerSeesTheModel() {

    Assertions
        .assertFalse(
            customizer.getParsedServiceTasks().isEmpty(),
            "the parse listener saw no service task of the deployed model");

  }

  @Test
  @DisplayName("The history events of a running workflow reach the contributed handler")
  public void theHistoryHandlerReceivesTheEvents() throws Exception {

    userTransaction.begin();
    final var aggregate = new TestAggregate();
    aggregate.setContent("history");
    entityManager.persist(aggregate);
    entityManager.flush();
    final var aggregateId = aggregate.getId();
    userTransaction.commit();

    engineRegistry
        .engineFor(ADAPTER_ID)
        .getRuntimeService()
        .createProcessInstanceByKey(BPMN_PROCESS_ID)
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregateId))
        .execute();

    final var expected = "%s/start/%s".formatted(ADAPTER_ID, BPMN_PROCESS_ID);
    final var deadline = System.currentTimeMillis() + 20_000;
    while (!customizer.getProcessInstanceHistory().contains(expected)) {
      Assertions
          .assertTrue(
              System.currentTimeMillis() < deadline,
              "the history event of the started workflow did not arrive within 20s: %s"
                  .formatted(customizer.getProcessInstanceHistory()));
      Thread.sleep(100);
    }

    // history is still WRITTEN, which is what the composite handler is for
    Assertions
        .assertEquals(
            1,
            engineRegistry
                .engineFor(ADAPTER_ID)
                .getHistoryService()
                .createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(String.valueOf(aggregateId))
                .count());

  }

}
