package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader.Delivery;

/**
 * What an engine datasource of its own changes about the INBOUND direction. It is asserted
 * against both modes side by side: {@code c7} shares the datasource of the application, and
 * {@code c7b} runs on a named one.
 * <ul>
 * <li>The adapter says per mode whether its deliveries may repeat.</li>
 * <li>On the own datasource, the transaction of the handler commits before the job of the
 * engine does. So a job which fails afterwards is handed out again. The repeated delivery is
 * answered from the record, instead of running the {@code @WorkflowTask} method again.</li>
 * <li>On the shared datasource, the same failure rolls the work of the handler back with
 * the job, including the record of that delivery. The handler runs again, and that is what
 * a redelivery means there. The run which commits leaves a record all the same, but that
 * record answers no repetition. This mode names no delivery, so nothing could recognize a
 * repetition. The record still says who holds the task, and which kind of id its id
 * is.</li>
 * </ul>
 * An end listener of the task ({@link FailTheJobOnce}) fails the job. That is the only
 * moment where the handler is done and the transaction of the engine is not.
 * <p>
 * The record has a second consequence, and it is asserted here for the same reason. A
 * completion of a task which is gone is routed from that record, instead of probing the
 * BPMS. So the own phase-one check of the adapter is what finds out. What it raises has to
 * be the type which the SPI documents.
 * <p>
 * What a record CARRIES is asserted here as well, out of the table and not out of the
 * invocation context: the element id of the BPMN element, and the own id of the process
 * instance in the engine.
 * <p>
 * The kind of task which a record names is read twice: out of the column, and out of the
 * message a caller gets. Somebody who asks for a user task under the id of a task is told
 * what the id really is. That message exists only where the record carries the kind. So it
 * is the proof that this adapter reported it.
 */
