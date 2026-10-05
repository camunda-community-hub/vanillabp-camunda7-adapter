package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;

import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.spi.service.TaskEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivers {@link TaskEvent.Event#CANCELED} to <code>&#64;WorkflowTask</code>
 * methods subscribing to it: attached as an END execution listener at parse time
 * ({@link Camunda7AsyncBpmnParseListener}), it invokes the handler when the activity ends by
 * CANCELLATION (interrupting boundary event, process termination) - a normal completion does
 * not fire it. The core skips methods without a matching <code>&#64;TaskEvent</code> parameter
 * filter, so handlers not asking for lifecycle events never see the delivery.
 * <p>
 * Two kinds of method are told, and the activity decides which. The TASK of the activity is one
 * of them, which is why this listener sits on every service-like activity. The served LISTENERS
 * of the activity are the other: a listener somebody modelled fires at the moment the modeller
 * picked, and an element taken away by a boundary event never reaches that moment, so the method
 * serving it would never learn what happened. The parse listener attaches this listener to those
 * activities as well, and the registry says which listeners of an activity are waiting for it.
 * A listener the modeller put on <code>end</code> is not among them: the engine fires an END
 * execution listener on a cancellation too, so that method already hears the moment. Why a
 * listener knows these two events and no others is decision 26 in the repository's DECISIONS.md.
 * <p>
 * The invocation runs INSIDE the engine's cancellation transaction
 * ({@code runInCurrentTransaction}): aggregate changes made by the handler commit
 * or roll back together with the cancellation itself.
 */
@Slf4j
public class Camunda7TaskCancellationListener implements ExecutionListener {

  private final WorkflowTaskInvoker workflowTaskInvoker;

  private final Camunda7TaskRegistry taskRegistry;

  /**
   * One listener for the whole engine, attached to every activity whose cancelation a
   * method of the application asks about.
   *
   * @param workflowTaskInvoker Where the application's method is called
   * @param taskRegistry What the method serving the canceled element is looked up in
   */
  public Camunda7TaskCancellationListener(
      final WorkflowTaskInvoker workflowTaskInvoker,
      final Camunda7TaskRegistry taskRegistry) {

    this.workflowTaskInvoker = workflowTaskInvoker;
    this.taskRegistry = taskRegistry;

  }

  /**
   * The registry this listener resolves with, which the parse listener asks which elements of a
   * process carry a served listener waiting for a cancellation.
   *
   * @return The registry of this engine
   */
  Camunda7TaskRegistry taskRegistry() {

    return taskRegistry;

  }

  @Override
  public void notify(
      final DelegateExecution execution) throws Exception {

    if (!(execution instanceof ExecutionEntity executionEntity) || !executionEntity.isCanceled()) {
      // a normal completion - the regular invocation already happened
      return;
    }

    final var processDefinition = executionEntity.getProcessDefinition();
    // The tenant answers the workflow module only while the module IS
    // isolated by one - with prefixed identifiers the registry knows which module a
    // process definition key belongs to, and what its plain id is
    final var scopedBpmnProcessId = processDefinition.getKey();
    final var workflowModuleId = taskRegistry
        .resolveWorkflowModuleId(processDefinition.getTenantId(), scopedBpmnProcessId);
    final var bpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, scopedBpmnProcessId);

    final var connectable = taskRegistry
        // the SCOPED id, because that is what the registry is keyed by. The plain id
        // belongs into the delivery and into the log, never into a lookup: with
        // prefixed identifiers the two differ, the lookup finds nothing and the
        // cancellation stays silent
        .resolve(
            workflowModuleId,
            scopedBpmnProcessId,
            execution.getCurrentActivityId(),
            null)
        // a TASK of the element and nothing else. A user task is told about its cancellation by
        // Camunda7UserTaskEventListener, through the engine's DELETE task-listener event, and a
        // listener of the element is told below - both would hear it twice from here, because a
        // match by ELEMENT answers with whatever sits on that element
        .filter(candidate -> !candidate.isExecutionListener())
        .filter(candidate -> candidate.type() != Camunda7TaskConnectable.Type.USER_TASK);
    if (connectable.isPresent()) {
      log.debug(
          "Camunda7: delivering CANCELED for task '{}' (activity '{}') of BPMN process '{}' of "
              + "workflow module '{}'",
          execution.getId(),
          execution.getCurrentActivityId(),
          bpmnProcessId,
          workflowModuleId);
      deliverCancellation(connectable.get(), execution);
    }

    taskRegistry
        .listenersNeedingACancellation(workflowModuleId, scopedBpmnProcessId)
        .stream()
        .filter(listener -> listener.elementId().equals(execution.getCurrentActivityId()))
        .forEach(listener -> taskRegistry
            .resolve(workflowModuleId, scopedBpmnProcessId, null, listener.taskDefinition())
            // the registry answers by name first and falls back to the element, which is not
            // what is asked for here: a listener is meant, and its task definition names it
            .filter(candidate -> listener.taskDefinition().equals(candidate.taskDefinition()))
            .ifPresent(listenerConnectable -> {
              log.debug(
                  "Camunda7: delivering CANCELED to the listener '{}' (activity '{}') of BPMN process "
                      + "'{}' of workflow module '{}'",
                  listener.taskDefinition(),
                  execution.getCurrentActivityId(),
                  bpmnProcessId,
                  workflowModuleId);
              deliverCancellation(listenerConnectable, execution);
            }));

  }

  /**
   * Calls one <code>&#64;WorkflowTask</code> method with the cancellation.
   * <p>
   * The outcome is deliberately ignored: there is nothing to complete, the activity is being
   * canceled. The core skips a method which does not subscribe to CANCELED.
   *
   * @param connectable The task or listener whose method is called
   * @param execution The execution being canceled
   */
  private void deliverCancellation(
      final Camunda7TaskConnectable connectable,
      final DelegateExecution execution) {

    workflowTaskInvoker.invokeWorkflowTask(
        connectable.workflowModuleId(),
        connectable.bpmnProcessId(),
        new Camunda7WorkflowTaskBehavior.Camunda7TaskInvocationContext(
            connectable, execution, TaskEvent.Event.CANCELED, taskRegistry));

  }

}
