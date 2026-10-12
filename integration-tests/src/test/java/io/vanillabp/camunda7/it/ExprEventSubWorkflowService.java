package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Starts <code>ExprEventSub</code>. The process carries the conditional start event of an
 * event subprocess. It is measured on its own, because the engine evaluates that condition
 * on every change of a variable in its scope. The process has no workflow task of its own.
 * All it does is decide, wait or iterate. So nothing has to be wired, and a failing
 * expression is the only thing that can go wrong.
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
