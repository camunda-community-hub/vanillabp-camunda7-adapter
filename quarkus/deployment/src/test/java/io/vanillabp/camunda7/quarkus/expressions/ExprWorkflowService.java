package io.vanillabp.camunda7.quarkus.expressions;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Owns {@code ExprCheckProcess}. It has no {@code @WorkflowTask} method, because the model
 * has no task: what is measured here happens while the model is deployed.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = ExprAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ExprCheckProcess"))
public class ExprWorkflowService {

  @Inject
  ProcessService<ExprAggregate> processService;

  public ExprAggregate startWorkflow() {

    return processService.startWorkflow(new ExprAggregate());

  }

}
