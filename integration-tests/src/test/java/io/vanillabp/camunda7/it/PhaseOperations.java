package io.vanillabp.camunda7.it;

import java.util.Map;

import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.workflowstart.WorkflowStartReport;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;

/**
 * Runs one phase of one operation against an adapter, the way VanillaBP's core does it:
 * through the handler the adapter contributes for that operation.
 * <p>
 * A test which wants to see what the engine makes of a phase-two dispatch has no outbox
 * to go through, so it asks the adapter directly - and this is what asking directly
 * looks like now that an adapter answers a map of handlers instead of a method per
 * operation and phase.
 */
public final class PhaseOperations {

  private PhaseOperations() {
  }

  /**
   * @param <A> The workflow-aggregate type
   * @param adapter The adapter to ask
   * @param operation The operation to run
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param aggregatePersistence The aggregate's persistence, or <code>null</code> where
   *          the operation does not read the aggregate
   * @param workflowAggregate The workflow aggregate
   * @param args The operation's arguments
   */
  public static <A> void phaseOne(
      final MigratableProcessService<A> adapter,
      final PhaseOperation operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final Map<String, String> args) {

    adapter
        .phaseOperations()
        .get(operation)
        .phaseOne(
            new PhaseOneRequest<>(
                workflowModuleId, bpmnProcessId, aggregatePersistence, workflowAggregate, args));

  }

  /**
   * @param <A> The workflow-aggregate type
   * @param adapter The adapter to ask
   * @param operation The operation to run
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param aggregatePersistence The aggregate's persistence, or <code>null</code> where
   *          the operation does not read the aggregate
   * @param workflowAggregateId The workflow aggregate's ID
   * @param args The operation's arguments
   */
  public static <A> void phaseTwo(
      final MigratableProcessService<A> adapter,
      final PhaseOperation operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final Map<String, String> args) {

    adapter
        .phaseOperations()
        .get(operation)
        .phaseTwo(
            new PhaseTwoRequest<>(
                workflowModuleId, bpmnProcessId, aggregatePersistence, workflowAggregateId, args));

  }

  /**
   * Phase two of a start, with somebody listening to which workflow the adapter says it
   * created, the way the core listens while it dispatches a start.
   *
   * @param <A> The workflow-aggregate type
   * @param adapter The adapter to ask
   * @param operation The start operation to run
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param workflowAggregateId The workflow aggregate's ID
   * @param args The operation's arguments
   * @param startedWorkflows Where the adapter's report of the started workflow goes
   */
  public static <A> void phaseTwoOfAStart(
      final MigratableProcessService<A> adapter,
      final PhaseOperation operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Object workflowAggregateId,
      final Map<String, String> args,
      final WorkflowStartReport startedWorkflows) {

    adapter
        .phaseOperations()
        .get(operation)
        .phaseTwo(
            new PhaseTwoRequest<>(
                workflowModuleId, bpmnProcessId, null, workflowAggregateId, args, startedWorkflows));

  }

  /**
   * Listens to what phase two of a start reports, id and version together, the way the core
   * writes both into the row about the start.
   */
  public static final class ReportedStarts implements WorkflowStartReport {

    private final java.util.List<String> reported = new java.util.ArrayList<>();

    @Override
    public void startedWorkflow(
        final String workflowId) {

      reported.add(workflowId);

    }

    @Override
    public void startedWorkflow(
        final String workflowId,
        final String processVersion) {

      reported.add("%s on version %s".formatted(workflowId, processVersion));

    }

    /**
     * @return Every report, an id with its version written as "id on version n" and an id
     *         reported without a version alone
     */
    public java.util.List<String> reported() {

      return reported;

    }

  }

}
