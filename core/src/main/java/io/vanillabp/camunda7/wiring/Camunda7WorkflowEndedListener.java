package io.vanillabp.camunda7.wiring;

import java.time.Instant;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.pvm.runtime.PvmExecutionImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.spi.service.WorkflowEnd;

/**
 * Attached as an END execution listener to the PROCESS scope of processes whose
 * workflow service declares a <code>&#64;WorkflowEnded</code> method (see
 * {@link Camunda7AsyncBpmnParseListener}), this listener tells the application that
 * a workflow ended.
 * <p>
 * It runs inside the engine's own transaction, so the application's change to the
 * workflow aggregate commits together with the end of the process instance - and
 * rolls back with it. What the engine reports decides the KIND: an execution
 * carrying a delete reason was cancelled (by the API, by a terminate end event or
 * by an interrupting event), everything else reached an end event.
 * <p>
 * Why a listener is added to the deployed model, and only where a handler exists, is decision 5 in
 * the repository's DECISIONS.md.
 */
public class Camunda7WorkflowEndedListener implements ExecutionListener {

  private static final Logger log = LoggerFactory.getLogger(Camunda7WorkflowEndedListener.class);

  private final WorkflowEndedInvoker workflowEndedInvoker;

  private final Camunda7TaskRegistry taskRegistry;

  /**
   * One listener for the whole engine, attached to the process scope of every model whose
   * application has a method for the end of a workflow.
   *
   * @param workflowEndedInvoker Where the core is told that a workflow ended
   * @param taskRegistry What the workflow module of the ended process is looked up in
   */
  public Camunda7WorkflowEndedListener(
      final WorkflowEndedInvoker workflowEndedInvoker,
      final Camunda7TaskRegistry taskRegistry) {

    this.workflowEndedInvoker = workflowEndedInvoker;
    this.taskRegistry = taskRegistry;

  }

  @Override
  public void notify(
      final DelegateExecution execution) {

    final var businessKey = execution.getProcessBusinessKey();
    if ((businessKey == null) || businessKey.isBlank()) {
      // not a VanillaBP workflow: without a business key there is no workflow
      // aggregate this end could be reported for
      return;
    }

    final var processDefinitionKey = execution.getProcessEngineServices()
        .getRepositoryService()
        .getProcessDefinition(execution.getProcessDefinitionId())
        .getKey();
    final var workflowModuleId = taskRegistry
        .resolveWorkflowModuleId(execution.getTenantId(), processDefinitionKey);
    if (workflowModuleId == null) {
      log
          .debug(
              "Camunda7: no workflow module known for process definition '{}' - the end of instance "
                  + "'{}' is not reported",
              processDefinitionKey,
              execution.getProcessInstanceId());
      return;
    }
    final var bpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, processDefinitionKey);

    final var deleteReason = execution instanceof PvmExecutionImpl pvmExecution
        ? pvmExecution.getDeleteReason()
        : null;
    final var kind = deleteReason == null
        ? WorkflowEnd.Kind.COMPLETED
        : WorkflowEnd.Kind.CANCELED;
    // the activity the process instance ends at: the end event for a regular end,
    // nothing where the instance was cancelled
    final var endEventId = kind == WorkflowEnd.Kind.COMPLETED
        ? execution.getCurrentActivityId()
        : null;
    final var processVersion = taskRegistry.versionOfDefinition(execution.getProcessDefinitionId());

    workflowEndedInvoker
        .workflowEnded(
            workflowModuleId,
            bpmnProcessId,
            new WorkflowEndedContext() {

              @Override
              public String getAdapterId() {
                return taskRegistry.getAdapterId();
              }

              @Override
              public String getWorkflowAggregateId() {
                return businessKey;
              }

              @Override
              public WorkflowEnd.Kind getKind() {
                return kind;
              }

              @Override
              public Instant getEndTime() {
                // the engine does not hand the listener an end time; this is the
                // moment the instance ends, which is the same transaction
                return Instant.now();
              }

              @Override
              public String getEndEventId() {
                return endEventId;
              }

              @Override
              public String getProcessVersion() {
                return processVersion;
              }

              @Override
              public boolean runInCurrentTransaction() {
                // an embedded engine: the notification belongs into the transaction
                // which ends the workflow. An engine on a datasource of its own ends it
                // where the application's persistence cannot join, so VanillaBP opens
                // its own transaction and the notification is at-least-once like every
                // other delivery of that mode
                return !taskRegistry.engineRunsOnItsOwnDataSource();
              }

            });

  }

}
