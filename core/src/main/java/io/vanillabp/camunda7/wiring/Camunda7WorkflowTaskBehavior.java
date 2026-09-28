package io.vanillabp.camunda7.wiring;

import java.util.Map;

import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.impl.bpmn.behavior.AbstractBpmnActivityBehavior;
import org.camunda.bpm.engine.impl.pvm.delegate.ActivityExecution;

import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;

/**
 * Executes a <code>&#64;WorkflowTask</code> method for one BPMN task, dispatched
 * through the core's {@link WorkflowTaskInvoker}: the invocation context is built
 * from the {@link DelegateExecution} (business key = serialized aggregate ID,
 * local variables for <code>&#64;TaskParam</code>, multi-instance context from the
 * execution hierarchy) and the handler runs INSIDE the engine's transaction
 * ({@link TaskInvocationContext#runInCurrentTransaction()}): business changes and
 * engine state commit or roll back together.
 * <p>
 * That holds while the engine shares the application's datasource. An engine given one of
 * its own commits on a resource the application's persistence cannot join, so VanillaBP
 * opens the transaction around the handler itself, the aggregate commits before the job
 * does, and the job the engine hands out again after a crash in between is a REPEATED
 * delivery. Which is why an engine in that mode names its deliveries
 * ({@link TaskInvocationContext#getDeliveryId()} - the id of the job at hand) and the core
 * answers the repetition from what it recorded.
 * <p>
 * Outcome mapping:
 * <ul>
 * <li>COMPLETED - the activity is left (task completes);</li>
 * <li>COMPLETION_PENDING (<code>&#64;TaskId</code> methods) - the activity is NOT
 * left: the execution stays at the task until it is completed asynchronously via
 * <code>ProcessService#completeTask</code> (which signals the execution);</li>
 * <li>BPMN_ERROR ({@code TaskException}) - a {@link BpmnError} with the
 * exception's error code is thrown for error-boundary routing; the aggregate
 * changes were saved and commit with the engine's transaction;</li>
 * <li>any other exception - propagates: the engine rolls back the job transaction
 * (business changes included) and applies its retry semantics.</li>
 * </ul>
 * Used for <code>camunda:delegateExpression</code> tasks (as an
 * {@link AbstractBpmnActivityBehavior}, so the activity can stay open) and for
 * <code>camunda:expression</code> tasks (executed inline by the
 * {@link Camunda7TaskELResolver}; staying open is impossible there, so
 * <code>&#64;TaskId</code> methods require a delegate expression).
 * <p>
 * Why the handler runs in the engine's own job transaction, and why the engine's retry is the
 * recovery this adapter relies on, is decision 6 in the repository's DECISIONS.md.
 */
public class Camunda7WorkflowTaskBehavior extends AbstractBpmnActivityBehavior {

  private final Camunda7TaskConnectable connectable;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * Translates the error code of a {@code TaskException} into what the engine knows
   * (the model's error codes are prefixed too, see decision 3 in the repository's
   * DECISIONS.md). May be <code>null</code>.
   */
  private final io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport scoping;

  private final String adapterId;

  /**
   * Answers the version of the process definition an execution runs on.
   * May be <code>null</code> (tests): no version is reported then.
   */
  private final Camunda7TaskRegistry taskRegistry;

  /**
   * Convenience constructor for a test: no scoping, so the identifiers reach the engine as
   * the application wrote them, and no version is reported with a delivery.
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
   * Composed from the workflow module of the process this task belongs to, which is the
   * module the catcher belongs to as well: this engine looks for the called process of a call
   * activity in the tenant of the calling instance, and a static called element carries the
   * calling module's prefix under {@code use-prefix}, so a call activity stays inside its
   * module. The one model which leaves it names another tenant on the call activity, and the
   * deployment warns about that one, because the code raised there would carry the other
   * module's prefix and this code is not bent to fit.
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
      // @TaskId method: the task stays open, completion arrives via
      // ProcessService#completeTask which signals this execution
      return;
    }
    leave(execution);

  }

  /**
   * The signal name carrying a cancellation
   * ({@code ProcessService#cancelTask}): the signal data is the BPMN error code to
   * propagate.
   */
  public static final String SIGNAL_CANCEL = "vanillabp:cancel";

