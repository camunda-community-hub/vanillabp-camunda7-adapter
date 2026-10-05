package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Starts <code>ExprConditions</code>, which puts one expression per outcome class on an
 * exclusive gateway, each on a parallel branch of its own. The process has no
 * workflow task of its own: everything it does is decide, wait or iterate, so nothing
 * has to be wired and a failing expression is the only thing that can go wrong.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ExprConditionsAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ExprConditions"))
public class ExprConditionsWorkflowService {

  private final ProcessService<ExprConditionsAggregate> processService;

  public ExprConditionsWorkflowService(
      final ProcessService<ExprConditionsAggregate> processService) {

    this.processService = processService;

  }

  public ExprConditionsAggregate start(
      final ExprConditionsAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

}
