package io.vanillabp.camunda7.quarkus.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Camunda 7 adapter's documented features, run end to end on a BOOTED Quarkus
 * application against the embedded engine on H2.
 * <p>
 * This duplicates what the Spring Boot suite in {@code integration-tests} proves, and
 * the duplication is the point: the adapter's platform-neutral core being correct
 * says nothing about a platform's glue ever calling it. Coverage is measured per
 * platform for exactly that reason, so the core lines Quarkus never reaches name the
 * features Quarkus never runs - deploying into a tenant, starting a workflow through
 * the two-phase outbox, delivering a task, completing and cancelling one, notifying
 * about user tasks, correlating a message, broadcasting a signal, pushing a changed
 * aggregate and matching process versions.
 * <p>
 * Everything is observed through the application's own <code>introspect/...</code>
 * endpoints, because a prod-mode test runs the application in a forked JVM. The
 * JaCoCo agent is forwarded into it, otherwise the run would prove the features and
 * count as nothing.
 * <p>
 * Four things of the Spring Boot suite are deliberately NOT repeated here, and the
 * reason is the same for all of them - they are about a second boot or a second
 * engine, neither of which a prod-mode test can produce inside one class:
 * <ul>
 * <li>the startup check for old process versions needs five boots against
 * one database, each with a different model or configuration;</li>
 * <li>two adapter ids on one datasource kept apart by a table prefix and
 * two engines side by side are covered at extension level in
 * {@code quarkus/deployment}, where a second configuration is a second test class;
 * </li>
 * <li>the name-clash-avoidance mode {@code use-prefix} is a configuration
 * variant of the same code path, and its remaining lines are uncovered on Spring Boot
 * as well;</li>
 * <li>a task-scoped push into ONE iteration of a multi-instance subprocess
 * needs the job executor parked while the test reads execution scopes; the scope
 * SELECTION it proves is exercised here by the boundary-event case below.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7WorkflowLifecycleTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addPackage("io.vanillabp.camunda7.quarkus.test")
          .addAsResource("application.yaml")
          .addAsResource("c7-e2e/processes/task-matrix.bpmn")
          .addAsResource("c7-e2e/processes/signal-catch.bpmn")
          .addAsResource("c7-e2e/processes/aggregate-changed.bpmn")
          .addAsResource("c7-e2e/processes/timer-start.bpmn")
          .addAsResource("c7-e2e/processes/versioned-process.bpmn")
          // deployed by the test WHILE the application runs, so it must travel with
          // it but must not sit in the workflow module's resources location
          .addAsResource("c7-e2e/versioned/versioned-process-v2.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // JVM args needed for tracking coverage - check this module's POM for the
      // systemPropertyVariables feeding 'jacoco.agent'
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .setRun(true)
      .setRuntimeProperties(Map.of(
          "quarkus.http.port", Integer.toString(OneFreePortPerJvm.getPort()),
          // the application runs in a forked JVM, so its own log is the only place a
          // failure inside it can be read afterwards
          "quarkus.log.file.enable", "true",
          "quarkus.log.file.path", Path
              .of("target", "c7-e2e-application.log")
              .toAbsolutePath()
              .toString()));

  private static final String MODULE = "c7-e2e";

  private static final long TIMEOUT_MS = 60_000;

  // --- talking to the application ---

  private static RequestSpecification api() {

    return RestAssured
        .given()
        .baseUri("http://localhost")
        .port(OneFreePortPerJvm.getPort());

  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> post(
      final String path) {

    return api()
        .post(path)
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);

  }

  private static void postWithoutResponse(
      final String path) {

    api()
        .post(path)
        .then()
        .statusCode(204);

  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(
      final String path) {

    return api()
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .as(Map.class);

  }

  private static List<String> strings(
      final String path) {

    return api()
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("$", String.class);

  }

  private static String text(
      final String path) {

    return api()
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .asString();

  }

  private static void await(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + TIMEOUT_MS;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(100);
    }

  }

  /**
   * Two seconds is more than the outbox needs to dispatch (poll interval 0.5 s) and
   * more than the job executor needs to pick a job up - long enough to make "nothing
   * happened" a statement rather than a race.
   */
  private static void awaitNothingElseHappens() throws InterruptedException {

    Thread.sleep(2000);

  }

  // --- the workflow the tests drive ---

  private static String startWorkflow(
      final boolean approved) {

    final var reported = post("introspect/workflows/"
        + approved);
    // the workflow is created by the phase-two outbox, so nothing may
    // exist while the caller's transaction is still open
    assertEquals(
        0,
        ((Number) reported.get("instancesInsideTransaction")).intValue(),
        "the workflow may not be created inside the caller's transaction");
    return reported
        .get("id")
        .toString();

  }

  private static String startProcess(
      final String bpmnProcessId,
      final boolean approved) {

    return post("introspect/processes/%s/%s".formatted(bpmnProcessId, approved))
        .get("id")
        .toString();

  }

  private static String resultsOf(
      final String aggregateId) {

    final var value = object("introspect/aggregates/"
        + aggregateId)
        .get("results");
    return value == null
        ? null
        : value.toString();

  }

  private static String taskIdOf(
      final String aggregateId) {

    final var value = object("introspect/aggregates/"
        + aggregateId)
        .get("taskId");
    return value == null
        ? null
        : value.toString();

  }

  private static boolean ended(
      final String aggregateId) {

    return Boolean.parseBoolean(text("introspect/engine/ended/"
        + aggregateId));

  }

  private static long instances(
      final String aggregateId) {

    return Long.parseLong(text("introspect/engine/instances/"
        + aggregateId));

  }

  private static boolean executionExists(
      final String executionId) {

    return Boolean.parseBoolean(text("introspect/engine/executions/"
        + executionId));

  }

  /**
   * Waits until the handler carrying <code>&#64;TaskId</code> committed the parked
   * execution into the aggregate.
   */
  private static String awaitParkedTask(
      final String aggregateId) throws InterruptedException {

    await(() -> taskIdOf(aggregateId) != null, "the handler to park the task of aggregate "
        + aggregateId);
    return taskIdOf(aggregateId);

  }

  // --- deployment ---

  @Test
  @DisplayName("The boot deploys the module's BPMN with the workflow module id as the Camunda tenant")
  public void deploymentUsesTheWorkflowModuleAsTenant() {

    assertEquals(MODULE, text("introspect/workflow-module"));

    final var definitions = strings("introspect/engine/definitions");
    assertTrue(
        definitions.contains("TaskProcess|%s|1|null".formatted(MODULE)),
        "the primary process is deployed into the module's tenant, but got: "
            + definitions);
    assertTrue(
        definitions
            .stream()
            .allMatch(definition -> definition.contains("|%s|".formatted(MODULE))),
        "no definition may be deployed without the tenant: "
            + definitions);
    // every BPMN of the resources location is deployed, the ones nobody starts
    // included
    assertTrue(
        definitions
            .stream()
            .anyMatch(definition -> definition.startsWith("SignalCatchProcess|")),
        definitions.toString());

  }

  @Test
  @DisplayName("The parse listener forces the asynchronous continuations onto the task types it has to")
  public void theParseListenerForcesAsyncContinuations() {

    final var taskProcess = strings("introspect/engine/async-flags/TaskProcess");
    assertTrue(taskProcess.contains("TP_Happy|true|true"), "asyncBefore and asyncAfter on a service task: "
        + taskProcess);

    final var mixed = strings("introspect/engine/async-flags/MixedProcess");
    for (final var activity : List.of("MX_Send", "MX_Rule", "MX_Script")) {
      assertTrue(mixed.contains(activity
          + "|true|true"), activity
              + " in "
              + mixed);
    }
    for (final var activity : List.of("MX_User", "MX_Receive")) {
      assertTrue(
          mixed
              .stream()
              .anyMatch(entry -> entry.startsWith(activity
                  + "|") && entry.endsWith("|true")),
          activity
              + " needs asyncAfter, in "
              + mixed);
    }

  }

  // --- starting a workflow ---

  @Test
  @DisplayName("startWorkflow creates the instance after the commit, and the gateway reads the live aggregate")
  public void startWorkflowIsTwoPhaseAndTheGatewayReadsTheAggregate() throws Exception {

    final var approved = startWorkflow(true);
    await(() -> ended(approved), "the approved TaskProcess to end");
    assertEquals("happy|approved", resultsOf(approved));

    // approved=false: the gateway's default flow skips 'afterApproval' - the
    // condition is evaluated against the aggregate, not against a copy
    final var rejected = startProcess("TaskProcess", false);
    await(() -> ended(rejected), "the rejected TaskProcess to end");
    assertEquals("happy", resultsOf(rejected));

  }

  @Test
  @DisplayName("A rollback removes both the aggregate and the workflow - the engine shares the transaction")
  public void rollbackRemovesTheAggregateAndTheWorkflow() throws Exception {

    final var aggregateId = post("introspect/workflows/true/rollback")
        .get("id")
        .toString();

    awaitNothingElseHappens();
    assertEquals(Boolean.FALSE, object("introspect/aggregates/"
        + aggregateId)
        .get("exists"), "the aggregate was rolled back");
    assertEquals(0, instances(aggregateId), "no workflow may have been created");
    assertFalse(ended(aggregateId), "and none may have run either");

  }

  // --- delivering a task ---

  @Test
  @DisplayName("A TaskException routes through the error boundary and COMMITS the aggregate changes")
  public void taskExceptionRoutesTheErrorBoundary() throws Exception {

    final var aggregateId = startProcess("ErrorProcess", true);
    await(() -> ended(aggregateId), "ErrorProcess to end through the error boundary");

    // the mutation of the THROWING handler is visible, together with the boundary path
    assertEquals("error-raised|handled", resultsOf(aggregateId));

  }

  @Test
  @DisplayName("A technical exception rolls the job transaction back and decrements the retries")
  public void technicalExceptionRollsBackAndRetries() throws Exception {

    final var aggregateId = startProcess("FailProcess", true);

    await(
        () -> {
          final var retries = Integer.parseInt(text("introspect/engine/retries/"
              + aggregateId));
          return (retries >= 0) && (retries < 3);
        },
        "the failing job's retries to be decremented");

    assertEquals(1, instances(aggregateId), "the task was not completed, so the workflow stays");
    assertNull(resultsOf(aggregateId), "the handler's mutation was rolled back with the job's transaction");

  }

  @Test
  @DisplayName("A transaction annotation in the handler's call chain fails the job with VanillaBP's message")
  public void aNestedTransactionAnnotationIsReportedAtTheEngine() throws Exception {

    // the handler carries no annotation itself (that would fail the boot), it calls a
    // bean that does - and the engine shares the transaction that
    // bean's interceptor marks rollback-only
    final var aggregateId = startProcess("RollbackOnlyProcess", true);

    await(
        () -> text("introspect/engine/job-failure/"
            + aggregateId).contains("marked rollback-only"),
        "the job to fail with VanillaBP's message");

    // the message names the task, the process and the workflow module, so nobody
    // reading an incident has to guess where it came from
    final var failure = text("introspect/engine/job-failure/"
        + aggregateId);
    assertTrue(failure.contains("nestedTransaction"), failure);
    assertTrue(failure.contains("RollbackOnlyProcess"), failure);
    assertTrue(failure.contains(MODULE), failure);
    assertNull(resultsOf(aggregateId), "nothing was committed - the data loss the check makes visible");

  }

  @Test
  @DisplayName("Multi-instance: the collection comes from the aggregate, index, total and element are bound")
  public void multiInstanceBindsIndexTotalAndElement() throws Exception {

    final var aggregateId = post("introspect/processes/MultiInstanceProcess/true?items=a,b,c")
        .get("id")
        .toString();
    await(() -> ended(aggregateId), "MultiInstanceProcess to end");

    assertEquals("a0/3|b1/3|c2/3", resultsOf(aggregateId));

  }

  // --- asynchronous tasks ---

  @Test
  @DisplayName("A @TaskId handler parks the task, completeTask resumes it after the commit")
  public void asyncTaskStaysOpenAndIsCompletedAfterTheCommit() throws Exception {

    final var aggregateId = startProcess("AsyncProcess", true);
    final var taskId = awaitParkedTask(aggregateId);

    assertEquals("async-open", resultsOf(aggregateId));
    awaitNothingElseHappens();
    assertTrue(executionExists(taskId), "the job executor must not redeliver a parked task");

    final var reported = post("introspect/tasks/%s/complete/%s".formatted(taskId, aggregateId));
    assertEquals(
        Boolean.TRUE,
        reported.get("parkedInsideTransaction"),
        "the engine may not be touched inside the caller's transaction - that is what "
            + "makes the operation repeatable when it loses a conflict");

    await(() -> ended(aggregateId), "AsyncProcess to end after the commit");

  }

  @Test
  @DisplayName("completeTask inside a rolled-back transaction leaves the task open")
  public void completeTaskInARolledBackTransactionChangesNothing() throws Exception {

    final var aggregateId = startProcess("AsyncProcess", true);
    final var taskId = awaitParkedTask(aggregateId);

    post("introspect/tasks/%s/complete-and-rollback/%s".formatted(taskId, aggregateId));

    awaitNothingElseHappens();
    assertTrue(executionExists(taskId), "a rolled-back completion must leave the task where it was");
    assertFalse(ended(aggregateId));

  }

  @Test
  @DisplayName("completeTask of an unknown task raises the guiding TaskNotFoundException")
  public void completingAnUnknownTaskRaisesTheGuidingException() {

    final var aggregateId = post("introspect/aggregates")
        .get("id")
        .toString();

    final var failed = post("introspect/tasks/999999999/complete/"
        + aggregateId);
    assertEquals("TaskNotFoundException", failed.get("rootException"));
    assertTrue(
        failed
            .get("rootMessage")
            .toString()
            .contains("999999999"),
        "the message has to name the task but was: "
            + failed.get("rootMessage"));

  }

  @Test
  @DisplayName("cancelTask propagates the BPMN error through the boundary event")
  public void cancelTaskPropagatesTheBpmnError() throws Exception {

    final var aggregateId = startProcess("AsyncCancelProcess", true);
    final var taskId = awaitParkedTask(aggregateId);

    post("introspect/tasks/%s/cancel/%s/PAYMENT_FAILED".formatted(taskId, aggregateId));

    await(() -> ended(aggregateId), "AsyncCancelProcess to end through the error boundary");
    assertEquals("await-cancel|cancel-handled", resultsOf(aggregateId));

  }

  @Test
  @DisplayName("@TaskEvent: CANCELED is delivered when the open task's activity is canceled")
  public void canceledIsDeliveredWhenTheActivityGoesAway() throws Exception {

    final var aggregateId = startProcess("CancelEventProcess", true);
    await(() -> "event-created".equals(resultsOf(aggregateId)), "the CREATED event to park the task");

    postWithoutResponse("introspect/engine/instances/%s/terminate".formatted(aggregateId));

    await(
        () -> {
          final var results = resultsOf(aggregateId);
          return (results != null) && results.contains("event-canceled");
        },
        "the CANCELED event to be delivered, results were: "
            + resultsOf(aggregateId));

  }

  // --- user tasks ---

  @Test
  @DisplayName("A user task notifies on creation and completeUserTask resumes the workflow")
  public void userTaskNotificationAndCompletion() throws Exception {

    final var aggregateId = startProcess("UserTaskProcess", true);
    await(() -> "usertask-created".equals(resultsOf(aggregateId)), "the user task's CREATED notification");

    final var taskId = taskIdOf(aggregateId);
    assertTrue(strings("introspect/engine/user-tasks/"
        + aggregateId).contains(taskId), "the notification carries the engine's task id");

    post("introspect/user-tasks/%s/complete/%s".formatted(taskId, aggregateId));

    await(() -> ended(aggregateId), "UserTaskProcess to end after the commit");
    assertEquals("usertask-created", resultsOf(aggregateId), "completing is not an event of its own");

  }

  @Test
  @DisplayName("cancelUserTask routes the BPMN error through the boundary and delivers CANCELED")
  public void cancelUserTaskRoutesTheBpmnError() throws Exception {

    final var aggregateId = startProcess("UserTaskProcess", true);
    await(() -> "usertask-created".equals(resultsOf(aggregateId)), "the user task's CREATED notification");
    final var taskId = taskIdOf(aggregateId);

    post("introspect/user-tasks/%s/cancel/%s/PAYMENT_FAILED".formatted(taskId, aggregateId));

    await(() -> ended(aggregateId), "UserTaskProcess to end through the error boundary");
    final var results = resultsOf(aggregateId);
    assertTrue(results.contains("usertask-canceled"), "the CANCELED notification is delivered: "
        + results);
    assertTrue(results.contains("usertask-cancel-handled"), "and the boundary path ran: "
        + results);

  }

  @Test
  @DisplayName("completeUserTask inside a rolled-back transaction leaves the task open")
  public void completeUserTaskInARolledBackTransactionChangesNothing() throws Exception {

    final var aggregateId = startProcess("UserTaskProcess", true);
    await(() -> "usertask-created".equals(resultsOf(aggregateId)), "the user task's CREATED notification");
    final var taskId = taskIdOf(aggregateId);

    post("introspect/user-tasks/%s/complete-and-rollback/%s".formatted(taskId, aggregateId));

    awaitNothingElseHappens();
    assertTrue(
        strings("introspect/engine/user-tasks/"
            + aggregateId).contains(taskId),
        "a rolled-back completion must leave the user task where it was");

  }

  @Test
  @DisplayName("A user task WITHOUT a handler boots and is completed through the engine")
  public void aUserTaskWithoutAHandlerStillWorks() throws Exception {

    final var aggregateId = startProcess("SilentUserTaskProcess", true);
    await(
        () -> !strings("introspect/engine/user-tasks/"
            + aggregateId).isEmpty(),
        "the user task nobody is notified about to show up");
    assertNull(resultsOf(aggregateId), "a task without a handler notifies nobody");

    postWithoutResponse("introspect/engine/user-tasks/%s/complete"
        .formatted(strings("introspect/engine/user-tasks/"
            + aggregateId).getFirst()));

    await(() -> ended(aggregateId), "SilentUserTaskProcess to end");

  }

  // --- messages ---

  @Test
  @DisplayName("correlateMessage resumes the workflow waiting at the message catch event")
  public void correlateMessageResumesTheWaitingWorkflow() throws Exception {

    final var aggregateId = startProcess("MessageProcess", true);
    await(
        () -> !text("introspect/engine/message-executions/%s/PaymentReceived".formatted(aggregateId)).isEmpty(),
        "the workflow to wait at the message catch event");

    post("introspect/messages/PaymentReceived/correlate/"
        + aggregateId);

    await(() -> ended(aggregateId), "MessageProcess to end after the correlation");
    assertEquals("message-arrived", resultsOf(aggregateId));

  }

  @Test
  @DisplayName("A correlation id has to match the local-variable convention, a rollback correlates nothing")
  public void correlationIdAndRollback() throws Exception {

    final var aggregateId = startProcess("MessageProcess", true);
    await(
        () -> !text("introspect/engine/message-executions/%s/PaymentReceived".formatted(aggregateId)).isEmpty(),
        "the workflow to wait at the message catch event");
    final var execution = text("introspect/engine/message-executions/%s/PaymentReceived".formatted(aggregateId));

    postWithoutResponse(
        "introspect/engine/message-executions/%s/correlation-id/PaymentReceived/payment-42".formatted(execution));

    // a mismatching correlation id does not correlate - and phase one says so where
    // the application called it, instead of failing behind the commit
    final var mismatch = post("introspect/messages/PaymentReceived/correlate/%s/wrong-id".formatted(aggregateId));
    assertTrue(
        mismatch
            .get("rootMessage")
            .toString()
            .contains("wrong-id"),
        String.valueOf(mismatch.get("rootMessage")));
    assertEquals(1, instances(aggregateId), "the workflow has to keep waiting");

    // a rolled-back correlation changes nothing either - the embedded engine shares
    // the caller's transaction
    post("introspect/messages/PaymentReceived/correlate-and-rollback/"
        + aggregateId);
    awaitNothingElseHappens();
    assertEquals(1, instances(aggregateId), "a rolled-back correlation must leave the workflow waiting");

    post("introspect/messages/PaymentReceived/correlate/%s/payment-42".formatted(aggregateId));
    await(() -> ended(aggregateId), "MessageProcess to end after the matching correlation");

  }

  @Test
  @DisplayName("startWorkflowByMessage starts the process of its own process service through its message start event")
  public void startWorkflowByMessageStartsTheWorkflow() throws Exception {

    final var aggregateId = post("introspect/messages/TaskRequested/start")
        .get("id")
        .toString();

    await(() -> ended(aggregateId), "TaskProcess to run through");
    assertEquals("happy", resultsOf(aggregateId));

  }

  @Test
  @DisplayName("startWorkflowByMessage refuses a message which starts another process")
  public void startWorkflowByMessageRefusesTheMessageOfAnotherProcess() throws Exception {

    final var aggregateId = post("introspect/aggregates")
        .get("id")
        .toString();

    // OrderPlaced starts MessageStartProcess, which is not the process of this service
    final var refused = post("introspect/messages/OrderPlaced/start/"
        + aggregateId);
    assertEquals("IllegalArgumentException", refused.get("rootException"));
    assertTrue(
        refused
            .get("rootMessage")
            .toString()
            .contains("Message 'OrderPlaced' does not start BPMN process 'TaskProcess' of workflow module"),
        String.valueOf(refused.get("rootMessage")));
    awaitNothingElseHappens();
    assertEquals(0, instances(aggregateId), "a refused message must start no process at all");

  }

  @Test
  @DisplayName("Correlating a workflow nobody started raises the guiding WorkflowNotFoundException")
  public void correlatingAnUnknownWorkflowRaisesTheGuidingException() {

    final var aggregateId = post("introspect/aggregates")
        .get("id")
        .toString();

    final var failed = post("introspect/messages/PaymentReceived/correlate/"
        + aggregateId);
    assertEquals("WorkflowNotFoundException", failed.get("rootException"));
    assertTrue(
        failed
            .get("rootMessage")
            .toString()
            .contains("startWorkflowByMessage"),
        "the message has to name the way out but was: "
            + failed.get("rootMessage"));

  }

  // --- signals ---

  @Test
  @DisplayName("A broadcast signal continues the waiting workflow, one in a rolled-back transaction does not")
  public void sendSignalContinuesTheWaitingWorkflow() throws Exception {

    final var aggregateId = startProcess("SignalCatchProcess", true);
    await(() -> instances(aggregateId) == 1, "the workflow to wait at the signal catch event");

    postWithoutResponse("introspect/signals/OrderReceived/rollback");
    awaitNothingElseHappens();
    assertFalse(ended(aggregateId), "a broadcast in a rolled-back transaction never happened");
    assertNull(resultsOf(aggregateId));

    postWithoutResponse("introspect/signals/OrderReceived");

    await(() -> ended(aggregateId), "the broadcast to continue the waiting workflow");
    assertEquals("signal-received", resultsOf(aggregateId));

  }

  // --- pushing a changed aggregate ---

  @Test
  @DisplayName("A conditional event fires once the changed aggregate was pushed")
  public void aConditionalEventWaitsForThePush() throws Exception {

    final var aggregateId = post("introspect/push/workflows")
        .get("id")
        .toString();
    await(() -> instances(aggregateId) == 1, "the workflow to wait at the conditional event");

    // the attribute the condition reads is not set yet, and nothing told the engine
    awaitNothingElseHappens();
    assertNull(
        object("introspect/push-aggregates/"
            + aggregateId).get("processedBy"),
        "the workflow may not continue before the aggregate was pushed");

    postWithoutResponse("introspect/push/%s/ready".formatted(aggregateId));

    await(
        () -> "conditionMet".equals(object("introspect/push-aggregates/"
            + aggregateId).get("processedBy")),
        "the task behind the conditional event to run");

  }

  @Test
  @DisplayName("A global push lands at the workflow's scope")
  public void aGlobalPushReachesTheWorkflowScope() throws Exception {

    final var aggregateId = post("introspect/push/processes/AggregateChangedMultiInstanceProcess")
        .get("id")
        .toString();
    await(
        () -> {
          final var taskIds = object("introspect/push-aggregates/"
              + aggregateId).get("taskIds");
          return (taskIds != null) && (taskIds.toString().split(",").length == 2);
        },
        "both iterations of the multi-instance subprocess to park");

    postWithoutResponse("introspect/push/%s/escalate".formatted(aggregateId));

    final var instanceId = text("introspect/engine/instance-id/"
        + aggregateId);
    await(
        () -> object("introspect/engine/local-variables/"
            + instanceId).containsKey("escalate"),
        "the global push to land at the workflow's scope");

  }

  @Test
  @DisplayName("A task with a scope of its own is not the scope meant - the push goes around it")
  public void aTaskScopeIsSkipped() throws Exception {

    final var aggregateId = post("introspect/push/processes/AggregateChangedBoundaryProcess")
        .get("id")
        .toString();
    await(
        () -> object("introspect/push-aggregates/"
            + aggregateId).get("taskIds") != null,
        "the workflow to park at the task carrying a boundary event");
    final var taskId = object("introspect/push-aggregates/"
        + aggregateId)
        .get("taskIds")
        .toString();

    postWithoutResponse("introspect/push/%s/escalate/%s".formatted(aggregateId, taskId));

    final var instanceId = text("introspect/engine/instance-id/"
        + aggregateId);
    await(
        () -> object("introspect/engine/local-variables/"
            + instanceId).containsKey("escalate"),
        "the scope around the task is the workflow itself here");
    // the boundary event makes the engine give the activity a scope of its own, and
    // that scope is the task's context - not the scope the task RUNS in
    assertFalse(
        object("introspect/engine/local-variables/"
            + taskId).containsKey("escalate"),
        "the activity's own scope may not be written at");

  }

  // --- process versions ---

  @Test
  @DisplayName("The version of the deployed process definition decides which method serves the task")
  public void theDeployedVersionDecidesWhichMethodServesTheTask() throws Exception {

    // the application deployed version 1 while booting
    final var first = startProcess("VersionedProcess", true);
    await(() -> "firstVersion".equals(resultsOf(first)), "version 1 to be served by the method naming version '1'");

    // a second version, deployed while the application runs and tagged 'release-2' -
    // the way another node of a rolling deployment does it
    postWithoutResponse("introspect/engine/deploy-version-two");

    final var second = startProcess("VersionedProcess", true);
    await(
        () -> "taggedVersion".equals(resultsOf(second)),
        "version 2 to be served by the method naming its version tag - the engine is asked "
            + "about it, since this application never deployed that version");

  }

  // --- a workflow the engine starts on its own ---

  @Test
  @DisplayName("A timer start event creates the aggregate, the task finds it and the end is reported")
  public void theEngineStartsAWorkflowOnItsOwn() throws Exception {

    // the timer fires one second after the deployment, and the job executor runs
    // because the workflow module started its processing
    await(() -> !strings("introspect/timer-aggregates").isEmpty(), "the timer to fire and the aggregate to be created");

    await(
        () -> strings("introspect/timer-aggregates")
            .stream()
            .anyMatch(reported -> reported.contains("|recordStart|") && reported.contains("|COMPLETED/")),
        "the task following the timer start event to run and the end to be reported, but got: "
            + strings("introspect/timer-aggregates"));

    // the name of the workflow is the one the application gave in its
    // @WorkflowStartedByBpms method, and the engine holds it as the business key
    assertTrue(
        strings("introspect/timer-aggregates")
            .stream()
            .anyMatch(reported -> reported.startsWith("timer-")),
        "the workflow carries the name the application gave it: "
            + strings("introspect/timer-aggregates"));

  }

  @Test
  @DisplayName("A start carrying a business key of its own is refused, and the message says why")
  public void aWorkflowStartedPastVanillaBpUnderItsOwnName() throws Exception {

    // the same process, started through the engine by somebody who never asked
    // VanillaBP - which is what anybody with access to the engine can do
    final var refused = post("introspect/started-past-vanillabp/started-past-vanillabp")
        .get("refused");

    assertNotNull(refused, "the start had to be refused");
    final var message = String.valueOf(refused);
    assertTrue(message.contains("started-past-vanillabp"), "the key is named: "
        + message);
    assertTrue(
        message.contains("VanillaBP names a workflow and nobody else"),
        "the rule is said out loud: "
            + message);
    assertTrue(message.contains("its business key"), "where the name is kept is named: "
        + message);

    assertTrue(
        strings("introspect/timer-aggregates")
            .stream()
            .noneMatch(reported -> reported.startsWith("started-past-vanillabp|")),
        "a refused start leaves no workflow aggregate behind: "
            + strings("introspect/timer-aggregates"));

  }

  @Test
  @DisplayName("A start which brings no name is named by the application and runs to its end")
  public void aWorkflowStartedPastVanillaBpWithoutAName() throws Exception {

    final var before = strings("introspect/timer-aggregates").size();

    post("introspect/started-past-vanillabp-unnamed");

    await(
        () -> strings("introspect/timer-aggregates").size() > before,
        "the workflow started past VanillaBP to get its workflow aggregate");
    await(
        () -> strings("introspect/timer-aggregates")
            .stream()
            .filter(reported -> reported.startsWith("timer-"))
            .anyMatch(reported -> reported.contains("|recordStart|COMPLETED/")),
        "the workflow the application named to run to its end, but got: "
            + strings("introspect/timer-aggregates"));

  }

}
