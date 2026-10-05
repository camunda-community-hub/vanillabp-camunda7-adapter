package io.vanillabp.camunda7.it;

import java.util.UUID;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the condition-started process of the foreign-start integration
 * test. A condition which became true starts a workflow the application has to name.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ForeignConditionAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ForeignConditionProcess"))
public class ForeignConditionWorkflowService {

  /**
   * Names a workflow the engine started because a condition became true.
   *
   * @return The workflow aggregate of the started workflow
   */
  @WorkflowStartedByBpms
  public ForeignConditionAggregate nameTheStartedWorkflow() {

    final var aggregate = new ForeignConditionAggregate();
    aggregate.setId("condition-"
        + UUID.randomUUID());
    return aggregate;

  }

  @WorkflowTask(taskDefinition = "recordForeignConditionStart")
  public void recordForeignConditionStart(
      final ForeignConditionAggregate aggregate) {

    aggregate.setProcessedBy("recordForeignConditionStart");

  }

}
