package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the test about the iteration a process called by an expression
 * runs in. Both processes are declared on ONE aggregate class, which is how an application
 * says that the called process continues the business case of its caller.
 */
@Service
@WorkflowService(
    workflowAggregateClass = MiCallExprAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "MiCallExprCaller"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "MiCallExprChild"))
public class MiCallExprWorkflowService {

  private final ProcessService<MiCallExprAggregate> processService;

  public MiCallExprWorkflowService(
      final ProcessService<MiCallExprAggregate> processService) {

    this.processService = processService;

  }

  @Transactional
  public MiCallExprAggregate startCaller() {

    final var aggregate = new MiCallExprAggregate();
    aggregate.setProcessToCall("MiCallExprChild");
    return processService.startWorkflow(aggregate);

  }

  /**
   * The one task of the called process. It names no level of its own: every iteration it
   * runs in belongs to the caller.
   *
   * @param aggregate The workflow aggregate of the caller
   * @param chain What the task was told about the iteration it runs in
   */
  @WorkflowTask
  public void reportTheIterationOfTheCaller(
      final MiCallExprAggregate aggregate,
      @MultiInstanceElement(resolverBean = MiCallExprChainResolver.class) final String chain) {

    aggregate
        .setReported(
            aggregate.getReported() == null
                ? chain
                : aggregate.getReported()
                    + ","
                    + chain);

  }

}
