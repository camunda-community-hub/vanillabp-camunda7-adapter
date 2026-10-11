package io.vanillabp.camunda7.quarkus.test;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service for pushing a changed aggregate. Two things here are worth a real
 * engine. First, a conditional event only looks at its condition when a variable of its
 * scope changes. So the push is what makes it fire. Second, a push for one task has to
 * land in the scope the task RUNS IN. That is the scope an event subprocess with a
 * conditional start event listens on.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = C7PushAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "AggregateChangedProcess"),
    secondaryBpmnProcesses = {
        @BpmnProcess(bpmnProcessId = "AggregateChangedMultiInstanceProcess"), @BpmnProcess(
            bpmnProcessId = "AggregateChangedBoundaryProcess")
    })
public class C7PushWorkflowService {

  @Inject
  ProcessService<C7PushAggregate> processService;

  public C7PushAggregate startWorkflow(
      final C7PushAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  public C7PushAggregate aggregateChanged(
      final C7PushAggregate aggregate) {

    return processService.aggregateChanged(aggregate);

  }

  public C7PushAggregate aggregateChanged(
      final C7PushAggregate aggregate,
      final String taskId) {

    return processService.aggregateChanged(aggregate, taskId);

  }

  @WorkflowTask
  public void conditionMet(
      final C7PushAggregate aggregate) {

    aggregate.setProcessedBy("conditionMet");

  }

  /**
   * The task of the boundary-event process. It has a boundary timer, so the engine gives
   * the activity a scope of its own. A push must NOT write into that scope.
   *
   * @param aggregate The workflow aggregate
   * @param taskId The parked execution
   */
  @WorkflowTask
  public void boundaryTask(
      final C7PushAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setTaskIds(taskId);

  }

  @WorkflowTask
  public void multiInstanceTask(
      final C7PushAggregate aggregate,
      @TaskId final String taskId,
      @MultiInstanceElement("MI_Sub") final String item) {

    // parks this iteration. The test pushes into its scope afterwards
    aggregate.appendTaskId(item
        + "="
        + taskId);

  }

  /**
   * The task behind the conditional start event of the event subprocess. It runs only in
   * the iteration whose scope received the push.
   *
   * @param aggregate The workflow aggregate
   * @param item The iteration's element
   */
  @WorkflowTask
  public void escalated(
      final C7PushAggregate aggregate,
      @MultiInstanceElement("MI_Sub") final String item) {

    aggregate.appendEscalatedItem(item);

  }

}
