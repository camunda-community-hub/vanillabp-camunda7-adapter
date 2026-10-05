package io.vanillabp.camunda7.it;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the signal integration test: the workflow waits at an
 * intermediate signal catch event, and a broadcast lets it continue.
 */
@Service
@WorkflowService(
    workflowAggregateClass = SignalTestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "SignalCatchProcess"))
public class SignalTestWorkflowService {

  @Autowired
  private ProcessService<SignalTestAggregate> processService;

  public SignalTestAggregate startWorkflow() {

    return processService.startWorkflow(new SignalTestAggregate());

  }

  public void broadcast(
      final String signalName) {

    processService.sendSignal(signalName);

  }

  @WorkflowTask(taskDefinition = "recordSignal")
  public void recordSignal(
      final SignalTestAggregate aggregate) {

    aggregate.setProcessedBy("recordSignal");

  }

}
