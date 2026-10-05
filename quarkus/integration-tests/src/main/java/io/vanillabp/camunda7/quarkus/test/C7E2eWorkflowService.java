package io.vanillabp.camunda7.quarkus.test;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the Quarkus end-to-end application: one
 * <code>&#64;WorkflowTask</code> method per outcome and per binding variation, serving
 * the same BPMN models the Spring Boot integration tests use.
 * <p>
 * That both platforms run the identical set of documented features is deliberate. The
 * adapter's platform-neutral core being correct says nothing about a platform's glue
 * ever calling it, and coverage is measured per platform for exactly that reason.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = C7E2eAggregate.class,
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
                                        bpmnProcessId = "SignalCatchProcess"), @BpmnProcess(
                                            bpmnProcessId = "VersionedProcess")
    })
public class C7E2eWorkflowService {

  @Inject
  ProcessService<C7E2eAggregate> processService;

  @Inject
  C7NestedTransactionalBean nestedTransactionalBean;

  // --- what the application asks of VanillaBP ---

  public C7E2eAggregate startWorkflow(
      final C7E2eAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  public C7E2eAggregate completeTask(
      final C7E2eAggregate aggregate,
      final String taskId) {

    return processService.completeTask(aggregate, taskId);

  }

  public C7E2eAggregate cancelTask(
      final C7E2eAggregate aggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return processService.cancelTask(aggregate, taskId, bpmnErrorCode);

  }

  public C7E2eAggregate completeUserTask(
      final C7E2eAggregate aggregate,
      final String taskId) {

    return processService.completeUserTask(aggregate, taskId);

  }

  public C7E2eAggregate cancelUserTask(
      final C7E2eAggregate aggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return processService.cancelUserTask(aggregate, taskId, bpmnErrorCode);

  }

  public C7E2eAggregate correlateMessage(
      final C7E2eAggregate aggregate,
      final String messageName) {

    return processService.correlateMessage(aggregate, messageName);

  }

  public C7E2eAggregate correlateMessage(
      final C7E2eAggregate aggregate,
      final String messageName,
      final String correlationId) {

    return processService.correlateMessage(aggregate, messageName, correlationId);

  }

  public C7E2eAggregate startWorkflowByMessage(
      final C7E2eAggregate aggregate,
      final String messageName) {

    return processService.startWorkflowByMessage(aggregate, messageName);

  }

  public void sendSignal(
      final String signalName) {

    processService.sendSignal(signalName);

  }

  public String getWorkflowModuleId() {

    return processService.getWorkflowModuleId();

  }

  // --- what the engine asks of the application ---

  @WorkflowTask
  public void happyTask(
      final C7E2eAggregate aggregate) {

    aggregate.appendResult("happy");

  }

  @WorkflowTask
  public void afterApproval(
      final C7E2eAggregate aggregate) {

    aggregate.appendResult("approved");

  }

  @WorkflowTask
  public void errorTask(
      final C7E2eAggregate aggregate) {

    // the mutation has to be COMMITTED although the handler throws - a
    // TaskException is a BPMN error, not a rollback
    aggregate.appendResult("error-raised");
    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

  @WorkflowTask
  public void errorHandled(
      final C7E2eAggregate aggregate) {

    aggregate.appendResult("handled");

  }

  @WorkflowTask
  public void alwaysFails(
      final C7E2eAggregate aggregate) {

    // must NEVER become visible: a technical exception rolls back the job
    // transaction including this mutation
    aggregate.appendResult("must-never-be-visible");
    throw new IllegalStateException("boom-quarkus-e2e");

  }

  @WorkflowTask
  public void asyncTask(
      final C7E2eAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setTaskId(taskId);
    aggregate.appendResult("async-open");

  }

  @WorkflowTask
  public void awaitCancel(
      final C7E2eAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setTaskId(taskId);
    aggregate.appendResult("await-cancel");

  }

  @WorkflowTask
  public void cancelHandled(
      final C7E2eAggregate aggregate) {

    aggregate.appendResult("cancel-handled");

  }

  @WorkflowTask
  public void awaitCancelEvent(
      final C7E2eAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final TaskEvent.Event event) {

    // subscribed to ALL events (the default): CREATED parks the task, CANCELED is
    // delivered when the activity is canceled
    if (event == TaskEvent.Event.CREATED) {
      aggregate.setTaskId(taskId);
      aggregate.appendResult("event-created");
    } else {
      aggregate.appendResult("event-"
          + event.name().toLowerCase());
    }

  }

  @WorkflowTask
  public void mixedSend(
      final C7E2eAggregate aggregate) {

    aggregate.appendResult("mixed-send");

  }

  @WorkflowTask
  public void mixedRule(
      final C7E2eAggregate aggregate) {

    aggregate.appendResult("mixed-rule");

  }

  @WorkflowTask(taskDefinition = "approveRequest")
  public void approveRequestNotification(
      final C7E2eAggregate aggregate,
      @TaskId final String taskId,
      @TaskEvent final TaskEvent.Event event) {

    if (event == TaskEvent.Event.CREATED) {
      aggregate.setTaskId(taskId);
      aggregate.appendResult("usertask-created");
    } else {
      aggregate.appendResult("usertask-"
          + event.name().toLowerCase());
    }

  }

  @WorkflowTask
  public void userTaskCancelHandled(
      final C7E2eAggregate aggregate) {

    aggregate.appendResult("usertask-cancel-handled");

  }

  @WorkflowTask
  public void messageArrived(
      final C7E2eAggregate aggregate) {

    aggregate.appendResult("message-arrived");

  }

  @WorkflowTask
  public void orderPlaced(
      final C7E2eAggregate aggregate) {

    aggregate.appendResult("order-placed");

  }

  @WorkflowTask
  public void recordSignal(
      final C7E2eAggregate aggregate) {

    aggregate.appendResult("signal-received");

  }

  /**
   * Calls a bean carrying a transaction annotation of the application. The handler
   * itself has none, so the startup check cannot see it and the runtime check has to
   * catch the rollback-only transaction.
   *
   * @param aggregate The workflow aggregate
   */
  @WorkflowTask
  public void nestedTransaction(
      final C7E2eAggregate aggregate) {

    aggregate.appendResult("about-to-fail");
    nestedTransactionalBean.raiseTaskException();

  }

  @WorkflowTask
  public void miTask(
      final C7E2eAggregate aggregate,
      @MultiInstanceIndex("MI_Task") final int index,
      @MultiInstanceTotal("MI_Task") final int total,
      @MultiInstanceElement("MI_Task") final Object element) {

    aggregate.appendResult("%s%d/%d".formatted(element, index, total));

  }

  /**
   * Which method serves the task is decided by the version of the deployed
   * process definition - this one by its number.
   *
   * @param aggregate The workflow aggregate
   */
  @WorkflowTask(taskDefinition = "versionedTask", version = "1")
  public void firstVersion(
      final C7E2eAggregate aggregate) {

    aggregate.appendResult("firstVersion");

  }

  /**
   * This one by the <code>camunda:versionTag</code> of the second version,
   * which is deployed while the application runs - the way another node of a rolling
   * deployment does it.
   *
   * @param aggregate The workflow aggregate
   */
  @WorkflowTask(taskDefinition = "versionedTask", version = "release-2")
  public void taggedVersion(
      final C7E2eAggregate aggregate) {

    aggregate.appendResult("taggedVersion");

  }

}
