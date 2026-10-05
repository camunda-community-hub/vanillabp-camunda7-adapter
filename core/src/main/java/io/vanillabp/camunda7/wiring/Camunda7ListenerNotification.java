package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;

/**
 * What the EL resolver hands the engine for a {@code camunda:executionListener} written as
 * {@code camunda:delegateExpression}: the engine resolves the expression and expects a listener
 * object, so the adapter gives it one instead of the activity behavior a task is served with.
 * <p>
 * A listener neither completes nor leaves its element - the engine is in the middle of a
 * transition of its own while the listener runs - so the only thing that happens here is the
 * call into the application. That is also why this is a class of its own rather than a second
 * role of {@link Camunda7WorkflowTaskBehavior}: a listener which could be mistaken for an
 * activity behavior would leave the element the first time somebody wired it differently.
 */
class Camunda7ListenerNotification implements ExecutionListener {

  private final Camunda7WorkflowTaskBehavior behavior;

  private final Camunda7TaskConnectable connectable;

  Camunda7ListenerNotification(
      final Camunda7WorkflowTaskBehavior behavior,
      final Camunda7TaskConnectable connectable) {

    this.behavior = behavior;
    this.connectable = connectable;

  }

  @Override
  public void notify(
      final DelegateExecution execution) throws Exception {

    invoke(behavior, connectable, execution);

  }

  /**
   * Calls the application's method for one listener notification, whichever of the two forms
   * the BPMN uses.
   * <p>
   * A {@code TaskException} of the method arrives here as a {@link BpmnError}, which is what a
   * TASK raises to reach an error boundary event. A listener has no token to route: the engine
   * is inside a transition, the error would surface as an unhandled engine exception, and an
   * incident saying nothing about the cause is the worst of the possible answers. So the cause
   * is named instead.
   *
   * @param behavior What invokes the application's method
   * @param connectable The listener being notified
   * @param execution The current execution
   */
  static void invoke(
      final Camunda7WorkflowTaskBehavior behavior,
      final Camunda7TaskConnectable connectable,
      final DelegateExecution execution) {

    try {
      behavior.invokeHandler(execution);
    } catch (final BpmnError e) {
      throw new ProcessEngineException(
          """
              The @WorkflowTask method serving the listener '%s' of BPMN process '%s' (workflow \
              module '%s') threw a TaskException! A listener cannot raise a BPMN error: the engine is \
              inside a transition of its own while the listener runs and has no token to route. Model \
              the work as a task of the process where an error has to change the path."""
              .formatted(
                  connectable.taskDefinition(), connectable.bpmnProcessId(), connectable.workflowModuleId()), e);
    }

  }

}
