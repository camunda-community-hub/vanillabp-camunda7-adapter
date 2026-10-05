package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.function.Supplier;

import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * End-to-end test of pushing a changed aggregate against a real embedded
 * Camunda 7 engine. Two things are worth an engine: a conditional event only ever
 * looks at its condition when a variable of its scope changes - so the push is what
 * makes it fire - and a task-scoped push has to land in the scope the task RUNS IN
 * (here: one iteration of a multi-instance embedded subprocess), because that is the
 * scope an event subprocess with a conditional start event listens on.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: contexts are cached and live in parallel - a foreign engine
    // (and job executor) on the same H2 database would compete for this test's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-aggregate-changed-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, Spring would keep every context until the JVM exits, and an engine outliving its
// test keeps its job executor running against a database the next classes work on
@DirtiesContext
public class Camunda7AggregateChangedIT {

  private static final String MODULE_ID = "c7-it";

  @Autowired
  private AggregateChangedTestWorkflowService workflowService;

  @Autowired
  private AggregateChangedTestRepository repository;

  @Autowired
  private MultiInstancePushTestWorkflowService multiInstanceWorkflowService;

  @Autowired
  private MultiInstancePushTestRepository multiInstanceRepository;

  @Autowired
  private RuntimeService runtimeService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  /**
   * Lets the test PARK the engine's job executor. What a push lands in is asserted by
   * reading the variables of an execution, and an interrupting event subprocess ends
   * exactly that execution as soon as its task ran - which is an async job. With the
   * executor parked, nothing moves while the test looks.
   */
  @Autowired
  private io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder engineHolder;

