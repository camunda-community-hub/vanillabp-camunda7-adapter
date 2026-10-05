package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process which starts with a plain start event. It has NO
 * <code>@WorkflowStartedByBpms</code> method on purpose: the engine never starts this
 * process by itself, so the application boots without one, and a start past VanillaBP is
 * refused with a message carrying the method to write.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ForeignPlainAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ForeignPlainProcess"))
public class ForeignPlainWorkflowService {

  private final ProcessService<ForeignPlainAggregate> processService;

  public ForeignPlainWorkflowService(
      final ProcessService<ForeignPlainAggregate> processService) {

    this.processService = processService;

  }

  /**
   * Starts the workflow the way an application does, through a plain start event.
   *
   * @param id The id of the workflow aggregate to start
   */
  @Transactional
  public void startTheWorkflow(
      final String id) {

    final var aggregate = new ForeignPlainAggregate();
    aggregate.setId(id);
    processService.startWorkflow(aggregate);

  }

  @WorkflowTask(taskDefinition = "recordForeignPlainStart")
  public void recordForeignPlainStart(
      final ForeignPlainAggregate aggregate) {

    aggregate.setProcessedBy("recordForeignPlainStart");

  }

}
