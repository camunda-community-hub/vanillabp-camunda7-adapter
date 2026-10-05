package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The first task of this workflow computes the decision, and the gateway right
 * behind it branches on that value - which means the value has to have reached the engine
 * before the gateway is evaluated.
 */
@Service
@WorkflowService(
    workflowAggregateClass = DecisionTestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "SyncDecisionProcess"))
public class DecisionTestWorkflowService {

  private final ProcessService<DecisionTestAggregate> processService;

  public DecisionTestWorkflowService(
      final ProcessService<DecisionTestAggregate> processService) {

    this.processService = processService;

  }

  public DecisionTestAggregate startDecisionProcess(
      final DecisionTestAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void decideTask(
      final DecisionTestAggregate aggregate) {

    aggregate.setDecided(true);

  }

  @WorkflowTask
  public void decisionRejected(
      final DecisionTestAggregate aggregate) {

    aggregate.setDecisionResult("rejected");

  }

  @WorkflowTask
  public void decisionAwait(
      final DecisionTestAggregate aggregate,
      @TaskId final String taskId) {

    // parks the workflow so the test can inspect the variables the engine holds
    aggregate.setDecisionResult("decided");
    aggregate.setTaskId(taskId);

  }

}
