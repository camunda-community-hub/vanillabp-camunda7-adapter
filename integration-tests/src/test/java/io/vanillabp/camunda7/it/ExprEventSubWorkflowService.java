package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Starts <code>ExprEventSub</code>, which carries the conditional start event of an event subprocess, which is measured apart because its condition is evaluated on every variable change of the scope. The process has no
 * workflow task of its own: everything it does is decide, wait or iterate, so nothing
 * has to be wired and a failing expression is the only thing that can go wrong.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ExprEventSubAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ExprEventSub"))
public class ExprEventSubWorkflowService {

  private final ProcessService<ExprEventSubAggregate> processService;

  public ExprEventSubWorkflowService(
      final ProcessService<ExprEventSubAggregate> processService) {

    this.processService = processService;

  }

  public ExprEventSubAggregate start(
      final ExprEventSubAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

}
