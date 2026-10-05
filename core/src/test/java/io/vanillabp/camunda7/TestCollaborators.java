package io.vanillabp.camunda7;

import static org.mockito.Mockito.mock;

import io.vanillabp.integration.adapter.spi.AdapterCollaborators;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.PreCommitRegistrar;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * What the platform hands the adapter, for tests which need the adapter and not the
 * registration: the mandatory collaborators are mocks nobody calls unless the test says
 * so, and the two optional ones are supplied per test.
 */
public final class TestCollaborators {

  private TestCollaborators() {
    // static helper
  }

  /**
   * The default of this adapter: identifiers are deployed as modelled. A bare mock would
   * answer null here, which is not a value the adapter has to cope with - the platform
   * always answers with one of the three modes.
   */
  private static NameClashAvoidanceSupport scopingWithoutNameClashAvoidance() {

    // every identifier passes through unchanged, and the mode is the one that isolates by
    // tenant: a bare mock answers null to both, and null is not a value the platform ever
    // gives an adapter
    final var scoping = mock(NameClashAvoidanceSupport.class, invocation -> {
      final var method = invocation.getMethod();
      if ("modeFor".equals(method.getName())) {
        // the tenant isolation of version 1, which is what these tests deploy into
        return io.vanillabp.integration.adapter.spi.NameClashAvoidance.BY_ADAPTER;
      }
      if (method.getReturnType() == String.class) {
        // every one of them ends with the adapter id and carries the identifier right
        // before it - scopedTaskDefinition has four parameters, the others three
        return invocation.getArgument(invocation.getArguments().length - 2);
      }
      return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    return scoping;

  }

  public static AdapterCollaborators.Builder builder() {

    return AdapterCollaborators
        .forAdapter("c7")
        .workflowTaskWiring(mock(WorkflowTaskWiring.class))
        .workflowTaskInvoker(mock(WorkflowTaskInvoker.class))
        .scoping(scopingWithoutNameClashAvoidance())
        .workflowAggregateSync(mock(WorkflowAggregateSync.class))
        .preCommitRegistrar(mock(PreCommitRegistrar.class))
        .workflowEndedInvoker(mock(WorkflowEndedInvoker.class))
        .bpmsInitiatedStartInvoker(mock(BpmsInitiatedStartInvoker.class));

  }

  /**
   * @return A complete set, with every collaborator a mock
   */
  public static AdapterCollaborators complete() {

    return builder().build();

  }

  /**
   * @param workflowEndedInvoker The core's registry of end handlers this test wants the
   *                            adapter to see
   * @return A set carrying it
   */
  public static AdapterCollaborators reportingEndsTo(
      final WorkflowEndedInvoker workflowEndedInvoker) {

    return builder().workflowEndedInvoker(workflowEndedInvoker).build();

  }

}