  @Override
  public void signal(
      final ActivityExecution execution,
      final String signalName,
      final Object signalData) throws Exception {

    // asynchronous completion/cancellation (ProcessService#completeTask/cancelTask
    // signals the parked execution - see Camunda7ProcessService)
    if (SIGNAL_CANCEL.equals(signalName)) {
      // cancelTask: route the workflow through an error boundary event; thrown
      // BpmnErrors are not translated automatically on the signal path, so the
      // error is propagated explicitly
      org.camunda.bpm.engine.impl.bpmn.helper.BpmnExceptionHandler.propagateBpmnError(
          new BpmnError(String.valueOf(signalData)),
          execution);
      return;
    }
    leave(execution);

  }

  /**
   * Runs the handler for the given execution and maps a
   * {@code TaskException} outcome to a {@link BpmnError}. Shared by the
   * delegate-expression path (this behavior) and the expression path (the EL
   * resolver).
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
    // What the handler computed has to reach the engine BEFORE it evaluates
    // what comes next - a gateway right behind this task would otherwise decide on the
    // values of the last ProcessService call. Written here, inside the engine's
    // transaction, so the variables commit with the aggregate and with the token
    writeSharedValues(execution);
    if (outcome.kind() == WorkflowTaskOutcome.Kind.BPMN_ERROR) {
      // error-boundary routing; the aggregate changes were saved and commit
      // with the engine's transaction (the V1 contract). The values are written above,
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
   * The values are read in the CALLER's transaction, which is the engine's: the handler
   * just changed the aggregate there, and reading in a new transaction would either see
   * the state before the handler or wait for the row this transaction holds. The read
   * never throws, and where nothing is shared nothing is written.
   *
   * @param execution The execution of the task just processed
   */
  private void writeSharedValues(
      final DelegateExecution execution) {

    final var businessKey = execution.getProcessBusinessKey();
    if (businessKey == null) {
      // no aggregate identity at hand (a process started outside VanillaBP): there is
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
     * Answers the version of the execution's process definition. May be
     * <code>null</code>: no version is reported then, which matches every method.
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

      // resolved once per process definition id and then answered from memory - a
      // query per task execution would be paid by every workflow
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
      // The task definition next to it is the expression text, so the two are different
      // answers and a reader of the delivery record needs this one to find the element
      return connectable.elementId();

    }

    @Override
    public String getWorkflowId() {

      // the engine's own id of the running instance - what an operator types into
      // Cockpit. A task of a called process reports the id of THAT instance, which is
      // the workflow the task belongs to
      return execution.getProcessInstanceId();

    }

    @Override
    public String getTaskId() {

      // the execution's ID identifies the open task instance - used by
      // ProcessService#completeTask to signal the parked execution
      return execution.getId();

    }

    @Override
    public Object getTaskParameter(
        final String name) {

      return execution.getVariableLocal(name);

    }

    @Override
    public boolean runInCurrentTransaction() {

      // the embedded engine invokes handlers inside its own (job) transaction -
      // business changes and engine state commit or roll back together. An engine on a
      // datasource of ITS OWN runs that transaction on a resource the application's
      // persistence knows nothing about, so there is nothing to join and VanillaBP has
      // to open the transaction it saves the workflow aggregate in itself
      return (taskRegistry == null) || !taskRegistry.engineRunsOnItsOwnDataSource();

    }

    @Override
    public String getDeliveryId() {

      // On the application's datasource a redelivery proves that nothing was committed,
      // so there is nothing to remember and no identity is reported. On an own datasource
      // the aggregate commits before the job does, and the engine hands the same job out
      // again after a crash in between - which is the delivery the core has to recognize
      if ((taskRegistry == null) || !taskRegistry.engineRunsOnItsOwnDataSource()) {
        return null;
      }
      // and only the CREATED delivery has a job to itself: the async-before continuation
      // of this very task. A CANCELED notification travels with whatever job cancels the
      // scope, and ONE such job cancels every task open in it, so that job would name
      // several notifications and the core would take the second for a repetition of the
      // first
      return taskEvent == io.vanillabp.spi.service.TaskEvent.Event.CREATED
          ? Camunda7DeliveringJob.idOnThisThread()
          : null;

    }

    @Override
    public String getActivationId() {

      // the activity instance is what the engine calls the running element instance:
      // its id reads '<element-id>:<instance-id>', so the second element of a
      // multi-instance activity and the next iteration of a loop each get their own.
      // Camunda 7 reports no delivery id at all (a redelivery here proves that nothing
      // was committed) and still answers this one - the two are different questions
      return execution.getActivityInstanceId();

    }

    @Override
    public Map<String, MultiInstanceValue> getMultiInstances() {

      if (multiInstances == null) {
        multiInstances = io.vanillabp.camunda7.api.Camunda7MultiInstances.of(execution);
      }
      return multiInstances;

    }

  }

}
