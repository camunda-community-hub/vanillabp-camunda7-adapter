package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Starts <code>ExprEventSubQuiet</code>. The process carries the conditional start event of
 * an event subprocess. It is measured on its own, because the engine evaluates that
 * condition on every change of a variable in its scope. The process has no workflow task of
 * its own. All it does is decide, wait or iterate. So nothing has to be wired, and a
 * failing expression is the only thing that can go wrong.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ExprEventSubQuietAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ExprEventSubQuiet"))
public class ExprEventSubQuietWorkflowService {

  private final ProcessService<ExprEventSubQuietAggregate> processService;

  public ExprEventSubQuietWorkflowService(
      final ProcessService<ExprEventSubQuietAggregate> processService) {

    this.processService = processService;

  }

  public ExprEventSubQuietAggregate start(
      final ExprEventSubQuietAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

}
