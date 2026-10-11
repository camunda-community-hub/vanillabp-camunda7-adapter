package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the repeated-delivery integration test. It has one task, and
 * every entry into it is counted. So a test can tell a handler which ran twice from one
 * whose second delivery was answered from the record which VanillaBP wrote.
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
   * Starts the workflow through the VanillaBP API. The start lands in the adapter with the
   * first priority, which is the one that shares the datasource of the application.
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
