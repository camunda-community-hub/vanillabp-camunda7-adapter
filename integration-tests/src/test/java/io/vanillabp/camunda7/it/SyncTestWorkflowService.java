package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the aggregate-sync integration test: the
 * workflow parks at an asynchronous task so the test can inspect the process
 * variables Camunda 7 actually holds.
 */
@Service
@WorkflowService(
    workflowAggregateClass = SyncTestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "SyncProcess"))
public class SyncTestWorkflowService {

  private final ProcessService<SyncTestAggregate> processService;

  public SyncTestWorkflowService(
      final ProcessService<SyncTestAggregate> processService) {

    this.processService = processService;

  }

  public SyncTestAggregate startSyncProcess(
      final SyncTestAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  public SyncTestAggregate completeAwaitTask(
      final SyncTestAggregate aggregate,
      final String taskId) {

    return processService.completeTask(aggregate, taskId);

  }

  @WorkflowTask
  public void syncAwait(
      final SyncTestAggregate aggregate,
      @TaskId final String taskId) {

    // parks the workflow: the instance (and its variables) stay in the engine
    aggregate.setTaskId(taskId);

  }

}
