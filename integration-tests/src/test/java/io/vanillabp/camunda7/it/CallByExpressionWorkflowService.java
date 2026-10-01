package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the call-by-expression test. It declares both processes on
 * ONE aggregate class, which is how an application says that the called process
 * continues the business case of its caller.
 */
@Service
@WorkflowService(
    workflowAggregateClass = CallByExpressionAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "CallByExpressionCaller"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "CallByExpressionChild"))
public class CallByExpressionWorkflowService {

  private final ProcessService<CallByExpressionAggregate> processService;

  public CallByExpressionWorkflowService(
      final ProcessService<CallByExpressionAggregate> processService) {

    this.processService = processService;

  }

  @Transactional
  public CallByExpressionAggregate startCaller(
      final CallByExpressionAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  /**
   * The one task of the called process. It writes into the aggregate it is handed, so
   * the test can see from the caller's row whether the called process found it.
   */
  @WorkflowTask
  public void theTaskOfTheCalledProcess(
      final CallByExpressionAggregate aggregate) {

    aggregate.setWhatTheCalledProcessWrote("the called process ran on aggregate "
        + aggregate.getId());

  }

}
