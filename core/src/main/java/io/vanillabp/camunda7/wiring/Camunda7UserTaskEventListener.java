package io.vanillabp.camunda7.wiring;

import java.util.Map;

import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;

import io.vanillabp.camunda7.api.Camunda7MultiInstances;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.spi.service.TaskEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Notifies <code>&#64;WorkflowTask</code> methods about USER-task lifecycle events:
 * attached as a task listener to user tasks at parse time
 * ({@link Camunda7AsyncBpmnParseListener}) for the engine's global CREATE and
 * DELETE events - CREATE delivers {@link TaskEvent.Event#CREATED} (e.g. to send an
 * email or feed an own task list), DELETE delivers
 * {@link TaskEvent.Event#CANCELED}. The handler is OPTIONAL (a user task without
 * one is simply processed through forms/task lists) and never completes the task
 * on return - completion arrives via <code>ProcessService#completeUserTask</code>.
 * The invocation runs INSIDE the engine's transaction
 * ({@code runInCurrentTransaction}): aggregate changes commit or roll back with
 * the task's creation/cancellation itself - unless the engine was given a datasource of
 * its own, whose transaction the application's persistence cannot join, so that VanillaBP
 * opens the one the workflow aggregate is saved in.
 * <p>
 * Why a listener is added to the deployed model, and only where a handler exists, is decision 5 in
 * the repository's DECISIONS.md.
 */
@Slf4j
public class Camunda7UserTaskEventListener implements TaskListener {

  private final WorkflowTaskInvoker workflowTaskInvoker;

  private final Camunda7TaskRegistry taskRegistry;

  /**
   * One listener for the whole engine, attached to every user task while the model is
   * parsed.
   *
   * @param workflowTaskInvoker Where the application's method is called
   * @param taskRegistry What the method serving the user task is looked up in
   */
  public Camunda7UserTaskEventListener(
      final WorkflowTaskInvoker workflowTaskInvoker,
      final Camunda7TaskRegistry taskRegistry) {

    this.workflowTaskInvoker = workflowTaskInvoker;
    this.taskRegistry = taskRegistry;

  }

  @Override
  public void notify(
      final DelegateTask delegateTask) {

    final var execution = (ExecutionEntity) delegateTask.getExecution();
    final var processDefinition = execution.getProcessDefinition();
    // The tenant answers the workflow module only while the module IS
    // isolated by one - with prefixed identifiers the registry knows which module a
    // process definition key belongs to, and what its plain id is
    final var scopedBpmnProcessId = processDefinition.getKey();
    final var workflowModuleId = taskRegistry
        .resolveWorkflowModuleId(processDefinition.getTenantId(), scopedBpmnProcessId);
    final var bpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, scopedBpmnProcessId);

    final var connectable = taskRegistry
        .resolve(
            workflowModuleId,
            scopedBpmnProcessId,
            delegateTask.getTaskDefinitionKey(),
            null)
        .filter(candidate -> candidate.type() == Camunda7TaskConnectable.Type.USER_TASK);
    if (connectable.isEmpty()) {
      // not a VanillaBP-wired user task of this engine
      return;
    }

    final var event = TaskListener.EVENTNAME_DELETE.equals(delegateTask.getEventName())
        ? TaskEvent.Event.CANCELED
        : TaskEvent.Event.CREATED;

    log.debug(
        "Camunda7: delivering {} for user task '{}' (activity '{}') of BPMN process '{}' of "
            + "workflow module '{}'",
        event,
        delegateTask.getId(),
        delegateTask.getTaskDefinitionKey(),
        bpmnProcessId,
        workflowModuleId);

    // user-task handlers are OPTIONAL by design - skip silently without one
    final var context = new Camunda7UserTaskInvocationContext(
        connectable.get(), delegateTask, event, taskRegistry
            .versionOfDefinition(processDefinition.getId()), taskRegistry);
    if (!aMethodServesThisUserTask(workflowModuleId, bpmnProcessId, context)) {
      log.trace(
          "Camunda7: no @WorkflowTask handler for user task '{}' of BPMN process '{}' - skipping "
              + "the {} notification",
          delegateTask.getTaskDefinitionKey(),
          bpmnProcessId,
          event);
      return;
    }
    // the core's event filter skips methods not subscribing to the event
    final WorkflowTaskOutcome outcome = workflowTaskInvoker.invokeWorkflowTask(
        workflowModuleId,
        bpmnProcessId,
        context);

