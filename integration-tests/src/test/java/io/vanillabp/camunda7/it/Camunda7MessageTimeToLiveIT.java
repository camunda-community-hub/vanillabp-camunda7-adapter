package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A message correlated before the workflow waits for it, on a real embedded engine.
 * <p>
 * The adapter continues after every user task in a job of its own. So completing a user task
 * and correlating the message the workflow waits for next are two operations which the
 * engine does not finish in the order they were asked for. The completion only writes the
 * job, and the job executor reaches the catch event a moment later. Without a message
 * time-to-live, phase one refuses such a correlation, as version 1 did. With one, the
 * correlation waits in the outbox until the workflow is there, or until its time-to-live is
 * over.
 * <p>
 * The time-to-live is configured for the workflow module, and per message for the two
 * messages which test the end of the wait and the switch back to version 1. The correlations
 * go through the process service of {@code TaskProcess}. The messages are declared in
 * {@code TtlProcess}, so the time-to-live of a message is read for the process whose model
 * waits for it.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // a database of its own. Otherwise the phase-two outbox of a test class which Spring
    // keeps cached would dispatch the entries of the next one
    "spring.datasource.url=jdbc:h2:mem:c7-message-time-to-live-it;DB_CLOSE_DELAY=-1", "vanillabp.workflow-modules.c7-it.adapters.c7.message-time-to-live=PT1M", "vanillabp.workflow-modules.c7-it.workflows.TtlProcess.messages.TtlReminder.adapters.c7.message-time-to-live=PT1S", "vanillabp.workflow-modules.c7-it.workflows.TtlProcess.messages.TtlUnbuffered.adapters.c7.message-time-to-live=PT0S"
})
@DirtiesContext
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7MessageTimeToLiveIT {

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

  private Long startTtlProcess() {

    return transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(true);
      final var saved = repository.save(aggregate);
      runtimeService
          .createProcessInstanceByKey("TtlProcess")
          .processDefinitionTenantId(MODULE_ID)
          .businessKey(String.valueOf(saved.getId()))
          .execute();
      return saved.getId();
    });

  }

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(50);
    }

  }

  /**
   * The open user task of the workflow with the given element id, or <code>null</code>.
   */
  private String openUserTask(
      final Long aggregateId,
      final String elementId) {

    final var task = processEngine
        .getTaskService()
        .createTaskQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .taskDefinitionKey(elementId)
        .singleResult();
    return task == null
        ? null
        : task.getId();

  }

  private String awaitOpenUserTask(
      final Long aggregateId,
      final String elementId) throws InterruptedException {

    awaitUntil(() -> openUserTask(aggregateId, elementId) != null, "user task '%s'".formatted(elementId));
    return openUserTask(aggregateId, elementId);

  }

  private void completeUserTask(
      final Long aggregateId,
      final String taskId) {

    transactionTemplate.executeWithoutResult(status -> workflowService
        .completeUserTask(repository.findById(aggregateId).orElseThrow(), taskId));

  }

  private void correlate(
      final Long aggregateId,
      final String messageName) {

    transactionTemplate.executeWithoutResult(status -> workflowService
        .correlate(repository.findById(aggregateId).orElseThrow(), messageName));

  }

  /**
   * The status and the reason of the last failure of the correlations of an aggregate, read
   * from the outbox table.
   */
  private java.util.List<java.util.Map<String, Object>> correlationsOf(
      final Long aggregateId) {

    return jdbcTemplate
        .queryForList(
            "SELECT STATUS, LAST_FAILURE, ARGS FROM %s WHERE AGGREGATE_ID = ? AND OPERATION = 'CORRELATE_MESSAGE'"
                .formatted(OUTBOX_TABLE),
            String.valueOf(aggregateId));

  }

  private boolean hasCorrelationWithStatus(
      final Long aggregateId,
      final String messageName,
      final String status) {

    return correlationsOf(aggregateId)
        .stream()
        .anyMatch(row -> status.equals(row.get("STATUS")) && String
            .valueOf(row.get("ARGS"))
            .contains(messageName));

  }

  @Test
  @DisplayName("A message correlated right after the user task before its catch event was completed reaches the workflow")
  public void aCorrelationRightAfterACompletionIsNotLost() throws Exception {

    final var aggregateId = startTtlProcess();
    final var review = awaitOpenUserTask(aggregateId, "TTL_Review");

    // no pause: this is what the blueprint module-bpms-migration does, and what was lost
    // before. The engine reaches the catch event only after the job which continues after
    // the user task
    completeUserTask(aggregateId, review);
    correlate(aggregateId, "TtlContractSigned");

    awaitOpenUserTask(aggregateId, "TTL_PayOut");
    awaitUntil(
        () -> hasCorrelationWithStatus(aggregateId, "TtlContractSigned", "DONE"),
        "the correlation to be ticked off");

  }

  @Test
  @DisplayName("A correlation which waits holds up no later operation of the same workflow")
  public void aWaitingCorrelationHoldsUpNoLaterOperation() throws Exception {

    final var aggregateId = startTtlProcess();
    final var review = awaitOpenUserTask(aggregateId, "TTL_Review");

    // the message comes first, while the workflow still waits at the user task. Phase one
    // checks the model only, so the call goes through
    correlate(aggregateId, "TtlContractSigned");
    awaitUntil(
        () -> hasCorrelationWithStatus(aggregateId, "TtlContractSigned", "OPEN"),
        "the correlation to wait in the outbox");

    // the completion is planned after the correlation, and it is not held up by it
    completeUserTask(aggregateId, review);
    awaitUntil(() -> openUserTask(aggregateId, "TTL_Review") == null, "the user task to be completed");

    // and once the workflow is at the catch event, the waiting correlation reaches it
    awaitOpenUserTask(aggregateId, "TTL_PayOut");
    awaitUntil(
        () -> hasCorrelationWithStatus(aggregateId, "TtlContractSigned", "DONE"),
        "the correlation to be ticked off");

  }

  @Test
  @DisplayName("A correlation whose time-to-live ran out is blocked, and the reason names message, aggregate, TTL and wait")
  public void aCorrelationWhoseTimeToLiveRanOutIsBlocked() throws Exception {

    final var aggregateId = startTtlProcess();
    awaitOpenUserTask(aggregateId, "TTL_Review");

    // the workflow waits for this message much later. One second is not enough
    correlate(aggregateId, "TtlReminder");

    awaitUntil(
        () -> hasCorrelationWithStatus(aggregateId, "TtlReminder", "BLOCKED"),
        "the correlation to be blocked once its time-to-live ran out");
    final var reason = String.valueOf(correlationsOf(aggregateId).getFirst().get("LAST_FAILURE"));
    assertTrue(reason.contains("message 'TtlReminder'"), reason);
    assertTrue(reason.contains("workflow aggregate '%s'".formatted(aggregateId)), reason);
    assertTrue(reason.contains("within its message time-to-live of PT1S"), reason);
    assertTrue(reason.contains("It waited PT"), reason);
    assertTrue(
        openUserTask(aggregateId, "TTL_Review") != null,
        "the workflow is left where it was, the blocked entry did not reach it");

  }

  @Test
  @DisplayName("A time-to-live of zero refuses a correlation nothing waits for where the application made the call")
  public void aTimeToLiveOfZeroRefusesAtTheCall() throws Exception {

    final var aggregateId = startTtlProcess();
    awaitOpenUserTask(aggregateId, "TTL_Review");

    final var refusal = assertThrows(
        IllegalStateException.class,
        () -> correlate(aggregateId, "TtlUnbuffered"));
    assertTrue(
        refusal
            .getMessage()
            .contains(
                "No execution of workflow aggregate '%s' waits for message 'TtlUnbuffered'".formatted(aggregateId)),
        refusal.getMessage());
    assertTrue(correlationsOf(aggregateId).isEmpty(), "nothing was planned");

  }

  @Test
  @DisplayName("With a time-to-live, a message no model of the workflow module waits for is refused at the call")
  public void aMessageNoModelWaitsForIsRefusedAtTheCall() throws Exception {

    final var aggregateId = startTtlProcess();
    awaitOpenUserTask(aggregateId, "TTL_Review");

    final var refusal = assertThrows(
        IllegalStateException.class,
        () -> correlate(aggregateId, "TtlContractSignd"));
    assertTrue(
        refusal
            .getMessage()
            .contains("No deployed model of workflow module 'c7-it' waits for message 'TtlContractSignd'"),
        refusal.getMessage());
    assertTrue(correlationsOf(aggregateId).isEmpty(), "nothing was planned");

  }

}
