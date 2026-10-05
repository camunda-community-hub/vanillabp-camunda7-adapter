package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the repeated-delivery integration test: one task whose every
 * entry is counted, so a test can tell a handler which ran twice from one whose second
 * delivery was answered from the record VanillaBP wrote.
 */
@Service
@WorkflowService(
    workflowAggregateClass = RepeatedDeliveryTestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "RepeatedDeliveryProcess"))
public class RepeatedDeliveryTestWorkflowService {

  private final ProcessService<RepeatedDeliveryTestAggregate> processService;

  private final RepeatedDeliveryProbe probe;

  public RepeatedDeliveryTestWorkflowService(
      final ProcessService<RepeatedDeliveryTestAggregate> processService,
      final RepeatedDeliveryProbe probe) {

    this.processService = processService;
    this.probe = probe;

  }

  /**
   * Starts the workflow through the VanillaBP API, which lands in the first prioritized
   * adapter - the one sharing the application's datasource.
   */
  public RepeatedDeliveryTestAggregate startOnTheFirstPrioritizedAdapter(
      final RepeatedDeliveryTestAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void repeatedDeliveryTask(
      final RepeatedDeliveryTestAggregate aggregate) {

    probe.countHandlerInvocation();
    aggregate.setHandlerRuns(aggregate.getHandlerRuns() + 1);

  }

}
