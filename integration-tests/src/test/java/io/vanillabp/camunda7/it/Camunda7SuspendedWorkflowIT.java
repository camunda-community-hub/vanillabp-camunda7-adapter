package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.extension.spi.runtime.SuspendedWorkflowFound;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.WorkflowSuspendedException;

/**
 * Answers to the tasks of a suspended process instance, on a real embedded engine.
 * <p>
 * The engine refuses every answer to a task of a suspended instance. The adapter reads the
 * suspension state from the query it runs anyway. Phase one throws the
 * {@link WorkflowSuspendedException} of the SPI, in the transaction of the caller, so nothing of
 * that transaction is saved. Phase two which meets a suspension keeps the outbox entry waiting,
 * without using its attempts, and the answer goes through once the instance is activated again.
 * Before, the engine's own exception was a {@code BadUserRequestException}, which blocked the
 * entry after one attempt.
 */
@SpringBootTest(classes = {
    TestApplication.class, Camunda7SuspendedWorkflowIT.Listening.class
}, properties = {
    // a database of its own. Otherwise the phase-two outbox of a test class which Spring
    // keeps cached would dispatch the entries of the next one.
    "spring.datasource.url=jdbc:h2:mem:c7-suspended-workflow-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7SuspendedWorkflowIT {

  private static final String MODULE_ID = "c7-it";

  private static final String OUTBOX_TABLE = "VANILLABP_PHASE_TWO_OUTBOX";

  @Autowired
  private RuntimeService runtimeService;

  @Autowired
  private ProcessEngine processEngine;

  @Autowired
  private TaskTestRepository repository;

  @Autowired
  private TaskTestWorkflowService workflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private RecordingExtension extension;

  /**
   * An extension which records what it hears about a suspended workflow. It is declared for
   * every BPMS, so it matches this adapter.
   */
  @TestConfiguration
  static class Listening {

    @Bean
    RecordingExtension recordingExtension() {

      return new RecordingExtension();

    }

  }

  static class RecordingExtension implements ExtensionWiringService<Object, Object> {

    final List<SuspendedWorkflowFound> heard = new CopyOnWriteArrayList<>();

    @Override
    public Class<Object> getModelType() {

      return Object.class;

    }

    @Override
    public Class<Object> getProcessContextType() {

      return Object.class;

    }

    @Override
    public void wireBpmn(
        final String workflowModuleId,
        final String filename,
        final String bpmnProcessId,
        final Object model,
        final Object context) {
      // nothing to wire
    }

    @Override
    public void startWorkflowProcessing(
        final String workflowModuleId,
        final Object bpmsProcessingContext) {
      // nothing to start
    }

    @Override
    public void workflowFoundSuspended(
        final SuspendedWorkflowFound found,
        final Object bpmsProcessingContext) {

      heard.add(found);

    }

    List<SuspendedWorkflowFound> about(
        final String taskId) {

      return heard
          .stream()
          .filter(found -> taskId.equals(found.taskId()))
          .toList();

    }

  }

  @Test
  @DisplayName("Completing a task of a suspended instance rolls the transaction back, and works after the activation")
  public void aTaskOfASuspendedWorkflow() throws Exception {

    final var aggregateId = startAndWaitForTheTask("AsyncProcess");
    final var taskId = repository.findById(aggregateId).orElseThrow().getTaskId();
    runtimeService.suspendProcessInstanceById(instanceIdOf(aggregateId));

    final var thrown = assertThrows(
        WorkflowSuspendedException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          aggregate.appendResult("answered while suspended");
          workflowService.completeAsyncTask(aggregate, taskId);
        }));
    assertTrue(
        thrown.getMessage().contains(taskId) && thrown.getMessage().contains("'c7'"),
        "the message names the task and the adapter: "
            + thrown.getMessage());
    assertEquals(
        "async-open",
        repository.findById(aggregateId).orElseThrow().getResults(),
        "the transaction of the answer rolled back");
    final var heard = extension.about(taskId);
    assertEquals(1, heard.size(), "the extension heard the refused call once: %s".formatted(heard));
    assertEquals("c7", heard.getFirst().adapterId());
    assertEquals(MODULE_ID, heard.getFirst().workflowModuleId());
    assertEquals(String.valueOf(aggregateId), heard.getFirst().workflowAggregateId());
    assertEquals(instanceIdOf(aggregateId), heard.getFirst().workflowId());
    assertEquals(SuspendedWorkflowFound.Phase.CALL, heard.getFirst().phase());

    runtimeService.activateProcessInstanceById(instanceIdOf(aggregateId));
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.completeAsyncTask(aggregate, taskId);
    });
    awaitUntil(() -> processEnded(aggregateId), 30_000, "the workflow to end after the activation");

  }

  @Test
  @DisplayName("Completing a user task of a suspended instance rolls the transaction back, and works after the activation")
  public void aUserTaskOfASuspendedWorkflow() throws Exception {

    final var aggregateId = startAndWaitForTheTask("UserTaskProcess");
    final var taskId = repository.findById(aggregateId).orElseThrow().getTaskId();
    runtimeService.suspendProcessInstanceById(instanceIdOf(aggregateId));

    assertThrows(
        WorkflowSuspendedException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          workflowService.completeUserTask(aggregate, taskId);
        }));
    assertEquals(
        List.of(SuspendedWorkflowFound.Phase.CALL),
        extension.about(taskId).stream().map(SuspendedWorkflowFound::phase).toList(),
        "the extension heard the refused call on the user task");
    assertEquals(instanceIdOf(aggregateId), extension.about(taskId).getFirst().workflowId());

    runtimeService.activateProcessInstanceById(instanceIdOf(aggregateId));
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.completeUserTask(aggregate, taskId);
    });
    awaitUntil(() -> processEnded(aggregateId), 30_000, "the workflow to end after the activation");

  }

  @Test
  @DisplayName("Phase two which meets a suspended instance waits without using attempts, and goes through after the activation")
  public void phaseTwoWaitsForTheActivation() throws Exception {

    final var aggregateId = startAndWaitForTheTask("AsyncProcess");
    final var taskId = repository.findById(aggregateId).orElseThrow().getTaskId();
    final var instanceId = instanceIdOf(aggregateId);

    // the check of phase one finds the task active. The suspension is written in the same
    // transaction, so it commits together with the answer. Phase two is the first to meet it
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.completeAsyncTask(aggregate, taskId);
      runtimeService.suspendProcessInstanceById(instanceId);
    });

    awaitUntil(
        () -> completionsOf(aggregateId)
            .stream()
            .anyMatch(row -> String.valueOf(row.get("LAST_FAILURE")).contains("is suspended")),
        30_000,
        "phase two to meet the suspension");
    final var waiting = completionsOf(aggregateId).getFirst();
    assertEquals("OPEN", waiting.get("STATUS"), "the entry waits and is not blocked");
    assertEquals(0, ((Number) waiting.get("ATTEMPTS")).intValue(), "and the wait used none of its attempts");
    assertEquals(List.of("AP_Task"), runtimeService.getActiveActivityIds(instanceId), "the task is still open");
    final var heard = extension.about(taskId);
    assertTrue(
        !heard.isEmpty() && heard.stream().allMatch(found -> found.phase() == SuspendedWorkflowFound.Phase.WAITING),
        "the extension heard the waiting entry, once per attempt: %s".formatted(heard));
    assertEquals(instanceId, heard.getFirst().workflowId());
    assertEquals(String.valueOf(aggregateId), heard.getFirst().workflowAggregateId());

    runtimeService.activateProcessInstanceById(instanceId);
    // the entry asks again after the window it named, thirty seconds
    awaitUntil(() -> processEnded(aggregateId), 90_000, "the waiting completion to go through after the activation");

  }

  private Long startAndWaitForTheTask(
      final String bpmnProcessId) throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(true);
      final var saved = repository.save(aggregate);
      runtimeService
          .createProcessInstanceByKey(bpmnProcessId)
          .processDefinitionTenantId(MODULE_ID)
          .businessKey(String.valueOf(saved.getId()))
          .execute();
      return saved.getId();
    });
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        30_000,
        "the handler to report the task id");
    return aggregateId;

  }

  private String instanceIdOf(
      final Long aggregateId) {

    return runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .singleResult()
        .getId();

  }

  private List<Map<String, Object>> completionsOf(
      final Long aggregateId) {

    return jdbcTemplate
        .queryForList(
            "SELECT STATUS, ATTEMPTS, LAST_FAILURE FROM %s WHERE AGGREGATE_ID = ? AND OPERATION = 'COMPLETE_TASK'"
                .formatted(OUTBOX_TABLE),
            String.valueOf(aggregateId));

  }

  private boolean processEnded(
      final Long aggregateId) {

    return processEngine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .finished()
        .count() > 0;

  }

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final long timeoutMillis,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeoutMillis;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(100);
    }

  }

}
