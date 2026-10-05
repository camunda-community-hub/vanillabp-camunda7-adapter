package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Owns <code>LossyFormat</code>, whose gateway reads the decimal of
 * {@link LossyFormatAggregate} at both levels.
 * <p>
 * A bean only under the profile of its own test, like every other scenario of this
 * module: all tests here boot the same application, and a workflow service whose model is
 * not deployed would report a process nobody serves in every one of them.
 */
@Service
@Profile("lossy-format")
@WorkflowService(
    workflowAggregateClass = LossyFormatAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "LossyFormat"))
public class LossyFormatWorkflowService {

  private final ProcessService<LossyFormatAggregate> processService;

  public LossyFormatWorkflowService(
      final ProcessService<LossyFormatAggregate> processService) {

    this.processService = processService;

  }

  public LossyFormatAggregate start(
      final LossyFormatAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

}
