package io.vanillabp.camunda7.it;

import java.util.UUID;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the signal-started process of the foreign-start integration
 * test. Two workflows started by one broadcast are two workflows, so each of them gets a
 * name of its own.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ForeignSignalAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ForeignSignalProcess"))
public class ForeignSignalWorkflowService {

  /**
   * Names a workflow a broadcast signal started.
   *
   * @param trigger Which signal fired
   * @return The workflow aggregate of the started workflow
   */
  @WorkflowStartedByBpms
  public ForeignSignalAggregate nameTheStartedWorkflow(
      final BpmsStartTrigger trigger) {

    final var aggregate = new ForeignSignalAggregate();
    aggregate.setId("signal-"
        + trigger.signalName()
        + "-"
        + UUID.randomUUID());
    return aggregate;

  }

  @WorkflowTask(taskDefinition = "recordForeignSignalStart")
  public void recordForeignSignalStart(
      final ForeignSignalAggregate aggregate) {

    aggregate.setProcessedBy("recordForeignSignalStart");

  }

}
