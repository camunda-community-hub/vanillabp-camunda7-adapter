package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the aggregate-sync integration test. The workflow parks at an
 * asynchronous task. So the test can inspect the process variables which Camunda 7 really
 * holds.
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

    // parks the workflow, so the instance and its variables stay in the engine
    aggregate.setTaskId(taskId);

  }

}
