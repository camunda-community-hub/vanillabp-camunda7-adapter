package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader;
import io.vanillabp.spi.process.TaskNotFoundException;

/**
 * What a user-task notification of this engine leaves in the delivery log, and what a
 * caller is told who completes that id with the method for the other kind of task.
 * <p>
 * This adapter names no delivery of a user task: one transaction creates every user task
 * the token reaches, and the id of such a task comes into being while it is created, so
 * nothing could tell a repetition from new work. There is therefore nothing to
 * deduplicate here, in either datasource mode. A record is written all the same, because
 * it answers two more questions: which BPMS holds the task, and which kind of id its id
 * is. Those two turn a failed completion from a list of guesses into one sentence
 * naming the method the caller wanted.
 * <p>
 * The record is read twice over, once out of the table and once out of that sentence. The
 * table says the adapter and the kind, which is what the delivery wrote down; the message
 * says that the platform read it back.
 * <p>
 * A record nobody deduplicates lives as long as
 * <code>vanillabp.delivery.retention</code> allows, and its clock runs from the moment
 * the handler ran: no redelivery arrives to move it on. A user task open longer than that
 * loses its record, and the caller gets the list of guesses again. This class runs with a
 * retention of one second and drives the cleanup itself, so that loss is a test rather
 * than a surprise in production.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // a database of its own: the delivery log is not emptied between test classes, and
    // the engine of a cached context would poll this one's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-user-task-delivery-record-it;DB_CLOSE_DELAY=-1",
    // short enough for the expiry to be a test, and nothing deletes a record before this
    // class asks for it: the cleanup runs at startup and then hourly
    "vanillabp.delivery.retention=PT1S"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, Spring would keep every context until the JVM exits, and an engine outliving its
// test keeps its job executor running against a database the next classes work on
@DirtiesContext
public class Camunda7UserTaskDeliveryRecordIT {

  private static final String MODULE_ID = "c7-it";

  private static final String BPMN_PROCESS_ID = "UserTaskProcess";

  /**
   * The adapter id of the engine sharing the application's datasource, which is the one
   * this class runs on.
   */
  private static final String ADAPTER_ID = "c7";

  @Autowired
  @Qualifier("Camunda7_Engine_c7")
  private Camunda7EngineHolder engine;

  @Autowired
  private TaskTestRepository repository;

  @Autowired
  private TaskTestWorkflowService workflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private javax.sql.DataSource applicationDataSource;

  /**
   * The delivery log of the application under test, driven here rather than waited for:
   * the cleanup of the platform runs at startup and then once an hour, and this class
   * cannot wait that long.
   */
  @Autowired
  private JdbcTaskDeliveryLog deliveryLog;

  /**
   * What this class asks about the delivery log. The reader belongs to the platform, so
   * this class names neither the table nor its columns.
   */
  private TaskDeliveryLogReader recordedDeliveries;

  @BeforeEach
  public void takeTheDeliveryLog() {

    recordedDeliveries = TaskDeliveryLogReader.of(applicationDataSource);

  }

  @Test
  @DisplayName("A user-task delivery is recorded with its adapter and its kind, and the kind sharpens a wrong completion")
  public void aUserTaskDeliveryIsRecordedAndSharpensAWrongCompletion() {

    final var aggregateId = startTheUserTaskProcess();
    final var userTaskId = deliveredUserTaskOf(aggregateId);

    final var records = recordedDeliveries.deliveriesOfTask(userTaskId);
    assertEquals(
        1,
        records.size(),
        "one delivery of this user task, and one record of it, although nothing is deduplicated here");
    final var record = records.get(0);
    assertEquals(ADAPTER_ID, record.adapterId(), "the adapter which holds the task");
    assertEquals(
        TaskKind.USER_TASK.name(),
        record.taskKind(),
        "which kind of id the recorded id is - the answer this adapter could not give before");
    assertEquals(BPMN_PROCESS_ID, record.bpmnProcessId(), "the BPMN process the task belongs to");

    // the mistake the sentence was written for: the id names a row of ACT_RU_TASK, and
    // completeTask asks the engine for an execution. No engine holds one under that id
    final var refused = assertThrows(
        TaskNotFoundException.class,
        () -> transactionTemplate
            .executeWithoutResult(status -> workflowService
                .completeAsyncTask(repository.findById(aggregateId).orElseThrow(), userTaskId)));

    assertTrue(
        refused.getMessage().contains("VanillaBP wrote down that '%s' is the id of a user task".formatted(userTaskId)),
        "the record carries the kind, so the message says what the id is: "
            + refused.getMessage());
    assertTrue(
        refused.getMessage().contains("The method which asks about a user task is completeUserTask"),
        "and it names the method which asks for that kind of id: "
            + refused.getMessage());
    assertFalse(
        refused.getMessage().contains("Likely causes"),
        "and it guesses nothing, because it knows: "
            + refused.getMessage());

  }

  @Test
  @DisplayName("A user task open longer than the retention loses its record, and the caller is told what the id could be")
  public void aUserTaskOpenLongerThanTheRetentionLosesItsRecord() {

    final var aggregateId = startTheUserTaskProcess();
    final var userTaskId = deliveredUserTaskOf(aggregateId);
    assertEquals(
        1,
        recordedDeliveries.deliveriesOfTask(userTaskId).size(),
        "the record of the open user task");

    // nothing renews this record: renewal happens when a redelivery of the same key
    // arrives, and this adapter names no delivery of a user task. So the retention
    // counts from the moment the handler ran, and the user task is still open when the
    // record is gone
    AwaitPhaseTwo
        .until(
            () -> {
              deliveryLog.cleanUpExpiredRecords();
              return recordedDeliveries.deliveriesOfTask(userTaskId).isEmpty();
            },
            "the retention to take the record of the still-open user task");
    assertTrue(theEngineStillHolds(userTaskId), "the user task outlived its record, which is the point here");

    final var refused = assertThrows(
        TaskNotFoundException.class,
        () -> transactionTemplate
            .executeWithoutResult(status -> workflowService
                .completeAsyncTask(repository.findById(aggregateId).orElseThrow(), userTaskId)));

    assertTrue(
        refused.getMessage().contains("Likely causes"),
        "without a record the message lists what the id could be: "
            + refused.getMessage());
    assertTrue(
        refused.getMessage().contains("VanillaBP keeps no record of this id"),
        "and it says why it cannot be sharper, naming the retention: "
            + refused.getMessage());
    assertFalse(
        refused.getMessage().contains("VanillaBP wrote down that"),
        "the sentence of the other test is gone with the record: "
            + refused.getMessage());

  }

  /**
   * Starts the process whose user task is served by a <code>&#64;TaskId</code> method,
   * directly at the engine: what the start does is not what this class is about, and the
   * aggregate has to exist before the engine hands the task out.
   *
   * @return The ID of the workflow aggregate of the started workflow
   */
  private Long startTheUserTaskProcess() {

    return transactionTemplate.execute(status -> {
      final var aggregate = repository.save(new TaskTestAggregate());
      engine
          .getRuntimeService()
          .createProcessInstanceByKey(BPMN_PROCESS_ID)
          .processDefinitionTenantId(MODULE_ID)
          .businessKey(String.valueOf(aggregate.getId()))
          .execute();
      return aggregate.getId();
    });

  }

  /**
   * The user task the notification handler reported, waited for rather than read in the
   * next line: the record becomes visible with the commit of that handler's transaction.
   *
   * @param aggregateId The workflow aggregate's ID
   * @return The id of the user task the engine created
   */
  private String deliveredUserTaskOf(
      final Long aggregateId) {

    return AwaitPhaseTwo
        .untilAvailable(
            () -> transactionTemplate
                .execute(status -> repository.findById(aggregateId).orElseThrow().getTaskId()),
            "the created user task to be delivered to its handler");

  }

  /**
   * @param userTaskId The user task the handler reported
   * @return Whether the engine still holds it
   */
  private boolean theEngineStillHolds(
      final String userTaskId) {

    return engine
        .getTaskService()
        .createTaskQuery()
        .taskId(userTaskId)
        .count() > 0;

  }

}
