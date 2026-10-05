package io.vanillabp.camunda7.quarkus.callactivity;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * One workflow aggregate, two BPMN processes: the called process is declared as a
 * secondary process of the class declaring the process to be started.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = CallActivityAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "CallingProcess"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "CalledProcess"))
public class CallActivityWorkflowService {

  @Inject
  ProcessService<CallActivityAggregate> processService;

  public CallActivityAggregate startWorkflow() {

    return processService.startWorkflow(new CallActivityAggregate());

  }

  /**
   * Runs in the CALLED process - it only finds its aggregate if the business key
   * reached the child instance.
   */
  @WorkflowTask
  public void calledTask(
      final CallActivityAggregate aggregate) {

    aggregate.setCalledProcessDid("its-work");

  }

}
