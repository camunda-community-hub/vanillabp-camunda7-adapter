package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the task-processing integration tests: one
 * {@code @WorkflowTask} method per outcome/binding variation, serving FIVE BPMN
 * processes of one aggregate ({@code secondaryBpmnProcesses} - exercised for real
 * on Camunda 7).
 */
@Service
@WorkflowService(
    workflowAggregateClass = TaskTestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TaskProcess"),
    secondaryBpmnProcesses = {
        @BpmnProcess(bpmnProcessId = "ErrorProcess"), @BpmnProcess(bpmnProcessId = "FailProcess"), @BpmnProcess(
            bpmnProcessId = "AsyncProcess"), @BpmnProcess(bpmnProcessId = "MultiInstanceProcess"), @BpmnProcess(
                bpmnProcessId = "AsyncCancelProcess"), @BpmnProcess(bpmnProcessId = "CancelEventProcess"), @BpmnProcess(
                    bpmnProcessId = "MixedProcess"), @BpmnProcess(bpmnProcessId = "UserTaskProcess"), @BpmnProcess(
                        bpmnProcessId = "SilentUserTaskProcess"), @BpmnProcess(
                            bpmnProcessId = "MessageProcess"), @BpmnProcess(
                                bpmnProcessId = "MessageStartProcess"), @BpmnProcess(
                                    bpmnProcessId = "RollbackOnlyProcess"), @BpmnProcess(
                                        bpmnProcessId = "BusinessRuleProcess")
    })
public class TaskTestWorkflowService {

  private final ProcessService<TaskTestAggregate> processService;

  private final NestedTransactionalBean nestedTransactionalBean;

  public TaskTestWorkflowService(
      final ProcessService<TaskTestAggregate> processService,
      final NestedTransactionalBean nestedTransactionalBean) {

    this.processService = processService;
    this.nestedTransactionalBean = nestedTransactionalBean;

  }

