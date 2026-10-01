package io.vanillabp.camunda7.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a task of a CALLED process is told about the iteration it runs in.
 * <p>
 * The walk leaves the called process through the call activity which started it, so the
 * models of these tests are split over files the way an application splits them: one file
 * per process which is called. Both halves of that sentence are measured here, because
 * one of them used to be wrong and the other one was never asked.
 * <p>
 * Whether the walk may leave is a question about the workflow aggregate, and the answer
 * comes from two places: the note the deployment wrote onto a call activity whose called
 * process the model spells out, and the core itself for one which names that process in an
 * expression. The cases of both are here, and so is the case of a caller which cannot reach
 * the core.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7MultiInstancesAcrossCallActivitiesTest {

  private static void assertScope(
      final Map<String, MultiInstanceValue> scopes,
      final String elementId,
      final Object element,
      final int total) {

    final var scope = scopes.get(elementId);
    assertEquals(element, scope == null ? null : scope.element(), "the element of scope '%s'".formatted(elementId));
    assertEquals(0, scope.index(), "the first iteration of scope '%s' is index zero".formatted(elementId));
    assertEquals(total, scope.total(), "the total of scope '%s'".formatted(elementId));

  }

  @Test
  @DisplayName("A task of a called process reports the iteration its call activity sits in")
  public void aTaskOfACalledProcessReportsTheIterationOfItsCaller() {

    try (var engine = AnEngineRunningCalledProcesses
        .started(
            "mi-called-inside-a-subprocess",
            "MiSubprocessCaller",
            Map.of("orders", AnEngineRunningCalledProcesses.ORDERS))) {

      final var scopes = Camunda7MultiInstances
          .of(engine.processEngine(), engine.executionWaitingAt("Approve"));

      assertEquals(
          List.of("OrderBatch"),
          List.copyOf(scopes.keySet()),
          "the multi-instance subprocess of the calling process is the one scope to report");
      assertScope(
          scopes,
          "OrderBatch",
          AnEngineRunningCalledProcesses.ORDERS.getFirst(),
          AnEngineRunningCalledProcesses.ORDERS.size());

    }

  }

  @Test
  @DisplayName("A call activity which is multi-instance itself keeps reporting its iteration")
  public void aMultiInstanceCallActivityKeepsReportingItsIteration() {

    try (var engine = AnEngineRunningCalledProcesses
        .started(
            "mi-call-activity",
            "MiCallActivityCaller",
            Map.of("orders", AnEngineRunningCalledProcesses.ORDERS))) {

      final var scopes = Camunda7MultiInstances
          .of(engine.processEngine(), engine.executionWaitingAt("Approve"));

      assertEquals(
          List.of("HandleEach"),
          List.copyOf(scopes.keySet()),
          "the call activity carrying the multi-instance characteristics is the one scope to report");
      assertScope(
          scopes,
          "HandleEach",
          AnEngineRunningCalledProcesses.ORDERS.getFirst(),
          AnEngineRunningCalledProcesses.ORDERS.size());

    }

  }

  @Test
  @DisplayName("The chain crosses two call activities, outermost first")
  public void theChainCrossesTwoCallActivities() {

    try (var engine = AnEngineRunningCalledProcesses
        .started(
            "mi-two-levels",
            "TwoLevelCaller",
            Map
                .of(
                    "regions",
                    AnEngineRunningCalledProcesses.REGIONS,
                    "orders",
                    AnEngineRunningCalledProcesses.ORDERS))) {

      final var scopes = Camunda7MultiInstances
          .of(engine.processEngine(), engine.executionWaitingAt("Approve"));

      assertEquals(
          List.of("Regions", "OrderBatch"),
          List.copyOf(scopes.keySet()),
          "both levels, the one of the outermost caller first");
      assertScope(
          scopes,
          "Regions",
          AnEngineRunningCalledProcesses.REGIONS.getFirst(),
          AnEngineRunningCalledProcesses.REGIONS.size());
      assertScope(
          scopes,
          "OrderBatch",
          AnEngineRunningCalledProcesses.ORDERS.getFirst(),
          AnEngineRunningCalledProcesses.ORDERS.size());

    }

  }

  @Test
  @DisplayName("A called process which iterates itself reports both chains, outermost first")
  public void aCalledProcessWhichIteratesItselfReportsBothChains() {

    try (var engine = AnEngineRunningCalledProcesses
        .started(
            "mi-called-iterates-itself",
            "OwnLoopCaller",
            Map
                .of(
                    "batches",
                    AnEngineRunningCalledProcesses.BATCHES,
                    "items",
                    AnEngineRunningCalledProcesses.ITEMS))) {

      final var scopes = Camunda7MultiInstances
          .of(engine.processEngine(), engine.executionWaitingAt("ApproveItem"));

      assertEquals(
          List.of("Batch", "Items"),
          List.copyOf(scopes.keySet()),
          "the iteration of the caller before the iteration the called process runs itself");
      assertScope(
          scopes,
          "Batch",
          AnEngineRunningCalledProcesses.BATCHES.getFirst(),
          AnEngineRunningCalledProcesses.BATCHES.size());
      assertScope(
          scopes,
          "Items",
          AnEngineRunningCalledProcesses.ITEMS.getFirst(),
          AnEngineRunningCalledProcesses.ITEMS.size());

    }

  }

  @Test
  @DisplayName("The chain crosses a call activity which names the called process in an expression")
  public void theChainCrossesACallActivityNamedByAnExpression() {

    try (var engine = AnEngineRunningCalledProcesses
        .started(
            "mi-called-by-expression",
            "ExpressionCaller",
            Map
                .of(
                    "orders",
                    AnEngineRunningCalledProcesses.ORDERS,
                    "processToCall",
                    "ApprovalProcess"))) {

      final var scopes = Camunda7MultiInstances
          .of(engine.processEngine(), engine.executionWaitingAt("Approve"), engine.taskRegistry());

      assertEquals(
          List.of("ExpressionBatch"),
          List.copyOf(scopes.keySet()),
          "the deployed model said nothing about this call activity, so the core was asked while "
              + "the workflow ran");
      assertScope(
          scopes,
          "ExpressionBatch",
          AnEngineRunningCalledProcesses.ORDERS.getFirst(),
          AnEngineRunningCalledProcesses.ORDERS.size());

    }

  }

  @Test
  @DisplayName("Without the registry the chain ends at a call activity named by an expression")
  public void withoutTheRegistryTheChainEndsAtACallActivityNamedByAnExpression() {

    try (var engine = AnEngineRunningCalledProcesses
        .started(
            "mi-called-by-expression-unasked",
            "ExpressionCaller",
            Map
                .of(
                    "orders",
                    AnEngineRunningCalledProcesses.ORDERS,
                    "processToCall",
                    "ApprovalProcess"))) {

      assertTrue(
          Camunda7MultiInstances
              .of(engine.processEngine(), engine.executionWaitingAt("Approve"))
              .isEmpty(),
          "nothing in the model answers for this call activity, so a caller which cannot reach "
              + "the core gets no level rather than a guessed one");

    }

  }

  @Test
  @DisplayName("A process named by an expression which has its own aggregate reports nothing")
  public void aProcessNamedByAnExpressionWithAnAggregateOfItsOwnReportsNothing() {

    try (var engine = AnEngineRunningCalledProcesses
        .started(
            "mi-foreign-by-expression",
            "ForeignByExpressionCaller",
            Map
                .of(
                    "orders",
                    AnEngineRunningCalledProcesses.ORDERS,
                    "processToCall",
                    AnEngineRunningCalledProcesses.PROCESS_WITH_AN_AGGREGATE_OF_ITS_OWN))) {

      assertTrue(
          Camunda7MultiInstances
              .of(engine.processEngine(), engine.executionWaitingAt("Pack"), engine.taskRegistry())
              .isEmpty(),
          "how the model named the called process does not change what the answer is: a business "
              + "case of its own hears nothing about the iteration which called it");

    }

  }

  @Test
  @DisplayName("A called process with a workflow aggregate of its own reports nothing")
  public void aCalledProcessWithAnAggregateOfItsOwnReportsNothing() {

    try (var engine = AnEngineRunningCalledProcesses
        .started(
            "mi-foreign-aggregate",
            "ForeignAggregateCaller",
            Map.of("orders", AnEngineRunningCalledProcesses.ORDERS))) {

      assertTrue(
          Camunda7MultiInstances
              .of(engine.processEngine(), engine.executionWaitingAt("Pack"))
              .isEmpty(),
          "a process with an aggregate of its own runs a business case of its own and hears "
              + "nothing about the iteration which called it");

      // and the chain is not broken, it ends: the calling process still reports its own
      // iteration, which is where the values the called process does not get come from
      assertEquals(
          List.of("Shipments"),
          List.copyOf(Camunda7MultiInstances.of(engine.processEngine(), engine.executionStandingOn("Ship")).keySet()),
          "the call activity of the calling process still stands in its own iteration");

    }

  }

}
