package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process whose workflow aggregate has a numeric id. The
 * persistence layer assigns that id while saving, so a business key somebody chose can
 * never be one of them.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ForeignNumericIdAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ForeignNumericIdProcess"))
public class ForeignNumericIdWorkflowService {

  /**
   * Names a workflow the engine started, by leaving the name to the persistence layer.
   *
   * @return The workflow aggregate of the started workflow
   */
  @WorkflowStartedByBpms
  public ForeignNumericIdAggregate nameTheStartedWorkflow() {

    return new ForeignNumericIdAggregate();

  }

  @WorkflowTask(taskDefinition = "recordForeignNumericIdStart")
  public void recordForeignNumericIdStart(
      final ForeignNumericIdAggregate aggregate) {

    aggregate.setProcessedBy("recordForeignNumericIdStart");

  }

}
