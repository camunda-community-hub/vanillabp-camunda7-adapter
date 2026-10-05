package io.vanillabp.camunda7.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.sync.Camunda7ExpressionIdentifiers.Placement;
import io.vanillabp.integration.adapter.spi.expressions.ModelExpression;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the two startup checks are told about the expressions of a model. One of them gets
 * the attribute paths a model's conditions, timers and multi-instance collections read,
 * each with the placement deciding what the engine does with a null. The other gets the
 * expressions themselves, with the place the core names and the text JUEL evaluates.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7ExpressionIdentifiersTest {

  private BpmnModelInstance model(
      final String resource) {

    return Bpmn
        .readModelFromStream(
            getClass()
                .getClassLoader()
                .getResourceAsStream("sync/"
                    + resource));

  }

  @Test
  @DisplayName("Conditions, timers and multi-instance collections are read, with their origin")
  public void identifiersOfAProcess() {

    final var identifiers = Camunda7ExpressionIdentifiers.of(model("ExpressionsProcess.bpmn"), "ExpressionsProcess");

    assertTrue(identifiers.containsKey("approved"), identifiers.toString());
    assertTrue(identifiers.containsKey("customerName"), identifiers.toString());
    assertTrue(identifiers.containsKey("reminderDelay"), identifiers.toString());
    assertTrue(identifiers.containsKey("riskFactors"), identifiers.toString());

    // the sequence flow carries the id, its condition element does not
    assertEquals("Flow_yes", identifiers.get("approved").elementId());
    // the timer definition carries an id of its own, which is what a modeler finds
    assertEquals("Timer_1", identifiers.get("reminderDelay").elementId());
    assertEquals("Assess", identifiers.get("riskFactors").elementId());
    assertTrue(
        identifiers.get("approved").expression().contains("${approved and not empty customerName}"),
        identifiers.get("approved").expression());

    // the placement is what decides what the engine does with a null, so it travels
    // with every path: this gateway declares no default flow
    assertEquals(Placement.SEQUENCE_FLOW_CONDITION_WITHOUT_DEFAULT_FLOW, identifiers.get("approved").placement());
    assertEquals(Placement.TIMER, identifiers.get("reminderDelay").placement());
    assertEquals(Placement.MULTI_INSTANCE_COLLECTION, identifiers.get("riskFactors").placement());

    // the wired tasks are the EL resolver's business, not this check's
    assertFalse(identifiers.containsKey("approveTask"), "a task wired by expression is no variable");
    assertFalse(identifiers.containsKey("assessTask"), "a delegate expression is no variable");

  }

  @Test
  @DisplayName("An unknown process yields nothing")
  public void unknownProcessYieldsNothing() {

    assertEquals(
        Set.of(),
        Camunda7ExpressionIdentifiers.of(model("ExpressionsProcess.bpmn"), "NoSuchProcess").keySet());

  }

  @Test
  @DisplayName("The condition of every kind of conditional event is read, with the element carrying it")
  public void theConditionsOfConditionalEventsAreRead() {

    final var identifiers = Camunda7ExpressionIdentifiers
        .of(model("ConditionalEventProcess.bpmn"), "ConditionalEventProcess");

    // a conditional event carries its condition in a 'condition' element, which is an
    // unrelated model type to the 'conditionExpression' of a sequence flow: asking for
    // one never answers the other, which is why a model whose only expression is a
    // conditional event used to yield nothing at all
    assertEquals(
        Set.of("approvedByRiskOffice", "order.budgetExceeded", "order.escalation.raised"),
        identifiers.keySet());

    // an intermediate catching event, a boundary event and the start event of an event
    // subprocess, each named by the element a modeler finds
    assertEquals("AwaitApproval", identifiers.get("approvedByRiskOffice").elementId());
    assertEquals("WatchBudget", identifiers.get("order.budgetExceeded").elementId());
    assertEquals("EscalationStart", identifiers.get("order.escalation.raised").elementId());

    identifiers
        .values()
        .forEach(origin -> assertEquals(Placement.CONDITIONAL_EVENT, origin.placement(), origin.toString()));

  }

  @Test
  @DisplayName("A path read by a conditional event and by a timer is credited to the conditional event")
  public void theQuietestPlacementIsTheOneReported() {

    final var identifiers = Camunda7ExpressionIdentifiers
        .of(model("ConditionalEventProcess.bpmn"), "ConditionBeforeTimerProcess");

    // both elements read '${order.reference}' and the path is reported once. The timer
    // would raise an incident nobody can miss, the conditional event would wait without
    // a trace, so the conditional event is the origin worth naming
    assertEquals(Set.of("order.reference"), identifiers.keySet());
    assertEquals("CBT_AwaitOrder", identifiers.get("order.reference").elementId());
    assertEquals(Placement.CONDITIONAL_EVENT, identifiers.get("order.reference").placement());

  }

  @Test
  @DisplayName("A path keeps its segments and ends where a call or an index begins")
  public void whatAPathIs() {

    assertEquals(Set.of("a", "b.c"), Camunda7ExpressionIdentifiers.pathsOf("${a > 1 and b.c ne null}"));
    assertEquals(Set.of("order.customer.name"), Camunda7ExpressionIdentifiers.pathsOf("${order.customer.name}"));
    // a method call says nothing about a declared type, so the path stops before it
    assertEquals(Set.of("order"), Camunda7ExpressionIdentifiers.pathsOf("${order.getTotal() > 100}"));
    assertEquals(Set.of("order.status"), Camunda7ExpressionIdentifiers.pathsOf("${order.status.name() == ''}"));
    // an indexed access reads an element of a collection, which the check does not
    // follow either
    assertEquals(Set.of("order.items"), Camunda7ExpressionIdentifiers.pathsOf("${order.items[0].price > 10}"));

  }

  @Test
  @DisplayName("Keywords, functions and namespaces are no paths")
  public void whatIsNoPath() {

    assertEquals(Set.of(), Camunda7ExpressionIdentifiers.pathsOf("${empty null}"));
    assertEquals(Set.of(), Camunda7ExpressionIdentifiers.pathsOf("${execution.getVariable('x')}"));
    assertEquals(Set.of("y"), Camunda7ExpressionIdentifiers.pathsOf("${fn:format(y)}"));
    assertEquals(Set.of(), Camunda7ExpressionIdentifiers.pathsOf("no expression at all"));
    assertEquals(Set.of(), Camunda7ExpressionIdentifiers.pathsOf(null));
    // '#{...}' is an expression as well
    assertEquals(Set.of("approved"), Camunda7ExpressionIdentifiers.pathsOf("#{approved}"));

  }

  /**
   * One reported expression in one line, so an assertion can read the four things the
   * core is told at once.
   *
   * @param expression What the adapter reports
   * @return Element, place, the expression as the model has it and the body JUEL
   *         evaluates
   */
  private static String reported(
      final ModelExpression expression) {

    return "%s | %s | %s | %s"
        .formatted(
            expression.elementId(),
            expression.place(),
            expression.expression(),
            expression.body());

  }

  @Test
  @DisplayName("Every expression of the model is reported with its element, its place and its body")
  public void everyExpressionIsReported() {

    final var expressions = Camunda7ExpressionIdentifiers
        .expressionsOf(model("ModelExpressionsProcess.bpmn"), "ModelExpressionsProcess");

    assertEquals(
        Set
            .of(
                "ME_Await | CONDITIONAL_EVENT_CONDITION | ${not approved} | not approved",
                "ME_Items | MULTI_INSTANCE_COMPLETION_CONDITION | ${order.items.size() > 3} | order.items.size() > 3",
                // the two sequence-flow placements of the sync check are one place here,
                // and '#{...}' is an expression just like '${...}'
                "ME_express | SEQUENCE_FLOW_CONDITION | #{order.shipping.express} | order.shipping.express",
                "ME_Cycle | TIMER | ${firstRun} | firstRun",
                "ME_Cycle | TIMER | ${pauseHours} | pauseHours",
                "ME_Rounds | MULTI_INSTANCE_CARDINALITY | ${rounds} | rounds",
                "ME_Items | MULTI_INSTANCE_COLLECTION | ${order.items} | order.items"),
        expressions
            .stream()
            .map(Camunda7ExpressionIdentifiersTest::reported)
            .collect(Collectors.toSet()));
    // nothing is reported twice, so the count the core's message states is the model's
    assertEquals(7, expressions.size(), expressions.toString());

    // a timer written as a plain duration carries no expression, and a delegate
    // expression names a wired task rather than reading the workflow's data
    assertTrue(
        expressions
            .stream()
            .noneMatch(expression -> "ME_Fixed".equals(expression.elementId())),
        expressions.toString());
    assertTrue(
        expressions
            .stream()
            .noneMatch(expression -> expression.expression().contains("shipTask")),
        expressions.toString());

  }

  @Test
  @DisplayName("An attribute holding two expressions reports both, in the order it writes them")
  public void twoExpressionsInOneAttribute() {

    final var bodies = Camunda7ExpressionIdentifiers
        .expressionsOf(model("ModelExpressionsProcess.bpmn"), "ModelExpressionsProcess")
        .stream()
        .filter(expression -> "ME_Cycle".equals(expression.elementId()))
        .map(ModelExpression::body)
        .toList();

    // 'R3/${firstRun}/PT${pauseHours}H' is two expressions with text around them. Judged
    // as one text it would read as a computation, which is the wrong half of the finding
    assertEquals(List.of("firstRun", "pauseHours"), bodies);

  }

  @Test
  @DisplayName("A model without an expression reports nothing, and so does an unknown process")
  public void nothingToReport() {

    assertEquals(
        List.of(),
        Camunda7ExpressionIdentifiers
            .expressionsOf(model("ModelExpressionsProcess.bpmn"), "NoExpressionsProcess"));
    assertEquals(
        List.of(),
        Camunda7ExpressionIdentifiers.expressionsOf(model("ModelExpressionsProcess.bpmn"), "NoSuchProcess"));

  }

}
