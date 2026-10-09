package io.vanillabp.camunda7.it;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
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

  /**
   * Every end the application was told about, as "aggregate|kind|end event", so the test can
   * count how often one workflow ended.
   */
  public static final List<String> ENDS_REPORTED = new CopyOnWriteArrayList<>();

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

  /**
   * Records each end. The called process runs on the aggregate of its caller, so it is a step
   * of that workflow, and only the end of the caller may arrive here.
   */
  @WorkflowEnded
  public void workflowEnded(
      final CallByExpressionAggregate aggregate,
      final WorkflowEnd end) {

    ENDS_REPORTED.add("%s|%s|%s".formatted(aggregate.getId(), end.kind(), end.endEventId()));

  }

}
