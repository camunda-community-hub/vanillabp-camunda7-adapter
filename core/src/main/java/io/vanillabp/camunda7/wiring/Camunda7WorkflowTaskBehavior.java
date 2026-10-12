package io.vanillabp.camunda7.wiring;

import java.util.Map;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.impl.bpmn.behavior.AbstractBpmnActivityBehavior;
import org.camunda.bpm.engine.impl.pvm.delegate.ActivityExecution;

import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;

/**
 * Executes a <code>&#64;WorkflowTask</code> method for one BPMN task, through the core's
 * {@link WorkflowTaskInvoker}. The invocation context is built from the
 * {@link DelegateExecution}. The business key is the serialized aggregate ID, the local
 * variables give the <code>&#64;TaskParam</code> values, and the execution hierarchy gives
 * the multi-instance context. The handler runs INSIDE the engine's transaction
 * ({@link TaskInvocationContext#runInCurrentTransaction()}). So business changes and engine
 * state commit or roll back together.
 * <p>
 * That holds while the engine shares the application's datasource. An engine with a
 * datasource of its own commits on a resource the application's persistence cannot join.
 * So VanillaBP itself opens the transaction around the handler. The aggregate commits
 * before the job does. After a crash in between, the engine hands the job out again, and
 * that is a REPEATED delivery. That is why an engine in that mode names its deliveries
 * ({@link TaskInvocationContext#getDeliveryId()}, the id of the job at hand). The core
 * answers the repetition from what it recorded.
 * <p>
 * Outcome mapping:
 * <ul>
 * <li>COMPLETED: the activity is left, and the task completes.</li>
 * <li>COMPLETION_PENDING (<code>&#64;TaskId</code> methods): the activity is NOT left. The
 * execution stays at the task until it is completed asynchronously through
 * <code>ProcessService#completeTask</code>, which signals the execution.</li>
 * <li>BPMN_ERROR ({@code TaskException}): a {@link BpmnError} with the exception's error
 * code is thrown, so an error boundary event can catch it. The aggregate changes were
 * saved, and they commit with the engine's transaction.</li>
 * <li>any other exception: it propagates. The engine rolls back the job transaction,
 * business changes included, and applies its retry rules.</li>
 * </ul>
 * It is used for <code>camunda:delegateExpression</code> tasks, as an
 * {@link AbstractBpmnActivityBehavior}, so the activity can stay open. It is also used for
 * <code>camunda:expression</code> tasks, which the {@link Camunda7TaskELResolver} executes
 * inline. Such a task cannot stay open. So <code>&#64;TaskId</code> methods require a
 * delegate expression.
 * <p>
 * Decision 6 in the repository's DECISIONS.md says why the handler runs in the engine's own
 * job transaction, and why this adapter relies on the engine's retry for recovery.
 */
public class Camunda7WorkflowTaskBehavior extends AbstractBpmnActivityBehavior {

  private final Camunda7TaskConnectable connectable;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * Translates the error code of a {@code TaskException} into what the engine knows.
   * The model's error codes are prefixed too, see decision 3 in the repository's
   * DECISIONS.md. May be <code>null</code>.
   */
  private final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  private final String adapterId;

  /**
   * Answers the version of the process definition an execution runs on.
   * May be <code>null</code> (tests). Then no version is reported.
   */
  private final Camunda7TaskRegistry taskRegistry;

  /**
   * For a test. There is no scoping, so the identifiers reach the engine as the application
   * wrote them. And no version is reported with a delivery.
   *
   * @param connectable The wired task this behavior serves
   * @param workflowTaskInvoker Where the application's method is called
   */
  public Camunda7WorkflowTaskBehavior(
      final Camunda7TaskConnectable connectable,
      final WorkflowTaskInvoker workflowTaskInvoker) {

    this(connectable, workflowTaskInvoker, null, null, null);

  }

  /**
   * The constructor the wiring uses.
   *
   * @param connectable The wired task this behavior serves
   * @param workflowTaskInvoker Where the application's method is called
   * @param scoping Translates the error code of a task exception into what the engine
   *          knows, or <code>null</code> where the identifiers are plain
   * @param adapterId The adapter id reported with the delivery, or <code>null</code>
   * @param taskRegistry Answers the version of the definition the execution runs on, or
   *          <code>null</code> where no version is reported
   */
  public Camunda7WorkflowTaskBehavior(
      final Camunda7TaskConnectable connectable,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping,
      final String adapterId,
      final Camunda7TaskRegistry taskRegistry) {

    this.connectable = connectable;
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.scoping = scoping;
    this.adapterId = adapterId;
    this.taskRegistry = taskRegistry;

  }

  /**
   * The BPMN error code as the engine knows it.
   * <p>
   * It is built from the workflow module of the process this task belongs to. The catcher
   * belongs to that module as well. This engine looks for the called process of a call
   * activity in the tenant of the calling instance. And under {@code use-prefix} a static
   * called element carries the prefix of the calling module. So a call activity stays inside
   * its module. Only a model which names another tenant on the call activity leaves it. The
   * deployment warns about that one. The code raised there would carry the other module's
   * prefix, and this code is not bent to fit.
   */
  private String scopedErrorCode(
      final String errorCode) {

    return (scoping == null) || (adapterId == null)
        ? errorCode
        : scoping.scopedIdentifier(connectable.workflowModuleId(), errorCode, adapterId);

  }

