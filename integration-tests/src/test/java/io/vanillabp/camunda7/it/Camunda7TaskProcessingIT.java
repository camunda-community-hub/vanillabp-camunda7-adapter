package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader;

/**
 * End-to-end test of {@code @WorkflowTask} processing on a real embedded Camunda 7
 * engine (H2, shared datasource/transaction manager with the JPA aggregate
 * persistence):
 * <ul>
 * <li>happy path: service task executes the handler, aggregate mutation persisted,
 * task completed, process ends - incl. a gateway condition reading an aggregate
 * attribute through VanillaBP's EL resolver;</li>
 * <li>{@code TaskException}: error-boundary routing WITH committed aggregate
 * changes (the V1 contract);</li>
 * <li>technical exception: job transaction rolled back (aggregate unchanged), job
 * retried (retry counter decremented);</li>
 * <li>{@code @TaskId}: the task stays open, the job executor does not redeliver;</li>
 * <li>multi-instance: collection from an aggregate attribute, index/total/element
 * bound;</li>
 * <li>async-before/after forced onto service tasks by the parse listener (asserted
 * on the parsed process definition).</li>
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // a database of its own: the phase-two outbox of a test class Spring keeps
    // cached would otherwise dispatch the entries of the next one
    "spring.datasource.url=jdbc:h2:mem:c7-task-processing-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7TaskProcessingIT {


  private static final String MODULE_ID = "c7-it";

  /**
   * What a probe is asked about: this test's workflow module and the BPMN
   * processes its {@code @WorkflowService} serves. The probes answer for that scope and
   * for nothing else, so a test calling them directly has to name it the way the platform
   * would.
   */
  private static final io.vanillabp.integration.adapter.spi.WorkflowScope SCOPE = new io.vanillabp.integration.adapter.spi.WorkflowScope(
      MODULE_ID, java.util.List
          .of(
              "TaskProcess", "ErrorProcess", "FailProcess", "AsyncProcess", "MultiInstanceProcess",
              "AsyncCancelProcess", "CancelEventProcess", "MixedProcess", "UserTaskProcess",
              "SilentUserTaskProcess", "MessageProcess", "BusinessRuleProcess"));

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
  private org.springframework.context.ApplicationContext applicationContext;

  @Autowired
  private javax.sql.DataSource applicationDataSource;

  private Long startWorkflow() {

    return transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(true);
      return workflowService.startTaskProcess(aggregate).getId();
    });

  }

  private Long startSecondaryProcess(
      final String bpmnProcessId,
      final boolean approved,
      final List<String> items) {

    return transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(approved);
      aggregate.setItems(items);
      final var saved = repository.save(aggregate);
      runtimeService
          .createProcessInstanceByKey(bpmnProcessId)
          .processDefinitionTenantId(MODULE_ID)
          .businessKey(String.valueOf(saved.getId()))
          .execute();
      return saved.getId();
    });

  }

  /**
   * Starts the process reading the decision table, with the value the decision asks
   * about as a process variable: what the aggregate contributes to the BPMS is the sync
   * model's business and has tests of its own.
   *
   * @param approved What the decision decides on
   * @return The aggregate's id
   */
  private Long startBusinessRuleProcess(
      final boolean approved) {

    return transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(approved);
      final var saved = repository.save(aggregate);
      runtimeService
          .createProcessInstanceByKey("BusinessRuleProcess")
          .processDefinitionTenantId(MODULE_ID)
          .businessKey(String.valueOf(saved.getId()))
          .setVariable("approved", approved)
          .execute();
      return saved.getId();
    });

  }

  /**
   * How long the engine is watched after a rolled-back operation, before what did not
   * happen counts as something which never will.
   * <p>
   * Half a second, against the moment a committed operation takes: this engine shares the
   * caller's transaction, so a completion which committed is in the database when the
   * transaction returns, and an asynchronous continuation is handed to a job executor
   * which is told about it at the same commit.
   * <p>
   * A guard and not a budget anybody has to be faster than: what is asserted afterwards is
   * that the instance is where it was, and a machine which leaves this JVM without a turn
   * only makes the silence longer.
   */
  private static final long UNTIL_A_COMPLETION_WOULD_HAVE_ARRIVED = 500;

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(100);
    }

  }

  /**
   * The instance is created by the phase-two outbox AFTER the commit, so
   * the lookup waits for it instead of reading a moment too early.
   *
   * @param aggregateId The aggregate's id (the business key)
   * @return The process instance's id
   */
  private String instanceIdOf(
      final Long aggregateId) {

    return AwaitPhaseTwo
        .untilAvailable(
            () -> runtimeService
                .createProcessInstanceQuery()
                .processInstanceBusinessKey(String.valueOf(aggregateId))
                .singleResult(),
            "the workflow of aggregate '%s' to be started".formatted(aggregateId))
        .getId();

  }

  /**
   * Asked against the HISTORY: a workflow which does not run may also be one the
   * outbox has not started yet, and that is a state every test passes through.
   *
   * @param aggregateId The aggregate's id (the business key)
   * @return Whether the workflow ran and ended
   */
  private boolean processEnded(
      final Long aggregateId) {

    return processEngine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .finished()
        .count() > 0;

  }

  @Test
  @DisplayName("Happy path: handler executes, aggregate persisted, gateway reads aggregate attribute, process ends")
  public void happyPathWithGatewayCondition() throws Exception {

    final var approvedId = startWorkflow();
    awaitUntil(() -> processEnded(approvedId), "approved TaskProcess to end");
    assertEquals("happy|approved", repository.findById(approvedId).orElseThrow().getResults());

    // approved=false: the gateway's default flow skips 'afterApproval'
    final var rejectedId = startSecondaryProcess("TaskProcess", false, null);
    awaitUntil(() -> processEnded(rejectedId), "rejected TaskProcess to end");
    assertEquals("happy", repository.findById(rejectedId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("TaskException routes through the error boundary and COMMITS the aggregate changes")
  public void taskExceptionRoutesErrorBoundaryAndCommits() throws Exception {

    final var aggregateId = startSecondaryProcess("ErrorProcess", true, null);
    awaitUntil(() -> processEnded(aggregateId), "ErrorProcess to end via the error boundary");

    // both the mutation of the THROWING handler and the boundary path are visible
    assertEquals("error-raised|handled", repository.findById(aggregateId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("A technical exception rolls back the job transaction and decrements the job retries")
  public void technicalExceptionRollsBackAndRetries() throws Exception {

    final var aggregateId = startSecondaryProcess("FailProcess", true, null);

    final var instanceId = instanceIdOf(aggregateId);

    // the job executor retries the failing job - wait for a decremented retry count
    awaitUntil(
        () -> processEngine.getManagementService()
            .createJobQuery()
            .processInstanceId(instanceId)
            .list()
            .stream()
            .anyMatch(job -> job.getRetries() < 3),
        "the failing job's retries to be decremented");

    final var job = processEngine.getManagementService()
        .createJobQuery()
        .processInstanceId(instanceId)
        .singleResult();
    assertNotNull(job, "the failing job stays (task not completed)");
    assertTrue(job.getRetries() < 3, "retries decremented");

    // the handler's mutation was rolled back with the job transaction
    assertNull(repository.findById(aggregateId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("A transaction annotation in the handler's call chain fails the job with VanillaBP's message")
  public void nestedTransactionAnnotationIsReportedAtTheEngine() throws Exception {

    // the handler carries no annotation itself (that would fail the boot), it calls a
    // bean that does - and the engine shares the transaction the
    // bean's interceptor marks rollback-only
    final var aggregateId = startSecondaryProcess("RollbackOnlyProcess", true, null);

    final var instanceId = instanceIdOf(aggregateId);

    awaitUntil(
        () -> processEngine.getManagementService()
            .createJobQuery()
            .processInstanceId(instanceId)
            .list()
            .stream()
            .anyMatch(job -> (job.getExceptionMessage() != null) && job
                .getExceptionMessage()
                .contains("marked rollback-only")),
        "the job to fail with VanillaBP's message");

    final var failure = processEngine.getManagementService()
        .createJobQuery()
        .processInstanceId(instanceId)
        .singleResult()
        .getExceptionMessage();
    // the message names the task, the process and the workflow module, so the
    // developer reading an incident does not have to guess where it came from
    assertTrue(failure.contains("nestedTransaction"), failure);
    assertTrue(failure.contains("RollbackOnlyProcess"), failure);
    assertTrue(failure.contains(MODULE_ID), failure);

    // and the workflow did NOT take the error path: nothing was committed, which is
    // exactly the data loss the check makes visible
    assertNull(repository.findById(aggregateId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("@TaskId: the task stays open, its aggregate changes commit, the job executor does not redeliver")
  public void asyncTaskStaysOpen() throws Exception {

    final var aggregateId = startSecondaryProcess("AsyncProcess", true, null);

    // the handler ran (aggregate committed) ...
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the async handler to run and commit the task id");

    // ... but the task stays open: instance alive, execution parked at the task
    final var instance = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .singleResult();
    assertNotNull(instance, "AsyncProcess stays active");
    final var activeActivities = runtimeService.getActiveActivityIds(instance.getId());
    assertEquals(List.of("AP_Task"), activeActivities, "execution parked at the async task");

    // no job remains - the job executor does NOT redeliver an open async task
    // (give a potential redelivery a moment to appear before asserting)
    Thread.sleep(500);
    assertEquals(
        0,
        processEngine.getManagementService()
            .createJobQuery()
            .processInstanceId(instance.getId())
            .count(),
        "no job pending for the parked async task");
    assertEquals("async-open", repository.findById(aggregateId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("completeTask resumes the parked async task and the process ends")
  public void completeTaskResumesProcess() throws Exception {

    final var aggregateId = startSecondaryProcess("AsyncProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the async handler to run and commit the task id");

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      aggregate.appendResult("completing");
      workflowService.completeAsyncTask(aggregate, aggregate.getTaskId());
    });

    // the signal leaves the activity; async-after parks a job - the process ends
    // through the job executor
    awaitUntil(() -> processEnded(aggregateId), "AsyncProcess to end after completeTask");
    assertEquals("async-open|completing", repository.findById(aggregateId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("cancelTask propagates the BPMN error through the error boundary")
  public void cancelTaskRoutesErrorBoundary() throws Exception {

    final var aggregateId = startSecondaryProcess("AsyncCancelProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the async handler to run and commit the task id");

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.cancelAsyncTask(aggregate, aggregate.getTaskId(), "PAYMENT_FAILED");
    });

    awaitUntil(
        () -> {
          final var results = repository.findById(aggregateId).orElseThrow().getResults();
          return (results != null) && results.contains("cancel-handled");
        },
        "the error boundary to route to the handling task");
    awaitUntil(() -> processEnded(aggregateId), "AsyncCancelProcess to end via the boundary");

  }

  @Test
  @DisplayName("completeTask inside a rolled-back transaction leaves the task open (shared transaction)")
  public void completeTaskInRolledBackTransactionLeavesTaskOpen() throws Exception {

    final var aggregateId = startSecondaryProcess("AsyncProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the async handler to run and commit the task id");
    final var taskId = repository.findById(aggregateId).orElseThrow().getTaskId();

    assertThrows(
        RuntimeException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          workflowService.completeAsyncTask(aggregate, taskId);
          throw new RuntimeException("test rollback");
        }));

    // the engine shares the caller's transaction: the rolled-back signal never
    // happened - the execution is still parked at the task
    Thread.sleep(500);
    final var instanceId = instanceIdOf(aggregateId);
    assertNotNull(instanceId, "the process must still be active");
    assertEquals(
        List.of("AP_Task"),
        runtimeService.getActiveActivityIds(instanceId),
        "the task has to stay open after the rollback");

    // the retried completion converges
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.completeAsyncTask(aggregate, taskId);
    });
    awaitUntil(() -> processEnded(aggregateId), "AsyncProcess to end after the retried completeTask");

  }

  @Test
  @DisplayName("completeTask of an unknown task raises the guiding TaskNotFoundException in the caller's transaction")
  public void completeUnknownTaskRaisesGuidingException() {

    final var aggregateId = startSecondaryProcess("AsyncProcess", true, null);

    final var exception = assertThrows(
        io.vanillabp.spi.process.TaskNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          workflowService.completeAsyncTask(aggregate, "no-such-task");
        }));

    // the probe of a task is an engine question with an exact answer, so nothing is
    // gained by asking again - and this runs in the caller's transaction (decision 27
    // of the platform's DECISIONS.md). The exception is the whole promise: an embedded
    // engine reports no visibility window, so there is no window a caller could sit out,
    // and how long the call took would say more about the machine than about the code
    assertTrue(
        exception.getMessage().contains("no-such-task"),
        "expected the unknown task to be named but got: "
            + exception.getMessage());

  }

  @Test
  @DisplayName("@TaskEvent: CANCELED is delivered when the open task's activity is canceled")
  public void taskEventCanceledDeliveredOnCancellation() throws Exception {

    final var aggregateId = startSecondaryProcess("CancelEventProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the async handler to run (CREATED event) and commit the task id");
    assertEquals("event-created", repository.findById(aggregateId).orElseThrow().getResults());

    // canceling the whole instance cancels the parked activity - the END listener
    // delivers CANCELED to the subscribing handler within the same transaction
    final var instanceId = instanceIdOf(aggregateId);
    transactionTemplate.executeWithoutResult(status -> runtimeService
        .deleteProcessInstance(instanceId, "async-task test cancellation"));

    awaitUntil(
        () -> {
          final var results = repository.findById(aggregateId).orElseThrow().getResults();
          return (results != null) && results.contains("event-canceled");
        },
        "the CANCELED event to be delivered to the handler");

  }

  @Test
  @DisplayName("Awareness edge cases and gone-task tolerance of phase two")
  public void awarenessAndPhaseTwoEdgeCases() throws Exception {

    final var aggregateId = startSecondaryProcess("AsyncProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the async handler to run and commit the task id");
    final var taskId = repository.findById(aggregateId).orElseThrow().getTaskId();

    @SuppressWarnings("unchecked")
    final var c7ProcessService = (io.vanillabp.camunda7.processservice.Camunda7ProcessService<TaskTestAggregate>) applicationContext
        .getBean("Camunda7_ProcessService_c7");

    // ACTIVE: execution exists and the business key matches
    assertEquals(
        io.vanillabp.integration.adapter.spi.WorkflowAwareness.ACTIVE,
        c7ProcessService.awarenessOfTask(SCOPE, aggregateId, taskId));
    // UNKNOWN: no such execution
    assertEquals(
        io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS,
        c7ProcessService.awarenessOfTask(SCOPE, aggregateId, "999999999"));
    // UNKNOWN: execution exists but belongs to ANOTHER workflow aggregate
    assertEquals(
        io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS,
        c7ProcessService.awarenessOfTask(SCOPE, -1L, taskId));

    // phase two tolerates a gone task (stale outbox entry - warned no-op)
    transactionTemplate.executeWithoutResult(status -> {
      goneTask(c7ProcessService, io.vanillabp.integration.spi.PhaseOperation.COMPLETE_TASK, "AsyncProcess",
          aggregateId);
      goneTask(c7ProcessService, io.vanillabp.integration.spi.PhaseOperation.CANCEL_TASK, "AsyncProcess", aggregateId);
      // user-task variants behave identically
      goneTask(c7ProcessService, io.vanillabp.integration.spi.PhaseOperation.COMPLETE_USER_TASK, "UserTaskProcess",
          aggregateId);
      goneTask(c7ProcessService, io.vanillabp.integration.spi.PhaseOperation.CANCEL_USER_TASK, "UserTaskProcess",
          aggregateId);
    });

    // user-task awareness edge cases
    assertEquals(
        io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS,
        c7ProcessService.awarenessOfUserTask(SCOPE, aggregateId, "999999999"));

    // correlation phase-two tolerance: no waiting subscription and an
    // already-started instance are warned no-ops, never errors
    transactionTemplate.executeWithoutResult(status -> {
      PhaseOperations
          .phaseTwo(
              c7ProcessService,
              io.vanillabp.integration.spi.PhaseOperation.CORRELATE_MESSAGE,
              "c7-it",
              "MessageProcess",
              null,
              aggregateId,
              java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_MESSAGE_NAME, "PaymentReceived"));
      PhaseOperations
          .phaseTwo(
              c7ProcessService,
              io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW_BY_MESSAGE,
              "c7-it",
              "AsyncProcess",
              null,
              aggregateId,
              java.util.Map.of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_MESSAGE_NAME, "OrderPlaced"));
    });
    // workflow awareness edge: unknown aggregate
    assertEquals(
        io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS,
        c7ProcessService.awarenessOfWorkflow(SCOPE, null, -1L));

    // cleanup: complete the still-open task
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.completeAsyncTask(aggregate, taskId);
    });
    awaitUntil(() -> processEnded(aggregateId), "AsyncProcess to end");

  }

  @Test
  @DisplayName("Send/business-rule/script/user/receive tasks get their async flags at parse time")
  public void mixedTaskTypesGetAsyncFlags() {

    final var definition = processEngine
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey("MixedProcess")
        .tenantIdIn(MODULE_ID)
        .latestVersion()
        .singleResult();
    assertNotNull(definition, "MixedProcess deployed");
    final var parsed = ((org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity) processEngine
        .getRepositoryService()
        .getProcessDefinition(definition.getId()));

    for (final var activityId : List.of("MX_Send", "MX_Rule", "MX_Script")) {
      final var activity = parsed.findActivity(activityId);
      assertTrue(activity.isAsyncBefore(), activityId
          + " asyncBefore");
      assertTrue(activity.isAsyncAfter(), activityId
          + " asyncAfter");
    }
    for (final var activityId : List.of("MX_User", "MX_Receive")) {
      final var activity = parsed.findActivity(activityId);
      assertTrue(activity.isAsyncAfter(), activityId
          + " asyncAfter");
    }

  }

  @Test
  @DisplayName("User-task awareness: ACTIVE with matching business key, UNKNOWN otherwise")
  public void userTaskAwarenessEdgeCases() throws Exception {

    final var aggregateId = startSecondaryProcess("UserTaskProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the CREATED notification to run");
    final var taskId = repository.findById(aggregateId).orElseThrow().getTaskId();

    @SuppressWarnings("unchecked")
    final var c7ProcessService = (io.vanillabp.camunda7.processservice.Camunda7ProcessService<TaskTestAggregate>) applicationContext
        .getBean("Camunda7_ProcessService_c7");

    assertEquals(
        io.vanillabp.integration.adapter.spi.WorkflowAwareness.ACTIVE,
        c7ProcessService.awarenessOfUserTask(SCOPE, aggregateId, taskId));
    assertEquals(
        io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS,
        c7ProcessService.awarenessOfUserTask(SCOPE, -1L, taskId));

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.completeUserTask(aggregate, taskId);
    });
    awaitUntil(() -> processEnded(aggregateId), "UserTaskProcess to end");

  }

  @Test
  @DisplayName("User task: CREATED notification, completeUserTask resumes the process")
  public void userTaskCreatedNotificationAndComplete() throws Exception {

    final var aggregateId = startSecondaryProcess("UserTaskProcess", true, null);

    // the CREATE task listener notified the optional handler (with @TaskId)
    awaitUntil(
        () -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          return (aggregate.getTaskId() != null) && aggregate.getResults().contains("usertask-created");
        },
        "the CREATED notification to run and commit the task id");

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      aggregate.appendResult("approving");
      workflowService.completeUserTask(aggregate, aggregate.getTaskId());
    });

    awaitUntil(() -> processEnded(aggregateId), "UserTaskProcess to end after completeUserTask");
    assertEquals("usertask-created|approving", repository.findById(aggregateId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("cancelUserTask routes the BPMN error through the boundary and delivers CANCELED")
  public void cancelUserTaskRoutesBoundary() throws Exception {

    final var aggregateId = startSecondaryProcess("UserTaskProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the CREATED notification to run");

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.cancelUserTask(aggregate, aggregate.getTaskId(), "PAYMENT_FAILED");
    });

    awaitUntil(
        () -> {
          final var results = repository.findById(aggregateId).orElseThrow().getResults();
          return (results != null) && results.contains("usertask-cancel-handled");
        },
        "the error boundary to route to the handling task");
    // handleBpmnError deletes the task - the DELETE listener delivered CANCELED
    assertTrue(
        repository.findById(aggregateId).orElseThrow().getResults().contains("usertask-canceled"),
        "expected the CANCELED notification but got: "
            + repository.findById(aggregateId).orElseThrow().getResults());
    awaitUntil(() -> processEnded(aggregateId), "UserTaskProcess to end via the boundary");

  }

  @Test
  @DisplayName("Instance termination delivers CANCELED to the user-task handler")
  public void userTaskCanceledOnInstanceTermination() throws Exception {

    final var aggregateId = startSecondaryProcess("UserTaskProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the CREATED notification to run");

    final var instanceId = instanceIdOf(aggregateId);
    transactionTemplate.executeWithoutResult(status -> runtimeService
        .deleteProcessInstance(instanceId, "user-task test cancellation"));

    awaitUntil(
        () -> {
          final var results = repository.findById(aggregateId).orElseThrow().getResults();
          return (results != null) && results.contains("usertask-canceled");
        },
        "the CANCELED notification to be delivered");

  }

  @Test
  @DisplayName("completeUserTask inside a rolled-back transaction leaves the task open")
  public void completeUserTaskInRolledBackTransactionLeavesTaskOpen() throws Exception {

    final var aggregateId = startSecondaryProcess("UserTaskProcess", true, null);
    awaitUntil(
        () -> repository.findById(aggregateId).orElseThrow().getTaskId() != null,
        "the CREATED notification to run");
    final var taskId = repository.findById(aggregateId).orElseThrow().getTaskId();

    assertThrows(
        RuntimeException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          workflowService.completeUserTask(aggregate, taskId);
          throw new RuntimeException("test rollback");
        }));

    Thread.sleep(UNTIL_A_COMPLETION_WOULD_HAVE_ARRIVED);
    final var instanceId = instanceIdOf(aggregateId);
    assertNotNull(instanceId, "the process must still be active");
    assertEquals(
        1,
        processEngine
            .getTaskService()
            .createTaskQuery()
            .processInstanceId(instanceId)
            .count(),
        "and the user task the rolled-back transaction completed is still open");

    // the retried completion converges
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.completeUserTask(aggregate, taskId);
    });
    awaitUntil(() -> processEnded(aggregateId), "UserTaskProcess to end after the retried completion");

  }

  @Test
  @DisplayName("A user task WITHOUT a handler boots and completes through the SPI (optional notification)")
  public void userTaskWithoutHandlerIsOptional() throws Exception {

    // SilentUserTaskProcess' user task has NO @WorkflowTask handler - the wiring
    // validation must not complain (user-task handlers are optional) and the
    // task is processed through the SPI like any externally managed task
    final var aggregateId = startSecondaryProcess("SilentUserTaskProcess", true, null);

    final var taskId = new java.util.concurrent.atomic.AtomicReference<String>();
    awaitUntil(
        () -> {
          final var instanceId = instanceIdOf(aggregateId);
          if (instanceId == null) {
            return false;
          }
          final var task = processEngine
              .getTaskService()
              .createTaskQuery()
              .processInstanceId(instanceId)
              .singleResult();
          if (task == null) {
            return false;
          }
          taskId.set(task.getId());
          return true;
        },
        "the silent user task to show up");

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.completeUserTask(aggregate, taskId.get());
    });
    awaitUntil(() -> processEnded(aggregateId), "SilentUserTaskProcess to end");

  }

  @Test
  @DisplayName("correlateMessage resumes the instance waiting at a message catch event")
  public void correlateMessageResumesProcess() throws Exception {

    final var aggregateId = startSecondaryProcess("MessageProcess", true, null);
    awaitUntil(
        () -> runtimeService
            .createExecutionQuery()
            .messageEventSubscriptionName("PaymentReceived")
            .processInstanceBusinessKey(String.valueOf(aggregateId))
            .count() > 0,
        "the instance to wait at the message catch event");

    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      aggregate.appendResult("correlating");
      workflowService.correlate(aggregate, "PaymentReceived");
    });

    awaitUntil(() -> processEnded(aggregateId), "MessageProcess to end after the correlation");
    assertEquals("correlating|message-arrived", repository.findById(aggregateId).orElseThrow().getResults());

  }

  @Test
  @DisplayName("correlateMessage with a correlation id matches via the local-variable convention")
  public void correlateMessageWithCorrelationId() throws Exception {

    final var aggregateId = startSecondaryProcess("MessageProcess", true, null);
    awaitUntil(
        () -> runtimeService
            .createExecutionQuery()
            .messageEventSubscriptionName("PaymentReceived")
            .processInstanceBusinessKey(String.valueOf(aggregateId))
            .count() > 0,
        "the instance to wait at the message catch event");

    // V1 convention: the local variable '<bpmnProcessId>-<messageName>' at the
    // subscription's execution holds the expected correlation id
    final var execution = runtimeService
        .createExecutionQuery()
        .messageEventSubscriptionName("PaymentReceived")
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .singleResult();
    // NOTE: the variable-name convention uses the PRIMARY BPMN process id of the
    // process service (V1 semantics) - here 'TaskProcess', not 'MessageProcess'
    transactionTemplate.executeWithoutResult(status -> runtimeService
        .setVariableLocal(
            execution.getId(),
            io.vanillabp.camunda7.processservice.Camunda7ProcessService
                .correlationIdVariableName("TaskProcess", "PaymentReceived"),
            "payment-42"));

    // a mismatching correlation id does not correlate - and the adapter's phase one
    // says so where the application called it, instead of
    // letting the correlation fail behind the commit
    final var mismatch = assertThrows(
        IllegalStateException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          workflowService.correlate(aggregate, "PaymentReceived", "wrong-id");
        }));
    assertTrue(mismatch.getMessage().contains("wrong-id"), mismatch.getMessage());
    assertNotNull(instanceIdOf(aggregateId), "the instance must still wait");

    // the matching one does
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.correlate(aggregate, "PaymentReceived", "payment-42");
    });
    awaitUntil(() -> processEnded(aggregateId), "MessageProcess to end after the matching correlation");

  }

  @Test
  @DisplayName("A rolled-back correlation leaves the instance waiting (shared transaction)")
  public void rolledBackCorrelationLeavesInstanceWaiting() throws Exception {

    final var aggregateId = startSecondaryProcess("MessageProcess", true, null);
    awaitUntil(
        () -> runtimeService
            .createExecutionQuery()
            .messageEventSubscriptionName("PaymentReceived")
            .processInstanceBusinessKey(String.valueOf(aggregateId))
            .count() > 0,
        "the instance to wait at the message catch event");

    assertThrows(
        RuntimeException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          workflowService.correlate(aggregate, "PaymentReceived");
          throw new RuntimeException("test rollback");
        }));

    Thread.sleep(UNTIL_A_COMPLETION_WOULD_HAVE_ARRIVED);
    assertNotNull(instanceIdOf(aggregateId), "the rolled-back correlation must leave the instance waiting");
    assertEquals(
        1,
        runtimeService
            .createExecutionQuery()
            .messageEventSubscriptionName("PaymentReceived")
            .processInstanceBusinessKey(String.valueOf(aggregateId))
            .count(),
        "and the instance still waits at the message catch event");

    // retried correlation converges
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = repository.findById(aggregateId).orElseThrow();
      workflowService.correlate(aggregate, "PaymentReceived");
    });
    awaitUntil(() -> processEnded(aggregateId), "MessageProcess to end after the retried correlation");

  }

  @Test
  @DisplayName("startWorkflowByMessage starts the instance via the message start event")
  public void startWorkflowByMessageStartsInstance() throws Exception {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(true);
      final var saved = repository.save(aggregate);
      workflowService.startByMessage(saved, "OrderPlaced");
      return saved.getId();
    });

    awaitUntil(
        () -> {
          final var results = repository.findById(aggregateId).orElseThrow().getResults();
          return (results != null) && results.contains("order-placed");
        },
        "the message start event to start the instance");
    awaitUntil(() -> processEnded(aggregateId), "MessageStartProcess to end");

    // the start by message left the id of its instance behind, like every other start
    final var instanceId = processEngine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .singleResult()
        .getId();
    final var startsOfTheAggregate = TaskDeliveryLogReader
        .of(applicationDataSource)
        .workflowStartsOfAggregate(String.valueOf(aggregateId));
    // two rows name the same instance. Phase two writes one under the process of the workflow
    // service which asked for the start, and only the report of phase two puts it there. The
    // listener on the start event writes the other one under the process the message started
    assertEquals(
        java.util.Map.of("TaskProcess", instanceId, "MessageStartProcess", instanceId),
        startsOfTheAggregate
            .stream()
            .collect(
                java.util.stream.Collectors
                    .toMap(TaskDeliveryLogReader.Delivery::bpmnProcessId, TaskDeliveryLogReader.Delivery::workflowId)),
        () -> "the rows about the start: "
            + startsOfTheAggregate);

  }

  /**
   * Phase two of a start by message names the instance the message created, and a start which
   * finds its workflow running already names nothing. What phase two reported is listened to
   * directly, apart from any row the listener on the start event writes.
   */
  @Test
  @DisplayName("Phase two of a start by message reports the process instance the message created")
  public void phaseTwoOfAStartByMessageReportsTheInstanceItCreated() {

    @SuppressWarnings("unchecked")
    final var c7ProcessService = (io.vanillabp.camunda7.processservice.Camunda7ProcessService<TaskTestAggregate>) applicationContext
        .getBean("Camunda7_ProcessService_c7");
    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(true);
      return repository.save(aggregate).getId();
    });

    final List<String> reported = new java.util.ArrayList<>();
    final var instanceId = transactionTemplate.execute(status -> {
      final var startByMessage = java.util.Map
          .of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_MESSAGE_NAME, "OrderPlaced");
      PhaseOperations
          .phaseTwoOfAStart(
              c7ProcessService, io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW_BY_MESSAGE,
              MODULE_ID, "MessageStartProcess", aggregateId, startByMessage, reported::add);
      // nothing runs before the commit, so the second dispatch finds the workflow running
      PhaseOperations
          .phaseTwoOfAStart(
              c7ProcessService, io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW_BY_MESSAGE,
              MODULE_ID, "MessageStartProcess", aggregateId, startByMessage, reported::add);
      return runtimeService
          .createProcessInstanceQuery()
          .processInstanceBusinessKey(String.valueOf(aggregateId))
          .singleResult()
          .getProcessInstanceId();
    });

    assertEquals(List.of(instanceId), reported);

  }

  @Test
  @DisplayName("Correlating an unknown workflow raises the guiding WorkflowNotFoundException")
  public void correlateUnknownWorkflowRaisesGuidingException() {

    final var aggregateId = transactionTemplate.execute(status -> repository
        .save(new TaskTestAggregate())
        .getId());

    final var exception = assertThrows(
        io.vanillabp.spi.process.WorkflowNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(status -> {
          final var aggregate = repository.findById(aggregateId).orElseThrow();
          workflowService.correlate(aggregate, "PaymentReceived");
        }));
    assertTrue(exception.getMessage().contains("startWorkflowByMessage"));

  }

  @Test
  @DisplayName("Multi-instance: collection from an aggregate attribute, index/total/element bound")
  public void multiInstanceBindings() throws Exception {

    TaskTestWorkflowService.ACTIVATIONS.clear();
    final var aggregateId = startSecondaryProcess(
        "MultiInstanceProcess", true, List.of("a", "b", "c"));
    awaitUntil(() -> processEnded(aggregateId), "MultiInstanceProcess to end");

    assertEquals(
        "a0/3|b1/3|c2/3",
        repository.findById(aggregateId).orElseThrow().getResults());

    // every element of the multi-instance activity is its OWN activation, which is what
    // keeps the correlations of three siblings from sharing an idempotency key. Camunda
    // 7 reports no delivery id at all and answers this question all the same
    final var activations = List.copyOf(TaskTestWorkflowService.ACTIVATIONS);
    assertEquals(3, activations.size(), "one activation per element: "
        + activations);
    assertEquals(
        3,
        activations.stream().distinct().count(),
        "three elements, three activations: "
            + activations);
    activations
        .forEach(activation -> assertNotNull(
            activation,
            "the adapter names its activations: "
                + activations));

  }

  @Test
  @DisplayName("The parse listener forces async-before/after onto service tasks")
  public void asyncBeforeAndAfterForcedOntoServiceTasks() {

    final var configuration = (ProcessEngineConfigurationImpl) processEngine
        .getProcessEngineConfiguration();
    // deployment-cache access needs an engine command context
    final var processDefinition = configuration
        .getCommandExecutorTxRequired()
        .execute(commandContext -> configuration
            .getDeploymentCache()
            .findDeployedLatestProcessDefinitionByKeyAndTenantId("TaskProcess", MODULE_ID));

    final var happyTask = processDefinition.findActivity("TP_Happy");
    assertTrue(happyTask.isAsyncBefore(), "asyncBefore forced onto the service task");
    assertTrue(happyTask.isAsyncAfter(), "asyncAfter forced onto the service task");

  }

  /**
   * Dispatches phase two of a task operation for a task which is not there any more -
   * the stale outbox entry every adapter has to tolerate.
   */
  private static void goneTask(
      final io.vanillabp.integration.adapter.spi.MigratableProcessService<TaskTestAggregate> adapter,
      final io.vanillabp.integration.spi.PhaseOperation operation,
      final String bpmnProcessId,
      final Long aggregateId) {

    PhaseOperations
        .phaseTwo(
            adapter,
            operation,
            "c7-it",
            bpmnProcessId,
            null,
            aggregateId,
            java.util.Map
                .of(
                    io.vanillabp.integration.spi.PhaseTwoCall.ARG_TASK_ID, "999999999",
                    io.vanillabp.integration.spi.PhaseTwoCall.ARG_BPMN_ERROR_CODE, "ERR"));

  }

  @Test
  @DisplayName("A business rule task evaluates the module's decision table, and the result reaches the handler")
  public void aDecisionTableOfTheModuleIsDeployedAndEvaluated() throws Exception {

    // the decision table sits next to the BPMN files of this module, so the boot
    // deployed it with them - nothing here deploys anything
    final var approvedId = startBusinessRuleProcess(true);
    awaitUntil(() -> processEnded(approvedId), "the approved BusinessRuleProcess to end");
    assertEquals("rating=GOOD", repository.findById(approvedId).orElseThrow().getResults());

    // the other rule of the same table, so it really is the decision deciding and not
    // a constant somewhere
    final var deniedId = startBusinessRuleProcess(false);
    awaitUntil(() -> processEnded(deniedId), "the denied BusinessRuleProcess to end");
    assertEquals("rating=POOR", repository.findById(deniedId).orElseThrow().getResults());

    // and the engine holds it as a decision definition of this module's tenant
    final var decision = processEngine
        .getRepositoryService()
        .createDecisionDefinitionQuery()
        .decisionDefinitionKey("creditRating")
        .tenantIdIn(MODULE_ID)
        .singleResult();
    assertNotNull(decision, "the decision table was deployed with the module's processes");

  }

}
