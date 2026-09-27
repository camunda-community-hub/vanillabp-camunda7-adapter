package io.vanillabp.camunda7.wiring;

import java.util.HashMap;
import java.util.Map;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
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
 * One thing stays invisible, with open eyes: a key somebody chose which happens to be the
 * id of an existing workflow aggregate attaches that instance to it without a word. Nothing
 * on Camunda 7 can catch that, because catching it needs two values naming the instance and
 * Camunda 7 keeps one.
 * <p>
 * Why a listener is added to the deployed model at all is decision 5 in the repository's
 * DECISIONS.md.
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
   *          workflow, so it can build the aggregate
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

    final var businessKey = (execution.getProcessBusinessKey() == null) || execution.getProcessBusinessKey().isBlank()
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

    final var signalName = taskRegistry
        .signalNameOfStartEvent(workflowModuleId, processDefinitionKey, execution.getCurrentActivityId());
    final var processVersion = taskRegistry.versionOfDefinition(execution.getProcessDefinitionId());
    final var result = bpmsInitiatedStartInvoker
        .startWorkflowByBpms(
            workflowModuleId,
            bpmnProcessId,
            contextOf(execution, signalName, processVersion, businessKey));

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
        // the name this instance already goes by, which on Camunda 7 is a workflow
        // aggregate's id and nothing else. The core reads it and decides from it what
        // this start is
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