  @Test
  @DisplayName("a task with a scope of its own is not the scope meant - the push goes around it")
  public void aTaskScopeIsSkipped() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> multiInstanceWorkflowService.saveAggregate().getId());
    assertNotNull(aggregateId);

    // the secondary process is started against the engine: the injectable process
    // service starts the primary process of its workflow service only. The business
    // key IS the aggregate's id, which is how VanillaBP finds it again
    transactionTemplate
        .executeWithoutResult(status -> runtimeService
            .startProcessInstanceByKey("AggregateChangedBoundaryProcess", String.valueOf(aggregateId)));

    awaitUntil(
        () -> multiInstanceRepository
            .findById(aggregateId)
            .map(MultiInstancePushTestAggregate::getTaskIds)
            .isPresent(),
        "the workflow to park at the task carrying a boundary event");

    final var taskId = multiInstanceRepository.findById(aggregateId).orElseThrow().getTaskIds();

    transactionTemplate
        .executeWithoutResult(status -> multiInstanceWorkflowService.escalateAt(aggregateId, taskId));

    // The pushed VALUES are the change now - the technical marker is written
    // only for an aggregate which shares nothing at all
    final var marker = "escalate";
    // the push happens after the commit, so the scope it lands in is
    // waited for
    final var instanceId = processInstanceIdOf(aggregateId);
    AwaitPhaseTwo.until(
        () -> runtimeService.getVariablesLocal(instanceId).containsKey(marker),
        "the scope around the task is the workflow itself here");
    // the boundary event makes the engine give the activity a scope of its own, and
    // that scope is the task's context - not the scope the task RUNS in
    assertFalse(
        runtimeService.getVariablesLocal(taskId).containsKey(marker),
        "the activity's own scope may not be written at");

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
      Thread.sleep(100);
    }

  }

  /**
   * The workflow is created by the phase-two outbox after the commit, so
   * the lookup waits for it instead of reading a moment too early.
   *
   * @param aggregateId The aggregate's id (the business key)
   * @return The process instance's id
   */
  private String processInstanceIdOf(
      final Object aggregateId) {

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
   * The SCOPE execution of the iteration working on the given item. Camunda 7 keeps
   * the multi-instance element variable on the concurrent execution ABOVE that scope,
   * so the scope is found as its child.
   */
  private String executionIdOfIteration(
      final Long aggregateId,
      final String item) {

    final var executions = runtimeService
        .createExecutionQuery()
        .processInstanceId(processInstanceIdOf(aggregateId))
        .list()
        .stream()
        .map(org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity.class::cast)
        .toList();
    final var itemHolder = executions
        .stream()
        .filter(execution -> item.equals(runtimeService.getVariablesLocal(execution.getId()).get("item")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no iteration found working on '%s'".formatted(item)));
    return executions
        .stream()
        .filter(execution -> itemHolder.getId().equals(execution.getParentId()))
        .filter(org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity::isScope)
        .map(org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity::getId)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no scope execution below the iteration '%s'".formatted(item)));

  }

  @Test
  @DisplayName("a conditional event fires once the changed aggregate was pushed")
  public void aConditionalEventWaitsForThePush() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> workflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    awaitUntil(
        () -> runtimeService
            .createEventSubscriptionQuery()
            .processInstanceId(processInstanceIdOf(aggregateId))
            .eventType("conditional")
            .count() > 0,
        "the workflow to wait at the conditional event");

    // the attribute the condition reads is set, but nothing tells the engine yet
    Thread.sleep(500);
    assertEquals(
        null,
        repository.findById(aggregateId).orElseThrow().getProcessedBy(),
        "the workflow may not continue before the aggregate was pushed");

    transactionTemplate.executeWithoutResult(status -> workflowService.becomeReady(aggregateId));

    awaitUntil(
        () -> "conditionMet".equals(
            repository
                .findById(aggregateId)
                .map(AggregateChangedTestAggregate::getProcessedBy)
                .orElse(null)),
        "the task behind the conditional event to run");

  }

  @Test
  @DisplayName("a task-scoped push lands in the scope the task runs in, not in the task itself")
  public void aTaskScopedPushReachesTheEnclosingScope() throws Exception {

    final var aggregateId = startedMultiInstanceWorkflow();

    // "item=taskId" per iteration - the test has to know WHICH iteration it pushed into
    final var parked = multiInstanceRepository.findById(aggregateId).orElseThrow().getTaskIds().split(",");
    final var pushedItem = parked[0].split("=")[0];
    final var pushedTaskId = parked[0].split("=")[1];
    final var siblingItem = parked[1].split("=")[0];

    // park the executor: the conditional start event of the event subprocess fires
    // within the push's transaction and INTERRUPTS its iteration once the task behind
    // it ran - which would take the very execution this test reads
    engineHolder.stopWorkflowProcessing(MODULE_ID);

    transactionTemplate
        .executeWithoutResult(status -> multiInstanceWorkflowService.escalateAt(aggregateId, pushedTaskId));

    // the values land in the scope the task RUNS IN - the iteration of the
    // multi-instance subprocess, recognizable by its own 'item' variable
    // The pushed VALUES are the change now - the technical marker is written
    // only for an aggregate which shares nothing at all
    final var marker = "escalate";
    final var iterationExecutionId = executionIdOfIteration(aggregateId, pushedItem);
    AwaitPhaseTwo.until(
        () -> runtimeService.getVariablesLocal(iterationExecutionId).containsKey(marker),
        "the push has to land in the scope of the iteration the task runs in");
    // the pushed value is TRUE in the iteration the task runs in
    assertEquals(
        Boolean.TRUE,
        runtimeService.getVariablesLocal(iterationExecutionId).get(marker),
        "the iteration the task runs in has to carry the pushed value");
    // the workflow's global scope keeps what the START wrote (the shared values are
    // written there when the workflow begins), so the sibling iterations read the old
    // value - which is the point of writing at the scope the task runs in
    assertEquals(
        Boolean.FALSE,
        runtimeService.getVariablesLocal(processInstanceIdOf(aggregateId)).get(marker),
        "the workflow's global scope may not be changed by a task-scoped push");
    assertFalse(
        runtimeService
            .getVariablesLocal(executionIdOfIteration(aggregateId, siblingItem))
            .containsKey(marker),
        "the sibling iteration's scope stays as it was");

    // deliberately NOT asserted: that the sibling iteration stays untouched afterwards.
    // Camunda 7 evaluates the conditional events of a scope whenever a variable of a
    // PARENT scope changes, and the first iteration ending updates the counters of the
    // multi-instance body - which is a parent of the sibling. Where the values land is
    // what this adapter decides; which conditions the engine then re-evaluates is the
    // engine's business.

    // let the engine run again: the escalation was recognized, the rest is jobs
    engineHolder.startWorkflowProcessing(MODULE_ID);

    // the payoff: the event subprocess of that iteration has a conditional start
    // event, and the write in its scope is what makes the engine look at it
    awaitUntil(
        () -> {
          final var escalated = multiInstanceRepository
              .findById(aggregateId)
              .map(MultiInstancePushTestAggregate::getEscalatedItems)
              .orElse("");
          return escalated.contains(pushedItem);
        },
        "the event subprocess of the iteration '%s' to run".formatted(pushedItem));

  }

  @Test
  @DisplayName("a global push lands at the workflow's scope")
  public void aGlobalPushReachesTheWorkflowScope() throws Exception {

    // an own workflow: the escalation of the test above interrupts an iteration and
    // may take the whole instance with it, and a push needs a workflow which is there
    final var aggregateId = startedMultiInstanceWorkflow();

    transactionTemplate.executeWithoutResult(status -> multiInstanceWorkflowService.pushGlobally(aggregateId));

    final var instanceId = processInstanceIdOf(aggregateId);
    AwaitPhaseTwo.until(
        () -> runtimeService
            .getVariablesLocal(instanceId)
            .containsKey("escalate"),
        "the global push has to land at the workflow's scope");

  }

  /**
   * Starts the multi-instance workflow and waits until both iterations park at their
   * task, which is where a push can be observed.
   *
   * @return The aggregate's id
   */
  private Long startedMultiInstanceWorkflow() throws Exception {

    final var aggregateId = transactionTemplate
        .execute(status -> multiInstanceWorkflowService.startWorkflow().getId());
    assertNotNull(aggregateId);

    awaitUntil(
        () -> multiInstanceRepository
            .findById(aggregateId)
            .map(MultiInstancePushTestAggregate::getTaskIds)
            .filter(taskIds -> taskIds.split(",").length == 2)
            .isPresent(),
        "both iterations of the multi-instance subprocess to park");

    return aggregateId;

  }

}
