package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;

/**
 * A workflow which waits, until an interrupting event subprocess takes the waiting token
 * away and ends the instance. It exists to record what this engine calls that end.
 */
@Service
@WorkflowService(
    workflowAggregateClass = EndKindEventSubprocessAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "EndKindEventSubprocessProcess"))
public class EndKindEventSubprocessWorkflowService {

  private final ProcessService<EndKindEventSubprocessAggregate> processService;

  public EndKindEventSubprocessWorkflowService(
      final ProcessService<EndKindEventSubprocessAggregate> processService) {

    this.processService = processService;

  }

  public EndKindEventSubprocessAggregate startWorkflow() {

    return processService.startWorkflow(new EndKindEventSubprocessAggregate());

  }

  @WorkflowEnded
  public void workflowEnded(
      final EndKindEventSubprocessAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setEndedAs("%s/%s".formatted(end.kind(), end.endEventId()));

  }

}
