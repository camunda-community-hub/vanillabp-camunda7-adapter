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
 * Attached to EVERY start event the process itself holds (see
 * {@link Camunda7AsyncBpmnParseListener}), this listener reports the start of a workflow to
 * the core and writes the name that workflow gets into the instance's BUSINESS KEY, which
 * is how everything else in this adapter finds a workflow again.
 * <p>
 * It runs inside the engine's own transaction (the timer job's, respectively the
 * command's), so aggregate and process instance commit together.
 * <p>
 * What a start means is not read from the kind of its start event. On Camunda 7 anybody
 * with access to the engine can start any of these processes, with a business key or
 * without one, so the kind of the event says nothing about who started the workflow. The
 * state of the workflow does, and the core reads it: an instance carrying a key whose
 * workflow aggregate exists is the application's own start (or the second delivery of this
 * very listener), an instance carrying no key was started past VanillaBP and its aggregate
 * is built by the application, and an instance carrying a key nothing carries is refused,
 * because VanillaBP names a workflow and nobody else. All of that is decision 28 in the
 * repository's DECISIONS.md, which supersedes decision 24 there.
 * <p>
 * A called process is the one start which brings no key and is nobody's foreign start. This
 * engine hands a called process no business key, so
 * {@link Camunda7CallActivities} writes the propagation into the model of a call activity
 * whose called process works on the aggregate of its caller. A call activity which names
 * the process to call in an EXPRESSION leaves nothing to write it onto, because nobody
 * knows while the model is deployed which process will be called. Such an instance arrives
 * here without a key, and this listener answers the question the model could not. It finds
 * the call activity which started the instance in the execution tree and asks the core
 * whether the two processes work on one workflow aggregate. Where they do, the instance is
 * given the caller's name. The same model then behaves on this engine the way it behaves on
 * a BPMS which copies the caller's values by itself.
 * <p>
 * One thing stays invisible, with open eyes: a key somebody chose which happens to be the
 * id of an existing workflow aggregate attaches that instance to it without a word. Nothing
 * on Camunda 7 can catch that, because catching it needs two values naming the instance and
 * Camunda 7 keeps one.
 * <p>
 * Why a listener is added to the deployed model at all is decision 5 in the repository's
 * DECISIONS.md.
 * <p>
 * What this listener costs the deployed model is nothing. It is attached to the element the
 * engine PARSED, so the bytes in the engine stay the ones the modeller wrote and no job is
 * created for it. An adapter which writes its listener into the model before deploying it
 * pays per start event and can count the difference; there is nothing here to count, which
 * the repository's README says where the start is described.
 */
public class Camunda7BpmsInitiatedStartListener implements ExecutionListener {

  private static final Logger log = LoggerFactory
      .getLogger(Camunda7BpmsInitiatedStartListener.class);

  private final BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker;

  private final Camunda7TaskRegistry taskRegistry;

  /**
   * Whether a workflow service of this application serves the given (workflow module, BPMN
   * process). The engine holds the definitions of whatever was deployed against its
   * database, this application's unclaimed processes included, and a start of one of those
   * is none of VanillaBP's business - the core has no workflow service to answer for it.
   */
  private final java.util.function.BiPredicate<String, String> servedByThisApplication;

  /**
   * Which kind of start event this listener sits on, decided at parse time. It is reported
   * to the application and decides nothing here.
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

    // the name of the calling workflow, where this instance continues its business case.
    // The model of a call activity naming its called process in an expression cannot say it
    final var inheritedBusinessKey = ownBusinessKey == null
        ? theNameOfTheCallingWorkflow(execution)
        : null;
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
      // same name - written here because the deployed model had nothing to carry it
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
      // the instance already carries the name of a workflow aggregate which exists - the
      // core refuses every other case, so there is nothing to write and nothing to say
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

    // nobody named this workflow, so the application just did, and the name becomes this
    // adapter's handle on it (the business key) - set within the same transaction which
    // created the instance
    ((PvmExecutionImpl) execution).setProcessBusinessKey(result.workflowAggregateId());

    // worth a line of its own: nobody asked VanillaBP for this workflow, and the
    // application learns about it from here on. INFO rather than WARN because the
    // workflow is in order once it has its aggregate, and once per workflow rather
    // than once per delivery
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
   * The question is the one {@link Camunda7CallActivities} asks while a model is prepared,
   * and it is asked through the same method here, so the two mechanisms which inherit from
   * a caller read one answer. It is asked again because a call activity naming the process
   * to call in an expression names it while the workflow runs and not while the model is
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
      // the caller goes by no name either, so there is nothing to inherit and this start
      // is reported as the start it is
      return null;
    }
    return Camunda7CallActivities.continuesTheCallersWorkflowAggregate(execution, callingExecution, taskRegistry)
        ? callersName
        : null;

  }

  /**
   * The execution of the call activity which started this instance, or <code>null</code>
   * where no call activity did. The engine keeps it on the process instance rather than on
   * the execution a start event stands in, so the scopes of this process are walked up
   * first.
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

    // what the model set before the start event completed: expressions, input
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
        // the name this instance goes by, which on Camunda 7 is a workflow aggregate's id
        // and nothing else: the one it arrived with, or the one its caller goes by where
        // the called process continues that business case. The core reads it and decides
        // from it what this start is
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
        // an embedded engine sharing the application's transaction: the aggregate
        // has to be written in the transaction which creates the instance. An engine on
        // a datasource of its own runs that transaction on a resource the application's
        // persistence cannot join, so VanillaBP opens its own and the two commit one
        // after the other
        return !taskRegistry.engineRunsOnItsOwnDataSource();
      }

    };

  }

}