  @Override
  public void execute(
      final ActivityExecution execution) throws Exception {

    final var outcome = invokeHandler(execution);
    if (outcome.kind() == WorkflowTaskOutcome.Kind.COMPLETION_PENDING) {
      // @TaskId method. The task stays open. The completion arrives through
      // ProcessService#completeTask, which signals this execution
      return;
    }
    leave(execution);

  }

  /**
   * The signal name which carries a cancellation ({@code ProcessService#cancelTask}).
   * The signal data is the BPMN error code to propagate.
   */
  public static final String SIGNAL_CANCEL = "vanillabp:cancel";

  @Override
  public void signal(
      final ActivityExecution execution,
      final String signalName,
      final Object signalData) throws Exception {

    // asynchronous completion or cancellation. ProcessService#completeTask and
    // #cancelTask signal the parked execution, see Camunda7ProcessService
    if (SIGNAL_CANCEL.equals(signalName)) {
      // cancelTask: route the workflow through an error boundary event. On the signal
      // path a thrown BpmnError is not translated by itself, so the error is propagated
      // explicitly
      org.camunda.bpm.engine.impl.bpmn.helper.BpmnExceptionHandler.propagateBpmnError(
          new BpmnError(String.valueOf(signalData)),
          execution);
      return;
    }
    leave(execution);

  }

  /**
   * Runs the handler for the given execution, and maps a {@code TaskException} outcome to
   * a {@link BpmnError}. The delegate-expression path (this behavior) and the expression
   * path (the EL resolver) both use it.
   *
   * @param execution The current execution
   * @return The outcome (never BPMN_ERROR - that one is thrown)
   */
  WorkflowTaskOutcome invokeHandler(
      final DelegateExecution execution) {

    final var outcome = workflowTaskInvoker.invokeWorkflowTask(
        connectable.workflowModuleId(),
        connectable.bpmnProcessId(),
        new Camunda7TaskInvocationContext(connectable, execution, taskRegistry));
    // what the handler computed has to reach the engine BEFORE it evaluates what comes
    // next. Otherwise a gateway right behind this task would decide on the values of the
    // last ProcessService call. They are written here, inside the engine's transaction, so
    // the variables commit with the token. On the application's datasource the aggregate
    // commits with them. An engine on a datasource of its own saw the aggregate commit
    // before, in the transaction VanillaBP opened around the handler
    writeSharedValues(execution);
    if (outcome.kind() == WorkflowTaskOutcome.Kind.BPMN_ERROR) {
      // for the error boundary event. The aggregate changes were saved. On the application's
      // datasource they commit with the engine's transaction (the V1 contract). The values are written above,
      // because the flow behind the error boundary may branch on them as well
      throw outcome.errorName() != null
          ? new BpmnError(scopedErrorCode(outcome.errorCode()), outcome.errorName())
          : new BpmnError(scopedErrorCode(outcome.errorCode()));
    }
    return outcome;

  }

  /**
   * Writes the values the aggregate shares with the BPMS onto the execution.
   * <p>
   * The values are read in the CALLER's transaction, which is the engine's. On the
   * application's datasource the handler just changed the aggregate there. A read in a new
   * transaction would either see the state before the handler, or wait for the row this
   * transaction holds. An engine on a datasource of its own runs the handler in a transaction
   * of its own, which has committed by now. So the read sees what the handler saved. The read never
   * throws. Where nothing is shared, nothing is written.
   *
   * @param execution The execution of the task just processed
   */
  private void writeSharedValues(
      final DelegateExecution execution) {

    final var businessKey = execution.getProcessBusinessKey();
    if (businessKey == null) {
      // no aggregate identity at hand (a process started outside VanillaBP). There is
      // nothing to read the values from
      return;
    }
    final var sharedValues = workflowTaskInvoker.syncedWorkflowAggregateValuesInCurrentTransaction(
        connectable.workflowModuleId(),
        connectable.bpmnProcessId(),
        businessKey,
        io.vanillabp.camunda7.processservice.Camunda7ProcessService.SYNC_MODE);
    if (sharedValues.isEmpty()) {
      return;
    }
    execution
        .setVariables(
            io.vanillabp.camunda7.sync.Camunda7Variables
                .of(
                    sharedValues,
                    taskRegistry.serializationFormatFor(
                        connectable.workflowModuleId(),
                        connectable.bpmnProcessId())));

  }

  /**
   * The neutral invocation context built from a Camunda 7 execution.
   */
  static class Camunda7TaskInvocationContext implements TaskInvocationContext {

    private final Camunda7TaskConnectable connectable;

    private final DelegateExecution execution;

    private final io.vanillabp.spi.service.TaskEvent.Event taskEvent;