  public TaskTestAggregate startTaskProcess(
      final TaskTestAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  /**
   * What a decision table produced reaches the application like any other process
   * variable: the engine wrote the result of the business rule task into the workflow's
   * scope, the task's input mapping brings it into the task's own scope, and the
   * parameter names it. Nothing about DMN is visible here, which is the
   * point - VanillaBP deploys the decision, the engine evaluates it.
   */
  @WorkflowTask(taskDefinition = "readTheRating")
  public void readTheRating(
      final TaskTestAggregate aggregate,
      @TaskParam("rating") final String rating) {

    aggregate.appendResult("rating="
        + rating);

  }

  @WorkflowTask
  public void happyTask(
      final TaskTestAggregate aggregate) {

    aggregate.appendResult("happy");

  }

  @WorkflowTask
  public void afterApproval(
      final TaskTestAggregate aggregate) {

    aggregate.appendResult("approved");

  }

  @WorkflowTask
  public void errorTask(
      final TaskTestAggregate aggregate) {

    // the mutation has to be COMMITTED although the handler throws - the V1
    // TaskException contract (BPMN error, no rollback)
    aggregate.appendResult("error-raised");
    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

  @WorkflowTask
  public void errorHandled(
      final TaskTestAggregate aggregate) {

    aggregate.appendResult("handled");

  }

  @WorkflowTask
  public void alwaysFails(
      final TaskTestAggregate aggregate) {

    // must NEVER become visible: a technical exception rolls back the job
    // transaction including this mutation
    aggregate.appendResult("must-never-be-visible");
    throw new IllegalStateException("boom-21b");

  }

  @WorkflowTask
  public void asyncTask(
      final TaskTestAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setTaskId(taskId);
    aggregate.appendResult("async-open");

  }

  public TaskTestAggregate completeAsyncTask(
      final TaskTestAggregate aggregate,
      final String taskId) {

    return processService.completeTask(aggregate, taskId);

  }

  public TaskTestAggregate cancelAsyncTask(
      final TaskTestAggregate aggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return processService.cancelTask(aggregate, taskId, bpmnErrorCode);

  }

  @WorkflowTask
  public void awaitCancel(
      final TaskTestAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setTaskId(taskId);
    aggregate.appendResult("await-cancel");

  }

  @WorkflowTask
  public void cancelHandled(
      final TaskTestAggregate aggregate) {

    aggregate.appendResult("cancel-handled");

  }

  @WorkflowTask
  public void awaitCancelEvent(
      final TaskTestAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final io.vanillabp.spi.service.TaskEvent.Event event) {

    // subscribed to ALL events (default): CREATED parks the task, CANCELED is
    // delivered when the activity is canceled
    if (event == io.vanillabp.spi.service.TaskEvent.Event.CREATED) {
      aggregate.setTaskId(taskId);
      aggregate.appendResult("event-created");
    } else {
      aggregate.appendResult("event-"
          + event.name().toLowerCase());
    }

  }

  @WorkflowTask
  public void mixedSend(
      final TaskTestAggregate aggregate) {

    aggregate.appendResult("mixed-send");

  }

  @WorkflowTask
  public void mixedRule(
      final TaskTestAggregate aggregate) {

    aggregate.appendResult("mixed-rule");

  }

  public TaskTestAggregate completeUserTask(
      final TaskTestAggregate aggregate,
      final String taskId) {

    return processService.completeUserTask(aggregate, taskId);

  }

  public TaskTestAggregate cancelUserTask(
      final TaskTestAggregate aggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return processService.cancelUserTask(aggregate, taskId, bpmnErrorCode);

  }

  @WorkflowTask(taskDefinition = "approveRequest")
  public void approveRequestNotification(
      final TaskTestAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final io.vanillabp.spi.service.TaskEvent.Event event) {

    // user-task lifecycle notification: CREATED when the task shows
    // up, CANCELED when its activity is canceled
    if (event == io.vanillabp.spi.service.TaskEvent.Event.CREATED) {
      aggregate.setTaskId(taskId);
      aggregate.appendResult("usertask-created");
    } else {
      aggregate.appendResult("usertask-"
          + event.name().toLowerCase());
    }

  }

  @WorkflowTask
  public void userTaskCancelHandled(
      final TaskTestAggregate aggregate) {

    aggregate.appendResult("usertask-cancel-handled");

  }

  public TaskTestAggregate correlate(
      final TaskTestAggregate aggregate,
      final String messageName) {

    return processService.correlateMessage(aggregate, messageName);

  }

  public TaskTestAggregate correlate(
      final TaskTestAggregate aggregate,
      final String messageName,
      final String correlationId) {

    return processService.correlateMessage(aggregate, messageName, correlationId);

  }

  public TaskTestAggregate startByMessage(
      final TaskTestAggregate aggregate,
      final String messageName) {

    return processService.startWorkflowByMessage(aggregate, messageName);

  }

  @WorkflowTask
  public void messageArrived(
      final TaskTestAggregate aggregate) {

    aggregate.appendResult("message-arrived");

  }

  @WorkflowTask
  public void orderPlaced(
      final TaskTestAggregate aggregate) {

    aggregate.appendResult("order-placed");

  }

  /**
   * Calls a bean carrying a transaction annotation of the application. The handler
   * itself has none, so the startup check cannot see it and the runtime check has to
   * catch the rollback-only transaction.
   */
  @WorkflowTask
  public void nestedTransaction(
      final TaskTestAggregate aggregate) {

    aggregate.appendResult("about-to-fail");
    nestedTransactionalBean.raiseTaskException();

  }

  /**
   * What the core reported as the running activation while a handler ran, in the order
   * the handlers ran. Read by the test which checks that the adapter names every
   * activation of an element differently - the value never reaches application code
   * through the SPI, so this is where an IT can see it.
   */
  public static final java.util.List<String> ACTIVATIONS = new java.util.concurrent.CopyOnWriteArrayList<>();

  @WorkflowTask
  public void miTask(
      final TaskTestAggregate aggregate,
      @MultiInstanceIndex("MI_Task") final int index,
      @MultiInstanceTotal("MI_Task") final int total,
      @MultiInstanceElement("MI_Task") final Object element) {

    aggregate.appendResult("%s%d/%d".formatted(element, index, total));
    ACTIVATIONS.add(io.vanillabp.integration.spi.RunningActivation.current());

  }

}
