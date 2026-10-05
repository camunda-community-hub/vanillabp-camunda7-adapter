package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the aggregateChanged integration test: the
 * workflow waits at a conditional event whose condition reads the aggregate.
 */
@Service
@WorkflowService(
    workflowAggregateClass = AggregateChangedTestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "AggregateChangedProcess"))
public class AggregateChangedTestWorkflowService {

  private final ProcessService<AggregateChangedTestAggregate> processService;

  private final AggregateChangedTestRepository repository;

  public AggregateChangedTestWorkflowService(
      final ProcessService<AggregateChangedTestAggregate> processService,
      final AggregateChangedTestRepository repository) {

    this.processService = processService;
    this.repository = repository;

  }

  public AggregateChangedTestAggregate startWorkflow() {

    return processService.startWorkflow(new AggregateChangedTestAggregate());

  }

  /**
   * Sets the attribute the conditional event waits for and tells the BPMS - without
   * the push the engine would never look at its condition.
   *
   * @param aggregateId The aggregate's id
   */
  public void becomeReady(
      final Long aggregateId) {

    final var aggregate = repository.findById(aggregateId).orElseThrow();
    aggregate.setReadyToGo(true);
    processService.aggregateChanged(aggregate);

  }

  @WorkflowTask
  public void conditionMet(
      final AggregateChangedTestAggregate aggregate) {

    aggregate.setProcessedBy("conditionMet");

  }

}