    /**
     * What this context asks about the engine it was built in. It asks for the version of
     * the execution's process definition and for the adapter which holds the engine. And it
     * asks whether a called process continues the business case of its caller. May be
     * <code>null</code> (tests). Then no version is reported, which matches every method.
     * And the multi-instance walk ends at a call activity which names its called process in
     * an expression.
     */
    private final Camunda7TaskRegistry taskRegistry;

    private Map<String, MultiInstanceValue> multiInstances;

    @Override
    public String getAdapterId() {

      return taskRegistry == null
          ? null
          : taskRegistry.getAdapterId();

    }

    Camunda7TaskInvocationContext(
        final Camunda7TaskConnectable connectable,
        final DelegateExecution execution,
        final Camunda7TaskRegistry taskRegistry) {

      this(connectable, execution, io.vanillabp.spi.service.TaskEvent.Event.CREATED, taskRegistry);

    }

    Camunda7TaskInvocationContext(
        final Camunda7TaskConnectable connectable,
        final DelegateExecution execution,
        final io.vanillabp.spi.service.TaskEvent.Event taskEvent,
        final Camunda7TaskRegistry taskRegistry) {

      this.connectable = connectable;
      this.execution = execution;
      this.taskEvent = taskEvent;
      this.taskRegistry = taskRegistry;

    }

    @Override
    public String getProcessVersion() {

      // resolved once per process definition id and then answered from memory. A query
      // per task execution would cost every workflow
      return taskRegistry == null
          ? null
          : taskRegistry.versionOfDefinition(execution.getProcessDefinitionId());

    }

    @Override
    public io.vanillabp.spi.service.TaskEvent.Event getTaskEvent() {

      return taskEvent;

    }

    @Override
    public String getTaskDefinition() {

      return connectable.taskDefinition();

    }

    @Override
    public String getWorkflowAggregateId() {

      return execution.getBusinessKey();

    }

    @Override
    public String getBpmnElementId() {

      // what a modeller wrote as the element's id, read out of the model while wiring.
      // The task definition next to it is the expression text. So the two are different
      // answers, and a reader of the delivery record needs this one to find the element
      return connectable.elementId();

    }

    @Override
    public String getWorkflowId() {

      // the engine's own id of the running instance. An operator types it into Cockpit.
      // A task of a called process reports the id of THAT instance, because that is the
      // workflow the task belongs to
      return execution.getProcessInstanceId();

    }

    @Override
    public String getTaskId() {

      // the execution's ID identifies the open task instance.
      // ProcessService#completeTask uses it to signal the parked execution
      return execution.getId();

    }

    @Override
    public TaskKind getTaskKind() {

      // an execution is the work the engine pushes to the application. So this context
      // is only ever built for a task. A user task of this engine travels through a
      // context of its own (Camunda7UserTaskEventListener). It names a row of
      // ACT_RU_TASK, which is not an execution ID. So nothing in the model has to be
      // read to know the kind
      return TaskKind.TASK;

    }

    @Override
    public Object getTaskParameter(
        final String name) {

      return execution.getVariableLocal(name);

    }

    @Override
    public boolean runInCurrentTransaction() {

      // the embedded engine calls handlers inside its own (job) transaction. So
      // business changes and engine state commit or roll back together. An engine on a
      // datasource of ITS OWN runs that transaction on a resource the application's
      // persistence knows nothing about. Then there is nothing to join, and VanillaBP
      // itself has to open the transaction it saves the workflow aggregate in
      return (taskRegistry == null) || !taskRegistry.engineRunsOnItsOwnDataSource();

    }

    @Override
    public String getDeliveryId() {

      // on the application's datasource a redelivery proves that nothing was committed.
      // So there is nothing to remember, and no identity is reported. On an own datasource
      // the aggregate commits before the job does. After a crash in between, the engine
      // hands the same job out again. The core has to recognize that delivery
      if ((taskRegistry == null) || !taskRegistry.engineRunsOnItsOwnDataSource()) {
        return null;
      }
      // and only the CREATED delivery has a job to itself: the async-before continuation
      // of this very task. A CANCELED notification travels with whatever job cancels the
      // scope. ONE such job cancels every task open in it. So that job would name several
      // notifications, and the core would take the second for a repetition of the first
      return taskEvent == io.vanillabp.spi.service.TaskEvent.Event.CREATED
          ? Camunda7DeliveringJob.idOnThisThread()
          : null;

    }

    @Override
    public String getActivationId() {

      // the activity instance is what the engine calls the running element instance.
      // Its id reads '<element-id>:<instance-id>'. So the second element of a
      // multi-instance activity and the next iteration of a loop each get their own.
      // Camunda 7 reports a delivery id only on an engine with a datasource of its own
      // (on the application's datasource a redelivery proves that nothing was committed).
      // It answers this one in both modes - the two are different questions
      return execution.getActivityInstanceId();

    }

    @Override
    public Map<String, MultiInstanceValue> getMultiInstances() {

      if (multiInstances == null) {
        // the registry is passed along. It answers for a call activity which names the
        // process it calls in an expression. The deployed model could say nothing about it
        multiInstances = io.vanillabp.camunda7.api.Camunda7MultiInstances.of(execution, taskRegistry);
      }
      return multiInstances;

    }

  }

}
