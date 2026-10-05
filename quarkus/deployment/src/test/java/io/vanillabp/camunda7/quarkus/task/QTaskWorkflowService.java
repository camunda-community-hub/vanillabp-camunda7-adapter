package io.vanillabp.camunda7.quarkus.task;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Workflow service of the Quarkus task-processing test: the three
 * outcomes on a real embedded engine with JTA transactions.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = QTaskAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "QTaskProcess"),
    secondaryBpmnProcesses = {
        @BpmnProcess(bpmnProcessId = "QFailProcess"), @BpmnProcess(bpmnProcessId = "QAsyncProcess")
    })
public class QTaskWorkflowService {

  @Inject
  ProcessService<QTaskAggregate> processService;

  public QTaskAggregate startWorkflow() {

    return processService.startWorkflow(new QTaskAggregate());

  }

  @WorkflowTask
  public void qHappy(
      final QTaskAggregate aggregate) {

    aggregate.appendResult("happy");

  }

  @WorkflowTask
  public void qError(
      final QTaskAggregate aggregate) {

    // the mutation has to be COMMITTED although the handler throws - the V1
    // TaskException contract (BPMN error, no rollback)
    aggregate.appendResult("error-raised");
    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

  @WorkflowTask
  public void qHandled(
      final QTaskAggregate aggregate) {

    aggregate.appendResult("handled");

  }

  public QTaskAggregate completeAsyncTask(
      final QTaskAggregate aggregate,
      final String taskId) {

    return processService.completeTask(aggregate, taskId);

  }

  @WorkflowTask
  public void qAsync(
      final QTaskAggregate aggregate,
      @io.vanillabp.spi.service.TaskId final String taskId) {

    aggregate.setTaskId(taskId);
    aggregate.appendResult("async-open");

  }

  @WorkflowTask
  public void qFails(
      final QTaskAggregate aggregate) {

    // must NEVER become visible: a technical exception rolls back the job's JTA
    // transaction including this mutation
    aggregate.appendResult("must-never-be-visible");
    throw new IllegalStateException("boom-q21b");

  }

}
