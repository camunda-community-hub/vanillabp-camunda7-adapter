package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The engine hooks an extension needs on Camunda 7, end to end against a real embedded
 * engine: a parse listener contributed by a {@code Camunda7EngineCustomizer} sees the
 * user tasks of the deployed models, the built-in task listener it attaches runs when a
 * user task is created, and the engine's history events reach the handler it contributed.
 * <p>
 * Two of the assertions are the ones worth pinning. A parse listener contributed "after"
 * sees the user task with VanillaBP's own built-in CREATE listener already on it - which
 * is the ordering the Business Cockpit depends on, and which is a property of Camunda's
 * pre/post parse-listener lists rather than of anything this adapter does. And history
 * keeps working: the handler is installed as a COMPOSITE next to the engine's own, so the
 * historic process instance is written as before.
 */
@SpringBootTest(classes = {
    TestApplication.class, Camunda7EngineCustomizerIT.CustomizerConfiguration.class
}, properties = {
    // own database: test contexts are cached and live in parallel - another
    // context's engine on the same H2 database would execute this test's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-engine-customizer-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, Spring would keep every context until the JVM exits, and an engine outliving its
// test keeps its job executor running against a database the next classes work on
@DirtiesContext
public class Camunda7EngineCustomizerIT {

  /**
   * What an extension contributes to the engine of this application.
   */
  @TestConfiguration
  public static class CustomizerConfiguration {

    @Bean
    public RecordingEngineCustomizer recordingEngineCustomizer() {

      return new RecordingEngineCustomizer();

    }

  }

  private static final String MODULE_ID = "c7-it";

  private static final String ADAPTER_ID = "c7";

  @Autowired
  private RecordingEngineCustomizer customizer;

  @Autowired
  private RuntimeService runtimeService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private TaskTestRepository repository;

  private Long startUserTaskProcess() {

    return transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(true);
      final var saved = repository.save(aggregate);
      runtimeService
          .createProcessInstanceByKey("UserTaskProcess")
          .processDefinitionTenantId(MODULE_ID)
          .businessKey(String.valueOf(saved.getId()))
          .execute();
      return saved.getId();
    });

  }

  private static void awaitUntil(
      final java.util.function.BooleanSupplier condition,
      final String what) throws Exception {

    final var deadline = System.currentTimeMillis() + 20000;
    while (!condition.getAsBoolean()) {
      assertTrue(System.currentTimeMillis() < deadline, "timed out waiting for %s".formatted(what));
      Thread.sleep(100);
    }

  }

  @Test
  @DisplayName("The customizer is asked for the adapter id whose engine is built")
  public void theCustomizerIsAskedPerAdapterId() {

    assertEquals(Set.of(ADAPTER_ID), customizer.getAskedAdapterIds());

  }

  @Test
  @DisplayName("A parse listener contributed 'after' sees the user tasks, wired by VanillaBP already")
  public void theParseListenerSeesTheUserTasksAfterVanillaBp() {

    assertFalse(customizer.getParsedUserTasks().isEmpty(), "the parse listener saw no user task");
    assertTrue(
        customizer.getParsedUserTasks().contains("%s/UT_Task".formatted(ADAPTER_ID)),
        "the user task of UserTaskProcess: %s".formatted(customizer.getParsedUserTasks()));
    // the ordering the Business Cockpit depends on: VanillaBP wired the task first
    assertEquals(
        customizer.getParsedUserTasks(),
        customizer.getUserTasksAlreadyWiredByVanillaBp(),
        "every user task had VanillaBP's built-in CREATE listener before this one was added");

  }

  @Test
  @DisplayName("The built-in task listener of the extension runs, and the history events arrive")
  public void theListenersAndTheHistoryHandlerRun() throws Exception {

    final var aggregateId = startUserTaskProcess();

    awaitUntil(
        () -> customizer.getTaskEvents().contains("%s/UT_Task/create".formatted(ADAPTER_ID)),
        "the extension's built-in CREATE listener to run");

    awaitUntil(
        () -> customizer
            .getProcessInstanceHistory()
            .contains("%s/start/UserTaskProcess".formatted(ADAPTER_ID)),
        "the history event of the started workflow to arrive");

    // history is still WRITTEN, which is what the composite handler is for
    awaitUntil(
        () -> !runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(String.valueOf(aggregateId))
            .tenantIdIn(MODULE_ID)
            .list()
            .isEmpty(),
        "the workflow to be running");

  }

}
