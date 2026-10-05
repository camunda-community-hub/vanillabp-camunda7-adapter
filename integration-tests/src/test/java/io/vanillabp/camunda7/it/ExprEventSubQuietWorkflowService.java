package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Starts <code>ExprEventSubQuiet</code>, which carries the conditional start event of an event subprocess, which is measured apart because its condition is evaluated on every variable change of the scope. The process has no
 * workflow task of its own: everything it does is decide, wait or iterate, so nothing
 * has to be wired and a failing expression is the only thing that can go wrong.
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
