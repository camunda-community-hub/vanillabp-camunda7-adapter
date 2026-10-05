package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * A workflow whose only path leads into a terminate end event. It exists to record what
 * this engine calls that end, since a modeller reading the model would call it a
 * cancelation.
 */
@Service
@WorkflowService(
    workflowAggregateClass = EndKindTerminateAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "EndKindTerminateProcess"))
public class EndKindTerminateWorkflowService {

  private final ProcessService<EndKindTerminateAggregate> processService;

  public EndKindTerminateWorkflowService(
      final ProcessService<EndKindTerminateAggregate> processService) {

    this.processService = processService;

  }

  public EndKindTerminateAggregate startWorkflow() {

    return processService.startWorkflow(new EndKindTerminateAggregate());

  }

  @WorkflowTask(taskDefinition = "runBeforeTerminating")
  public void runBeforeTerminating(
      final EndKindTerminateAggregate aggregate) {

    // the task is asynchronous, so the workflow reaches its terminate end event in a
    // transaction of the engine rather than in the one which started it - which is where
    // an application meets this end in production

  }

  @WorkflowEnded
  public void workflowEnded(
      final EndKindTerminateAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setEndedAs("%s/%s".formatted(end.kind(), end.endEventId()));

  }

}
