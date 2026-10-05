package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Starts <code>ExprPlacements</code>, which runs a chosen subset through the other sites a Camunda 7 model puts an expression into. The process has no
 * workflow task of its own: everything it does is decide, wait or iterate, so nothing
 * has to be wired and a failing expression is the only thing that can go wrong.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ExprPlacementsAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ExprPlacements"))
public class ExprPlacementsWorkflowService {

  private final ProcessService<ExprPlacementsAggregate> processService;

  public ExprPlacementsWorkflowService(
      final ProcessService<ExprPlacementsAggregate> processService) {

    this.processService = processService;

  }

  public ExprPlacementsAggregate start(
      final ExprPlacementsAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

}
