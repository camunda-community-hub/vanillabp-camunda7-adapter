package io.vanillabp.camunda7.quarkus.workflowended;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * A workflow which runs one task and ends. The <code>&#64;WorkflowEnded</code> method
 * is the point of the test: on Quarkus it was never called, because the adapter did
 * not hand the core's invoker to the engine.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = EndedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "EndedProcess"))
public class EndedWorkflowService {

  @Inject
  ProcessService<EndedAggregate> processService;

  public EndedAggregate startWorkflow(
      final String content) {

    final var aggregate = new EndedAggregate();
    aggregate.setContent(content);
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void endedTask(
      final EndedAggregate aggregate) {

    aggregate.setContent("task-done");

  }

  @WorkflowEnded
  public void workflowEnded(
      final EndedAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setEndedWith("ended:%s/%s".formatted(end.kind(), end.endEventId()));

  }

}
