package io.vanillabp.camunda7.quarkus.listeners;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Owns <code>ModelledListenerProcess</code>, whose end event carries both listener forms a
 * <code>&#64;WorkflowTask</code> method can serve: a <code>camunda:expression</code> and a
 * <code>camunda:delegateExpression</code>.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = ListenerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ModelledListenerProcess"))
public class ListenerWorkflowService {

  @Inject
  ProcessService<ListenerAggregate> processService;

  public ListenerAggregate start() {

    return processService.startWorkflow(new ListenerAggregate());

  }

  @WorkflowTask(taskDefinition = "doTheWork")
  public void doTheWork(
      final ListenerAggregate aggregate) {

    aggregate.setTheWorkWasDone(true);

  }

  /**
   * The <code>camunda:expression</code> form: the engine evaluates the expression and the
   * handler runs while it is evaluated.
   */
  @WorkflowTask(taskDefinition = "theEndIsReached")
  public void theEndIsReached(
      final ListenerAggregate aggregate) {

    aggregate.setTheEndWasReached(true);

  }

  /**
   * The <code>camunda:delegateExpression</code> form: the engine expects the expression to
   * yield a listener object, which is what the adapter's EL resolver hands it.
   */
  @WorkflowTask(taskDefinition = "theEndIsDone")
  public void theEndIsDone(
      final ListenerAggregate aggregate) {

    aggregate.setTheEndWasDone(true);

  }

}
