package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;

import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.spi.service.TaskEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Delivers {@link TaskEvent.Event#CANCELED} to the <code>&#64;WorkflowTask</code> methods
 * which subscribe to it. The parse listener ({@link Camunda7AsyncBpmnParseListener}) attaches
 * it as an END execution listener. It calls the handler when the activity ends by
 * CANCELLATION, for example by an interrupting boundary event or by the end of the process.
 * A normal completion does not fire it. The core skips methods without a matching
 * <code>&#64;TaskEvent</code> parameter filter. So a handler which does not ask for lifecycle
 * events never sees the delivery.
 * <p>
 * Two kinds of method are told, and the activity decides which. One is the TASK of the
 * activity. That is why this listener sits on every service-like activity. The other is a
 * served LISTENER of the activity. A listener somebody modelled fires at the moment the
 * modeller picked. An element which a boundary event takes away never reaches that moment.
 * So the method serving it would never learn what happened. The parse listener attaches this
 * listener to those activities as well. The registry says which listeners of an activity are
 * waiting for it. A listener the modeller put on <code>end</code> is not among them. The
 * engine fires an END execution listener on a cancellation too, so that method already hears
 * the moment. Decision 20 in the repository's DECISIONS.md says why a listener knows these
 * two events and no others.
 * <p>
 * The invocation runs INSIDE the engine's cancellation transaction
 * ({@code runInCurrentTransaction}). On the application's datasource, the changes the handler
 * makes to the aggregate commit or roll back together with the cancellation itself. An engine
 * on a datasource of its own cannot share that transaction. Then VanillaBP opens a
 * transaction of its own around the handler.
 */
@Slf4j
public class Camunda7TaskCancellationListener implements ExecutionListener {

  private final WorkflowTaskInvoker workflowTaskInvoker;

  private final Camunda7TaskRegistry taskRegistry;

  /**
   * One listener for the whole engine. It is attached to every service-like activity of a
   * process the application claims, and to the activities whose served listeners wait for a
   * cancelation. The core then skips a method which does not ask about the cancelation.
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
   * The registry this listener looks things up in. The parse listener asks it which elements
   * of a process carry a served listener that waits for a cancellation.
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
      // a normal completion. The regular invocation already happened
      return;
    }

    final var processDefinition = executionEntity.getProcessDefinition();
    // the tenant tells the workflow module only while a tenant keeps the module apart.
    // With prefixed identifiers the registry knows which module a process definition key
    // belongs to, and what its plain id is
    final var scopedBpmnProcessId = processDefinition.getKey();
    final var workflowModuleId = taskRegistry
        .resolveWorkflowModuleId(processDefinition.getTenantId(), scopedBpmnProcessId);
    final var bpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, scopedBpmnProcessId);

    // the SCOPED id, because the registry is keyed by it. The plain id belongs into the
    // delivery and into the log, never into a lookup. With prefixed identifiers the two
    // differ. Then the lookup would find nothing, and the cancellation would stay silent.
    // And the tasks of the version this workflow runs on, because another version may wire
    // another task to the same element
    final var tasksOfTheOwnVersion = taskRegistry
        .tasksOf(
            workflowModuleId,
            scopedBpmnProcessId,
            processDefinition.getId(),
            executionEntity::getBpmnModelInstance);
    final var connectable = tasksOfTheOwnVersion
        .resolve(execution.getCurrentActivityId(), null)
        // a TASK of the element and nothing else. Camunda7UserTaskEventListener tells a user
        // task about its cancellation, through the engine's DELETE task-listener event. A
        // listener of the element is told below. Both would hear it twice from here, because
        // a match by ELEMENT answers with whatever sits on that element
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
        .forEach(listener -> tasksOfTheOwnVersion
            .resolve(null, listener.taskDefinition())
            // the registry answers by name first and then falls back to the element. That is
            // not what is asked for here. A listener is meant, and its task definition names it
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
   * The outcome is ignored on purpose. There is nothing to complete, because the activity is
   * being canceled. The core skips a method which does not subscribe to CANCELED.
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