    if (outcome.kind() == WorkflowTaskOutcome.Kind.BPMN_ERROR) {
      // a TaskException in a user-task NOTIFICATION handler is a defect: the task
      // was just created/canceled - there is nothing to complete by BPMN error
      throw new IllegalStateException(
          ("The @WorkflowTask method notified about the %s event of user task '%s' (BPMN process "
              + "'%s' of workflow module '%s') threw a TaskException! User-task notification "
              + "handlers must not raise BPMN errors - route errors via "
              + "ProcessService#cancelUserTask instead.")
              .formatted(event, delegateTask.getTaskDefinitionKey(), bpmnProcessId, workflowModuleId));
    }

  }

  /**
   * Whether a <code>&#64;WorkflowTask</code> method serves this user task, asked with BOTH
   * keys a task is wired by: the task definition and the element id. A method names either
   * of the two (<code>&#64;WorkflowTask(taskDefinition = ...)</code> respectively
   * <code>&#64;WorkflowTask(id = ...)</code>), and the task definition of a user task is its
   * FORM KEY wherever the model carries one, so the two are different names here more often
   * than anywhere else.
   * <p>
   * Asked with the task definition alone, a user task with a form key whose handler names the
   * element id answered "nobody serves this" and the notification was skipped without a word.
   * That is worse than a task which fails, because nothing happens at all.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param context What this notification reports about the user task
   * @return Whether a method serves it
   */
  private boolean aMethodServesThisUserTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final TaskInvocationContext context) {

    return workflowTaskInvoker
        .workflowTaskHandlerExists(workflowModuleId, bpmnProcessId, context.getTaskDefinition()) || workflowTaskInvoker
            .workflowTaskHandlerExists(workflowModuleId, bpmnProcessId, context.getBpmnElementId());

  }

  /**
   * The neutral invocation context built from a Camunda 7 user-task event.
   */
  static class Camunda7UserTaskInvocationContext implements TaskInvocationContext {

    private final Camunda7TaskConnectable connectable;

    private final DelegateTask delegateTask;

    private final TaskEvent.Event event;

    /**
     * The version of the deployed process definition this user task belongs to
     * or <code>null</code>.
     */
    private final String processVersion;

    /**
     * What this context asks about the engine it was built in: the adapter holding it,
     * whether that engine runs on a datasource of its own, and whether a called process
     * continues the business case of its caller. May be <code>null</code> (tests): no
     * adapter is named then, the handler runs in the engine's transaction, and the
     * multi-instance walk ends at a call activity naming its called process in an
     * expression.
     */
    private final Camunda7TaskRegistry taskRegistry;

    private Map<String, MultiInstanceValue> multiInstances;

    Camunda7UserTaskInvocationContext(
        final Camunda7TaskConnectable connectable,
        final DelegateTask delegateTask,
        final TaskEvent.Event event,
        final String processVersion,
        final Camunda7TaskRegistry taskRegistry) {

      this.connectable = connectable;
      this.delegateTask = delegateTask;
      this.event = event;
      this.processVersion = processVersion;
      this.taskRegistry = taskRegistry;

    }

    @Override
    public String getAdapterId() {

      return taskRegistry == null
          ? null
          : taskRegistry.getAdapterId();

    }

    @Override
    public String getProcessVersion() {

      return processVersion;

    }

    @Override
    public String getTaskDefinition() {

      return connectable.taskDefinition() != null
          ? connectable.taskDefinition()
          : connectable.elementId();

    }

    @Override
    public String getWorkflowAggregateId() {

      return delegateTask.getExecution().getBusinessKey();

    }

    @Override
    public String getBpmnElementId() {

      // the user task's element id as the model spells it. The task definition of a user
      // task is its form key where it has one, so the two say different things here
      return connectable.elementId();

    }

    @Override
    public String getWorkflowId() {

      // the engine's own id of the running instance the user task belongs to
      return delegateTask.getProcessInstanceId();

    }

    @Override
    public String getTaskId() {

      // the engine's task ID (ACT_RU_TASK) - used by
      // ProcessService#completeUserTask/#cancelUserTask
      return delegateTask.getId();

    }

    @Override
    public TaskKind getTaskKind() {

      // this context exists only for a user-task event, so nothing has to be read to
      // know the kind. The id above comes out of ACT_RU_TASK, and completeTask would
      // look for an execution under it and find none
      return TaskKind.USER_TASK;

    }

    @Override
    public TaskEvent.Event getTaskEvent() {

      return event;

    }

    @Override
    public Object getTaskParameter(
        final String name) {

      return delegateTask.getVariableLocal(name);

    }

    @Override
    public boolean runInCurrentTransaction() {

      // the notification runs in the transaction which creates or cancels the task,
      // and that is the application's own as long as the engine shares its datasource.
      // An engine on a datasource of its own commits elsewhere, so VanillaBP opens the
      // transaction the workflow aggregate is saved in itself
      return (taskRegistry == null) || !taskRegistry.engineRunsOnItsOwnDataSource();

    }

    @Override
    public String getActivationId() {

      // the activity instance the user task belongs to - the same value the
      // asynchronous-task side reports, and per activation for the same reason. The
      // user task's own id would do as well; the activity instance is chosen because
      // creation and cancellation of one task then agree, which is what an activation
      // is supposed to mean
      return delegateTask.getExecution().getActivityInstanceId();

    }

    @Override
    public String getDeliveryId() {

      // No identity, not even on an own datasource. A user task gets no job of its
      // own: one transaction creates every user task the token reaches, so the job at
      // hand names several notifications and the core would take the second for a
      // repetition of the first. What is unique per task - the task id, the activity
      // instance - is generated while the task is created, so a rolled-back transaction
      // produces a new one and a repetition would look like new work. The handler of a
      // user-task notification therefore runs again after a crash, and no record of
      // VanillaBP changes that
      return null;

    }

    @Override
    public Map<String, MultiInstanceValue> getMultiInstances() {

      if (multiInstances == null) {
        // the engine hands a task listener the execution the user task hangs on, which is
        // the execution the walk starts at on the service-like side as well. So a user task
        // reads the same levels a service task in its place would read, and a model may not
        // tell the two apart. With the registry: it answers for a call activity which names
        // the process it calls in an expression, which the deployed model could say nothing
        // about
        multiInstances = Camunda7MultiInstances.of(delegateTask.getExecution(), taskRegistry);
      }
      return multiInstances;

    }

  }

}
