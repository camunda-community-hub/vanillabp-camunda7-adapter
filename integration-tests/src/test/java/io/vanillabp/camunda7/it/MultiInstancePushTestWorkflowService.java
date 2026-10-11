package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the multi-instance half of the aggregateChanged
 * integration test.
 */
@Service
@WorkflowService(
    workflowAggregateClass = MultiInstancePushTestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "AggregateChangedMultiInstanceProcess"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "AggregateChangedBoundaryProcess"))
public class MultiInstancePushTestWorkflowService {

  private final ProcessService<MultiInstancePushTestAggregate> processService;

  private final MultiInstancePushTestRepository repository;

  public MultiInstancePushTestWorkflowService(
      final ProcessService<MultiInstancePushTestAggregate> processService,
      final MultiInstancePushTestRepository repository) {

    this.processService = processService;
    this.repository = repository;

  }

  public MultiInstancePushTestAggregate startWorkflow() {

    return processService.startWorkflow(new MultiInstancePushTestAggregate());

  }

  /**
   * Sets what the conditional start event waits for. Then it pushes the aggregate into the
   * scope which the given task runs in.
   *
   * @param aggregateId The aggregate's id
   * @param taskId The parked execution of that iteration's task
   */
  public void escalateAt(
      final Long aggregateId,
      final String taskId) {

    final var aggregate = repository.findById(aggregateId).orElseThrow();
    aggregate.setEscalate(true);
    processService.aggregateChanged(aggregate, taskId);

  }

  /**
   * Pushes the aggregate at the global scope of the workflow.
   *
   * @param aggregateId The aggregate's id
   */
  public void pushGlobally(
      final Long aggregateId) {

    processService.aggregateChanged(repository.findById(aggregateId).orElseThrow());

  }

  /**
   * Saves an aggregate without starting a workflow. The test starts the boundary-event
   * process against the engine, because the process service starts the primary process
   * only.
   *
   * @return The saved aggregate
   */
  public MultiInstancePushTestAggregate saveAggregate() {

    return repository.save(new MultiInstancePushTestAggregate());

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
      final MultiInstancePushTestAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setTaskIds(taskId);

  }

  @WorkflowTask
  public void multiInstanceTask(
      final MultiInstancePushTestAggregate aggregate,
      @TaskId final String taskId,
      @MultiInstanceElement("MI_Sub") final String item) {

    // parks this iteration. The test pushes into its scope afterwards.
    final var entry = item
        + "="
        + taskId;
    aggregate
        .setTaskIds(
            aggregate.getTaskIds() == null
                ? entry
                : aggregate.getTaskIds()
                    + ","
                    + entry);

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
      final MultiInstancePushTestAggregate aggregate,
      @MultiInstanceElement("MI_Sub") final String item) {

    aggregate
        .setEscalatedItems(
            aggregate.getEscalatedItems() == null
                ? item
                : aggregate.getEscalatedItems()
                    + ","
                    + item);

  }

}