@SpringBootTest(classes = {
    TestApplication.class, Camunda7RepeatedDeliveryIT.NamedDataSourceConfiguration.class
}, properties = {
    // a database of its own. Test contexts are cached and live in parallel, and an engine
    // of another context would poll the jobs of this one.
    "spring.datasource.url=jdbc:h2:mem:c7-repeated-delivery-it;DB_CLOSE_DELAY=-1", "vanillabp.prioritized-adapters=c7,c7b", "vanillabp.adapters.c7b.type=camunda7", "vanillabp.adapters.c7b.name-clash-avoidance=by-adapter", "vanillabp.adapters.c7b.data-source-name=c7bDataSource", "vanillabp.workflow-modules.c7-it.adapters.c7b.resources-location=classpath*:c7-it/processes"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done. This IT has a database of its own, and so a context of its
// own. Spring would keep every context until the JVM exits. An engine which outlives its test
// keeps its job executor running against a database which the next classes work on.
@DirtiesContext
public class Camunda7RepeatedDeliveryIT {

  /**
   * The datasource bean which the application provides, and which the {@code c7b} engine
   * runs on. {@code defaultCandidate = false} keeps it out of injection by type. It also
   * lets the default-datasource auto-configuration of Spring Boot stay active. This is the
   * standard pattern for additional datasources of an application.
   */
  @org.springframework.boot.test.context.TestConfiguration
  public static class NamedDataSourceConfiguration {

    @org.springframework.context.annotation.Bean(defaultCandidate = false)
    public javax.sql.DataSource c7bDataSource() {

      return new org.springframework.jdbc.datasource.SimpleDriverDataSource(
          new org.h2.Driver(), "jdbc:h2:mem:c7b-repeated-delivery;DB_CLOSE_DELAY=-1");

    }

  }

  private static final String MODULE_ID = "c7-it";

  private static final String BPMN_PROCESS_ID = "RepeatedDeliveryProcess";

  @Autowired
  @Qualifier("Camunda7_Engine_c7")
  private Camunda7EngineHolder sharedDataSourceEngine;

  @Autowired
  @Qualifier("Camunda7_Engine_c7b")
  private Camunda7EngineHolder separateDataSourceEngine;

  @Autowired
  @Qualifier("Camunda7_ProcessService_c7")
  private Camunda7ProcessService<?> sharedDataSourceProcessService;

  @Autowired
  @Qualifier("Camunda7_ProcessService_c7b")
  private Camunda7ProcessService<?> separateDataSourceProcessService;

  @Autowired
  private RepeatedDeliveryTestRepository repository;

  @Autowired
  private RepeatedDeliveryTestWorkflowService workflowService;

  @Autowired
  private RepeatedDeliveryProbe probe;

  /**
   * The task-processing fixture, borrowed for the one case which needs a task that stays
   * open. The own process of this class ends by itself. A stale completion needs a task
   * which somebody can complete behind the back of VanillaBP.
   */
  @Autowired
  private TaskTestRepository taskRepository;

  @Autowired
  private TaskTestWorkflowService taskWorkflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private javax.sql.DataSource applicationDataSource;

  /**
   * What this class asks about the delivery log. The reader belongs to the platform, so
   * this class names neither the table nor its columns.
   */
  private TaskDeliveryLogReader deliveryLog;

  @BeforeEach
  public void takeTheDeliveryLog() {

    deliveryLog = TaskDeliveryLogReader.of(applicationDataSource);

  }

  @AfterEach
  public void forgetWhatThisTestSteered() {

    probe.reset();

  }

  @Test
  @DisplayName("Only the adapter id on its own datasource says that a delivery may repeat")
  public void deliveryRepetitionIsAnsweredFromTheDatasourceMode() {

    assertFalse(
        sharedDataSourceProcessService.deliversTasksAtLeastOnce(),
        "sharing the application's datasource means delivering in its transaction");
    assertTrue(
        separateDataSourceProcessService.deliversTasksAtLeastOnce(),
        "an own datasource means the aggregate commits before the engine does");

  }

  @Test
  @DisplayName("On an own datasource the repeated delivery is answered from the record")
  public void repeatedDeliveryOnAnOwnDataSourceIsAnsweredFromTheRecord() {

    final var aggregateId = transactionTemplate
        .execute(status -> repository.save(new RepeatedDeliveryTestAggregate()).getId());

    probe.failTheNextJob();
    separateDataSourceEngine
        .getRuntimeService()
        .createProcessInstanceByKey(BPMN_PROCESS_ID)
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregateId))
        .execute();

    AwaitPhaseTwo
        .until(
            () -> workflowEnded(separateDataSourceEngine, aggregateId),
            "the workflow to end after the repeated delivery");

    assertEquals(
        1,
        probe.handlerInvocations(),
        "the repeated delivery must be answered from the record, not by the method");
    assertEquals(
        1,
        committedHandlerRuns(aggregateId),
        "the handler's work committed in its own transaction and survived the failed job");
    assertEquals(1, recordsOf("c7b").size(), "the delivery of the own-datasource engine is remembered");

  }

  @Test
  @DisplayName("On the shared datasource the handler runs again, and one record is left behind")
  public void repeatedDeliveryOnTheSharedDataSourceRunsTheHandlerAgain() {

    final var aggregateId = transactionTemplate.execute(status -> {
      probe.failTheNextJob();
      return workflowService
          .startOnTheFirstPrioritizedAdapter(new RepeatedDeliveryTestAggregate())
          .getId();
    });

    AwaitPhaseTwo
        .until(
            () -> workflowEnded(sharedDataSourceEngine, aggregateId),
            "the workflow to end after the retried job");

    assertEquals(
        2,
        probe.handlerInvocations(),
        "the failed job took the handler's work with it, so the engine's retry runs the method again");
    assertEquals(
        1,
        committedHandlerRuns(aggregateId),
        "only the run which committed is in the aggregate");
    // this mode names no delivery, so no repetition can be recognized. A record is written
    // all the same, and exactly one. The run which was rolled back took its record with it,
    // and the run which committed left one.
    final var records = recordsOf("c7");
    assertEquals(
        1,
        records.size(),
        "the run which committed wrote a record down, the rolled-back one did not");
    assertEquals(
        TaskKind.TASK.name(),
        records.get(0).taskKind(),
        "and that record says which kind of id its id is, although it answers no repetition");
    assertEquals(
        "RD_Task",
        records.get(0).bpmnElementId(),
        "and which element of the model handed the task out");

  }

  @Test
  @DisplayName("The record of a delivery names the BPMN element and the engine's process instance")
  public void theRecordNamesTheElementAndTheWorkflow() {

    final var aggregateId = transactionTemplate
        .execute(status -> taskRepository.save(new TaskTestAggregate()).getId());

    // AsyncProcess parks at a @TaskId task, so the record of its delivery is written and
    // stays. Its element id and its task definition differ, so the two fields can be told
    // apart here.
    final var processInstance = separateDataSourceEngine
        .getRuntimeService()
        .createProcessInstanceByKey("AsyncProcess")
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregateId))
        .execute();

    // the handler runs before the record is written, and the record becomes visible with
    // the commit. So the test waits for the row, instead of reading it in the next line.
    final var record = AwaitPhaseTwo
        .untilAvailable(
            () -> recordOf(String.valueOf(aggregateId)),
            "the delivery of the asynchronous task to be recorded");

    assertEquals("AP_Task", record.bpmnElementId(), "the element id a modeller wrote");
    assertEquals(
        "asyncTask",
        record.taskDefinition(),
        "the task definition is the other value, and it is a different one");
    assertEquals(
        processInstance.getId(),
        record.workflowId(),
        "the engine's own id of the running instance");

  }

  @Test
  @DisplayName("The record says the id is a task, so a completion asking for a user task is told")
  public void theRecordSaysWhichKindOfTaskTheIdIs() {

    final var aggregateId = transactionTemplate
        .execute(status -> taskRepository.save(new TaskTestAggregate()).getId());

    separateDataSourceEngine
        .getRuntimeService()
        .createProcessInstanceByKey("AsyncProcess")
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregateId))
        .execute();
    AwaitPhaseTwo
        .untilAvailable(
            () -> recordOf(String.valueOf(aggregateId)),
            "the delivery of the asynchronous task to be recorded");
    final var taskId = parkedTaskOf(aggregateId);

    // the mistake this is about. The id is an execution of the engine, and completeUserTask
    // asks for a row of ACT_RU_TASK. No engine holds a user task under that id, so every
    // adapter answers that it knows no such user task.
    final var refused = org.junit.jupiter.api.Assertions
        .assertThrows(
            io.vanillabp.spi.process.TaskNotFoundException.class,
            () -> transactionTemplate
                .executeWithoutResult(status -> taskWorkflowService
                    .completeUserTask(taskRepository.findById(aggregateId).orElseThrow(), taskId)));

    assertTrue(
        refused.getMessage().contains("VanillaBP wrote down that '%s' is the id of a task".formatted(taskId)),
        "the record of this adapter's delivery carries the kind, so the message names it: "
            + refused.getMessage());
    assertTrue(
        refused.getMessage().contains("The method which asks about a task is completeTask"),
        "and it names the method which asks for that kind of id: "
            + refused.getMessage());

  }

  /**
   * The one record of a workflow aggregate, or <code>null</code> while there is none. It is
   * read by aggregate, because the process of this test runs only once per aggregate here.
   *
   * @param aggregateId The workflow aggregate's ID in the form the record carries it
   * @return The record, or <code>null</code>
   */
  private Delivery recordOf(
      final String aggregateId) {

    return deliveryLog
        .deliveries()
        .stream()
        .filter(record -> aggregateId.equals(record.aggregateId()))
        .filter(record -> "AsyncProcess".equals(record.bpmnProcessId()))
        .findFirst()
        .orElse(null);

  }

  @Test
  @DisplayName("A task the engine no longer holds raises the documented TaskNotFoundException")
  public void aStaleCompletionRaisesTheGuidingException() {

    final var aggregateId = transactionTemplate
        .execute(status -> taskRepository.save(new TaskTestAggregate()).getId());

    // started directly on the engine with the own datasource, because the record of ITS
    // delivery makes this case. The completion of the caller is then routed from that
    // record, instead of probing the BPMS. So the own check of the adapter is what meets
    // the task which is gone.
    separateDataSourceEngine
        .getRuntimeService()
        .createProcessInstanceByKey("AsyncProcess")
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregateId))
        .execute();
    AwaitPhaseTwo.until(() -> parkedTaskOf(aggregateId) != null, "the asynchronous task to be delivered");
    final var taskId = parkedTaskOf(aggregateId);

    // somebody completes it outside VanillaBP, which is what a stale completion means. The
    // signal only starts the continuation. So the test waits until the task is gone,
    // instead of assuming it. Otherwise this test would measure the speed of the job
    // executor.
    separateDataSourceEngine
        .getRuntimeService()
        .signal(taskId);
    AwaitPhaseTwo.until(() -> !theEngineStillHolds(taskId), "the signalled task to be gone from the engine");

    org.junit.jupiter.api.Assertions
        .assertThrows(
            io.vanillabp.spi.process.TaskNotFoundException.class,
            () -> transactionTemplate
                .executeWithoutResult(status -> taskWorkflowService
                    .completeAsyncTask(taskRepository.findById(aggregateId).orElseThrow(), taskId)),
            "the type is the one the SPI documents for a task no BPMS knows any more");

  }

  /**
   * @param taskId The parked execution the handler reported
   * @return Whether the own-datasource engine still holds it
   */
  private boolean theEngineStillHolds(
      final String taskId) {

    return separateDataSourceEngine
        .getRuntimeService()
        .createExecutionQuery()
        .executionId(taskId)
        .count() > 0;

  }

  /**
   * @param aggregateId The workflow aggregate's ID
   * @return The task the asynchronous handler parked and reported, or <code>null</code>
   *         while it has not run yet
   */
  private String parkedTaskOf(
      final Long aggregateId) {

    return transactionTemplate
        .execute(status -> taskRepository
            .findById(aggregateId)
            .orElseThrow()
            .getTaskId());

  }

  /**
   * @param aggregateId The workflow aggregate's ID
   * @return How many handler runs the aggregate carries, i.e. how many of them committed
   */
  private int committedHandlerRuns(
      final Long aggregateId) {

    return transactionTemplate
        .execute(status -> repository.findById(aggregateId).orElseThrow().getHandlerRuns());

  }

  /**
   * Waits for the workflow to END, not for the work of the handler to appear. The failing
   * job is what makes this scenario, and its retry has to have happened before anything is
   * counted.
   *
   * @param engine The engine the workflow runs in
   * @param aggregateId The workflow aggregate's ID
   * @return Whether that workflow has ended
   */
  private boolean workflowEnded(
      final Camunda7EngineHolder engine,
      final Long aggregateId) {

    return engine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .tenantIdIn(MODULE_ID)
        .finished()
        .count() == 1;

  }

  /**
   * Counted per adapter id, because both modes run the same BPMN process here, and the
   * records of the two share one table.
   *
   * @param adapterId The adapter id which delivered
   * @return What VanillaBP wrote down about the deliveries of this test's BPMN process
   */
  private List<Delivery> recordsOf(
      final String adapterId) {

    return deliveryLog
        .deliveries()
        .stream()
        .filter(record -> BPMN_PROCESS_ID.equals(record.bpmnProcessId()))
        .filter(record -> adapterId.equals(record.adapterId()))
        .toList();

  }

}
