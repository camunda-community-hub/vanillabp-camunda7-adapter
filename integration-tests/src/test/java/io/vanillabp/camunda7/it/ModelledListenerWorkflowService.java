package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Owns <code>ModelledListenerProcess</code>. Its end event carries the two listener forms
 * which a <code>@WorkflowTask</code> method can serve: a <code>camunda:expression</code>
 * and a <code>camunda:delegateExpression</code>.
 * <p>
 * It is a bean only under the profile of its own test, like every other scenario of this
 * module. All tests here boot the same application. And this model does not even deploy
 * without <code>allow-listeners</code>.
 */
@Service
@Profile("modelled-listeners")
@WorkflowService(
    workflowAggregateClass = ModelledListenerAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ModelledListenerProcess"))
public class ModelledListenerWorkflowService {

  private final ProcessService<ModelledListenerAggregate> processService;

  public ModelledListenerWorkflowService(
      final ProcessService<ModelledListenerAggregate> processService) {

    this.processService = processService;

  }

  public ModelledListenerAggregate start(
      final ModelledListenerAggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask(taskDefinition = "doTheWork")
  public void doTheWork(
      final ModelledListenerAggregate aggregate) {

    aggregate.setTheWorkWasDone(true);

  }

  /**
   * The listener of the user task. It is served for both moments a listener knows. It fires
   * when the task is created. It is called again when the boundary timer takes the task
   * away, because VanillaBP attaches the end listener of the engine to that element for
   * exactly this.
   */
  @WorkflowTask(taskDefinition = "theWaitBegins")
  public void theWaitBegins(
      final ModelledListenerAggregate aggregate,
      @TaskEvent(TaskEvent.Event.ALL) final TaskEvent.Event event) {

    if (event == TaskEvent.Event.CANCELED) {
      aggregate.setTheWaitWasCanceled(true);
      return;
    }
    aggregate.setTheWaitBegan(true);

  }

  /**
   * The <code>camunda:expression</code> form. The engine evaluates the expression, and the
   * handler runs while it is evaluated.
   */
  @WorkflowTask(taskDefinition = "theEndIsReached")
  public void theEndIsReached(
      final ModelledListenerAggregate aggregate) {

    aggregate.setTheEndWasReached(true);

  }

  /**
   * The <code>camunda:delegateExpression</code> form. The engine expects the expression to
   * give a listener object, and the EL resolver of the adapter hands it one.
   */
  @WorkflowTask(taskDefinition = "theEndIsDone")
  public void theEndIsDone(
      final ModelledListenerAggregate aggregate) {

    aggregate.setTheEndWasDone(true);

  }

}
