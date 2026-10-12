package io.vanillabp.camunda7.wiring;

import java.util.HashMap;
import java.util.Map;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * This listener sits on EVERY start event of the process itself (see
 * {@link Camunda7AsyncBpmnParseListener}). It reports the start of a workflow to the core. And
 * it writes the name of that workflow into the instance's BUSINESS KEY. Everything else in this
 * adapter finds a workflow again by that key.
 * <p>
 * It runs inside the engine's own transaction (the timer job's or the command's). On the
 * application's datasource, aggregate and process instance commit together. An engine on a
 * datasource of its own cannot share that transaction with the application's persistence. Then
 * VanillaBP opens a transaction of its own for the aggregate, and the two commit one after the
 * other.
 * <p>
 * What a start means is not read from the kind of its start event. On Camunda 7 anybody with
 * access to the engine can start any of these processes, with a business key or without one.
 * So the kind of the event says nothing about who started the workflow. The state of the
 * workflow does, and the core reads it:
 * <ul>
 * <li>An instance with a key whose workflow aggregate exists is the application's own start.
 * Or it is the second delivery of this listener.</li>
 * <li>An instance with no key was started past VanillaBP. The application builds its
 * aggregate.</li>
 * <li>An instance with a key which no aggregate has is refused. Only VanillaBP names a
 * workflow.</li>
 * </ul>
 * All of that is decision 28 in the repository's DECISIONS.md. It replaces decision 24 there.
 * <p>
 * A called process is the one start which brings no key and still is nobody's foreign start.
 * This engine gives a called process no business key. So {@link Camunda7CallActivities} writes
 * the hand-over of the key into the model of a call activity whose called process works on the
 * aggregate of its caller. But a call activity may name the process to call in an EXPRESSION.
 * Then nobody knows at deployment which process will be called, and there is nothing to write
 * it onto. Such an instance arrives here without a key, and this listener answers the question
 * the model could not. It finds the call activity which started the instance in the execution
 * tree. Then it asks the core whether the two processes work on one workflow aggregate. Where
 * they do, the instance gets the caller's name. So the same model behaves on this engine as on
 * a BPMS which copies the caller's values by itself.
 * <p>
 * A process the application declares only as a secondary process is no workflow of its own.
 * The core says so through
 * {@link BpmsInitiatedStartInvoker#startsAWorkflowOfItsOwn(String, String)}. For such a process
 * this listener only hands the caller's name on and reports nothing. It stays on the start
 * event, because a call activity which names its process in an expression leaves the name to
 * this listener.
 * <p>
 * One thing stays invisible, and we know it. Somebody may choose a key which happens to be the
 * id of an existing workflow aggregate. Then that instance is attached to the aggregate without
 * a word. Nothing on Camunda 7 can catch that. Catching it needs two values which name the
 * instance, and Camunda 7 keeps one.
 * <p>
 * Decision 5 in the repository's DECISIONS.md says why a listener is added to the deployed
 * model at all.
 * <p>
 * This listener costs the deployed model nothing. It is attached to the element the engine
 * PARSED. So the bytes in the engine stay the ones the modeller wrote, and no job is created
 * for it. An adapter which writes its listener into the model before deploying it pays per
 * start event and can count the difference. Here there is nothing to count. The repository's
 * README says so where it describes the start.
 */
public class Camunda7BpmsInitiatedStartListener implements ExecutionListener {

  private static final Logger log = LoggerFactory
      .getLogger(Camunda7BpmsInitiatedStartListener.class);

  private final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker;

  private final Camunda7TaskRegistry taskRegistry;

  /**
   * Whether a workflow service of this application serves the given workflow module and BPMN
   * process. The engine holds the definitions of whatever was deployed against its database,
   * this application's unclaimed processes included. A start of one of those is none of
   * VanillaBP's business. The core has no workflow service to answer for it.
   */
  private final java.util.function.BiPredicate<String, String> servedByThisApplication;

  /**
   * Which kind of start event this listener sits on, decided at parse time. It is reported to
   * the application and decides nothing here.
   */
  private final BpmsStartTrigger.Kind kind;

  /**
   * One listener per start event of the process, built while the model is parsed.
   *
   * @param bpmsInitiatedStartInvoker Where the core is told that the engine started a
   *          workflow, so it can ask the application for the aggregate
   * @param taskRegistry What the workflow module of the reported process is looked up in
   * @param kind Which trigger this start event carries, read while the model was parsed
   */
  public Camunda7BpmsInitiatedStartListener(
      final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker,
      final Camunda7TaskRegistry taskRegistry,
      final BpmsStartTrigger.Kind kind) {

    this(bpmsInitiatedStartInvoker, taskRegistry, kind, (
        workflowModuleId,
        bpmnProcessId) -> true);

  }

  /**
   * The same listener, told which processes this application serves.
   *
   * @param bpmsInitiatedStartInvoker Where the core is told that a workflow started
   * @param taskRegistry What the workflow module of the reported process is looked up in
   * @param kind Which trigger this start event carries, read while the model was parsed
   * @param servedByThisApplication Whether a workflow service serves that process
   */
  public Camunda7BpmsInitiatedStartListener(
      final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker,
      final Camunda7TaskRegistry taskRegistry,
      final BpmsStartTrigger.Kind kind,
      final java.util.function.BiPredicate<String, String> servedByThisApplication) {

    this.bpmsInitiatedStartInvoker = bpmsInitiatedStartInvoker;
    this.taskRegistry = taskRegistry;
    this.kind = kind;
    this.servedByThisApplication = servedByThisApplication;

  }

  @Override
  public void notify(
      final DelegateExecution execution) {

    final var ownBusinessKey = (execution.getProcessBusinessKey() == null) || execution
        .getProcessBusinessKey()
        .isBlank()
            ? null
            : execution.getProcessBusinessKey();

    final var processDefinitionKey = execution.getProcessEngineServices()
        .getRepositoryService()
        .getProcessDefinition(execution.getProcessDefinitionId())
        .getKey();
    final var workflowModuleId = taskRegistry
        .resolveWorkflowModuleId(execution.getTenantId(), processDefinitionKey);
    if (workflowModuleId == null) {
      log
          .debug(
              "Camunda7: no workflow module known for process definition '{}' - the start of instance "
                  + "'{}' is not a VanillaBP workflow",
              processDefinitionKey,
              execution.getProcessInstanceId());
      return;
    }
    final var bpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, processDefinitionKey);
    if (!servedByThisApplication.test(workflowModuleId, bpmnProcessId)) {
      log
          .debug(
              "Camunda7: no workflow service of this application serves BPMN process '{}' of "
                  + "workflow module '{}' - the start of instance '{}' is none of VanillaBP's business",
              bpmnProcessId,
              workflowModuleId,
              execution.getProcessInstanceId());
      return;
    }

    // the name of the calling workflow, where this instance continues its business case. A
    // call activity which names its called process in an expression cannot say it in the model
    final var inheritedBusinessKey = ownBusinessKey == null
        ? theNameOfTheCallingWorkflow(execution)
        : null;

    if (!bpmsInitiatedStartInvoker.startsAWorkflowOfItsOwn(workflowModuleId, bpmnProcessId)) {
      // a called process is a step of the workflow which called it, so its start is not
      // reported. Only the name is left to do. The called instance needs it for its tasks. See
      // decision 44 in the repository's DECISIONS.md
      if (inheritedBusinessKey != null) {
        ((PvmExecutionImpl) execution).setProcessBusinessKey(inheritedBusinessKey);
      }
      log
          .debug(
              "Camunda7: '{}' of workflow module '{}' (instance '{}') is a called process and goes by "
                  + "the name '{}' - its start is a step of the calling workflow and is not reported",
              bpmnProcessId,
              workflowModuleId,
              execution.getProcessInstanceId(),
              ownBusinessKey != null
                  ? ownBusinessKey
                  : inheritedBusinessKey);
      return;
    }
    final var businessKey = ownBusinessKey != null
        ? ownBusinessKey
        : inheritedBusinessKey;

    final var signalName = taskRegistry
        .signalNameOfStartEvent(workflowModuleId, processDefinitionKey, execution.getCurrentActivityId());
    final var processVersion = taskRegistry.versionOfDefinition(execution.getProcessDefinitionId());
    final var result = bpmsInitiatedStartInvoker
        .startWorkflowByBpms(
            workflowModuleId,
            bpmnProcessId,
            contextOf(execution, signalName, processVersion, businessKey));

    if (inheritedBusinessKey != null) {
      // the called process continues the business case of its caller, so it goes by the
      // same name. It is written here because the deployed model had nothing to carry it
      ((PvmExecutionImpl) execution).setProcessBusinessKey(inheritedBusinessKey);
      log
          .debug(
              "Camunda7: '{}' of workflow module '{}' (instance '{}') was called by a call activity "
                  + "naming it in an expression and works on the workflow aggregate of its caller, so "
                  + "it goes by the caller's name '{}'",
              bpmnProcessId,
              workflowModuleId,
              execution.getProcessInstanceId(),
              inheritedBusinessKey);
      return;
    }

    if (businessKey != null) {
      // the instance already carries the name of a workflow aggregate which exists. The
      // core refuses every other case. So there is nothing to write and nothing to say
      log
          .debug(
              "Camunda7: the start of '{}' of workflow module '{}' at start event '{}' names workflow "
                  + "aggregate '{}', which exists - this workflow is already ours",
              bpmnProcessId,
              workflowModuleId,
              execution.getCurrentActivityId(),
              result.workflowAggregateId());
      return;
    }

    // nobody named this workflow, so the application just did. The name becomes this
    // adapter's handle on it (the business key). It is set in the same transaction which
    // created the instance
    ((PvmExecutionImpl) execution).setProcessBusinessKey(result.workflowAggregateId());

    // worth a line of its own. Nobody asked VanillaBP for this workflow, and the
    // application learns about it only from here on. INFO and not WARN, because the
    // workflow is in order once it has its aggregate. And it comes once per workflow,
    // not once per delivery
    log
        .info(
            "Camunda7: '{}' of workflow module '{}' was started past VanillaBP (instance '{}', start "
                + "event '{}') - the application named it '{}' and built its workflow aggregate",
            bpmnProcessId,
            workflowModuleId,
            execution.getProcessInstanceId(),
            execution.getCurrentActivityId(),
            result.workflowAggregateId());

  }

  /**
   * The name of the workflow whose call activity started this instance, where the called
   * process works on the workflow aggregate of that workflow.
   * <p>
   * {@link Camunda7CallActivities} asks the same question while a model is prepared. Here it
   * is asked through the same method. So the two ways of inheriting from a caller read one
   * answer. It is asked again because a call activity may name the process to call in an
   * expression. Such a call activity names it while the workflow runs, not while the model is
   * deployed.
   *
   * @param execution The execution a start event of the called process stands in
   * @return The caller's business key, or <code>null</code> where this instance was not
   *         called, where the caller goes by no name either, or where the two processes
   *         have a workflow aggregate each
   */
  private String theNameOfTheCallingWorkflow(
      final DelegateExecution execution) {

    final var callingExecution = theExecutionWhichCalledThisOne(execution);
    if (callingExecution == null) {
      return null;
    }
    final var callersName = callingExecution.getProcessBusinessKey();
    if ((callersName == null) || callersName.isBlank()) {
      // the caller goes by no name either. So there is nothing to inherit, and this start
      // is reported as the start it is
      return null;
    }
    return Camunda7CallActivities.continuesTheCallersWorkflowAggregate(execution, callingExecution, taskRegistry)
        ? callersName
        : null;

  }

  /**
   * The execution of the call activity which started this instance, or <code>null</code>
   * where no call activity did. The engine keeps it on the process instance, not on the
   * execution a start event stands in. So this walks up the scopes of this process first.
   */
  private static DelegateExecution theExecutionWhichCalledThisOne(
      final DelegateExecution execution) {

    var current = execution;
    while (current.getParentId() != null) {
      current = ((ExecutionEntity) current).getParent();
    }
    return current.getSuperExecution();

  }

  private BpmsInitiatedStartContext contextOf(
      final DelegateExecution execution,
      final String signalName,
      final String processVersion,
      final String businessKey) {

    // what the model set before the start event completed. That is expressions, input
    // mappings, and for a signal the payload the broadcast carried
    final Map<String, Object> variables = new HashMap<>(execution.getVariables());
    final var startEventId = execution.getCurrentActivityId();

    return new BpmsInitiatedStartContext() {

      @Override
      public String getAdapterId() {
        return taskRegistry.getAdapterId();
      }

      @Override
      public String getStartEventId() {
        return startEventId;
      }

      @Override
      public BpmsStartTrigger.Kind getKind() {
        return kind;
      }

      @Override
      public String getProcessVersion() {
        return processVersion;
      }

      @Override
      public String getBusinessKey() {
        // the name this instance goes by. On Camunda 7 that is a workflow aggregate's id
        // and nothing else. It is the one the instance arrived with. Or it is the one its
        // caller goes by, where the called process continues that business case. The core
        // reads it and decides from it what this start is
        return businessKey;
      }

      @Override
      public String getSignalName() {
        return signalName;
      }

      @Override
      public Map<String, Object> getVariables() {
        return variables;
      }

      @Override
      public String getNativeInstanceId() {
        return execution.getProcessInstanceId();
      }

      @Override
      public boolean runInCurrentTransaction() {
        // an embedded engine shares the application's transaction. Then the aggregate
        // has to be written in the transaction which creates the instance. An engine on
        // a datasource of its own runs that transaction on a resource the application's
        // persistence cannot join. So VanillaBP opens its own, and the two commit one
        // after the other
        return !taskRegistry.engineRunsOnItsOwnDataSource();
      }

    };

  }

}
