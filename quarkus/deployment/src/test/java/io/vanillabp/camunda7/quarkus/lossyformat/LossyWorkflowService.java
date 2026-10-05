package io.vanillabp.camunda7.quarkus.lossyformat;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Owns {@code LossyFormat}. What is measured happens while its model is deployed, so the
 * service has nothing but the binding.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = LossyAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "LossyFormat"))
public class LossyWorkflowService {

  @Inject
  ProcessService<LossyAggregate> processService;

  public LossyAggregate startWorkflow() {

    return processService.startWorkflow(new LossyAggregate());

  }

}
