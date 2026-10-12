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
 * The engine hooks which an extension needs on Camunda 7, end to end against a real
 * embedded engine. A parse listener contributed by a {@code Camunda7EngineCustomizer} sees
 * the user tasks of the deployed models. The built-in task listener it attaches runs when a
 * user task is created. And the history events of the engine reach the handler it
 * contributed.
 * <p>
 * Two of the assertions are the ones worth pinning. A parse listener contributed "after"
 * sees the user task with the own built-in CREATE listener of VanillaBP already on it. The
 * Business Cockpit depends on this order. It comes from the pre and post parse-listener
 * lists of Camunda, not from anything this adapter does. And history keeps working: the
 * handler is installed as a COMPOSITE next to the own one of the engine, so the historic
 * process instance is written as before.
 */
@SpringBootTest(classes = {
    TestApplication.class, Camunda7EngineCustomizerIT.CustomizerConfiguration.class
}, properties = {
    // own database. Test contexts are cached and live in parallel. The engine of another
    // context on the same H2 database would execute the jobs of this test.
    "spring.datasource.url=jdbc:h2:mem:c7-engine-customizer-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done. This IT has a database of its own, and so a context of its
// own. Spring would keep every context until the JVM exits. An engine which outlives its test
// keeps its job executor running against a database which the next classes work on.
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
    // the order which the Business Cockpit depends on: VanillaBP wired the task first
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
