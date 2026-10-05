package io.vanillabp.camunda7.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which execution listeners of a model this adapter recognises, and what it makes of each
 * form the BPMN offers.
 * <p>
 * The separation from VanillaBP's own listeners needs no assertion about a prefix here: every
 * listener this adapter and its extensions attach goes onto the element the engine PARSED and
 * never into the BPMN, so reading the BPMN sees the modeller's and nothing else.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7ListenersTest {

  private static final String PROCESS = "TestProcess";

  /**
   * The expression reader the deployment hands in: the tolerant half of the adapter's own
   * unwrapping, which answers <code>null</code> for an expression VanillaBP cannot read.
   */
  private static final java.util.function.UnaryOperator<String> READ_EXPRESSION = expression -> {
    final var matcher = Pattern.compile("^[#$]\\{([^}]+)}$").matcher(expression.trim());
    return matcher.matches()
        ? matcher.group(1).trim()
        : null;
  };

  private static BpmnModelInstance model(
      final String processContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, processContent);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private static List<Camunda7Listeners.ModelledListener> listenersOf(
      final String processContent) {

    return Camunda7Listeners.listenersOf(model(processContent), PROCESS, READ_EXPRESSION);

  }

  @Test
  @DisplayName("A delegate expression names the task definition a method is matched by")
  public void aDelegateExpressionNamesTheTaskDefinition() {

    final var listeners = listenersOf("""
            <bpmn:endEvent id="Event_Done">
              <bpmn:extensionElements>
                <camunda:executionListener event="end" delegateExpression="${archiveTheOrder}" />
              </bpmn:extensionElements>
            </bpmn:endEvent>
        """);

    assertEquals(1, listeners.size());
    final var listener = listeners.getFirst();
    assertEquals("archiveTheOrder", listener.taskDefinition(), "which is version 1's convention, unchanged");
    assertEquals("end", listener.event(), "and the event, which version 1 never recorded");
    assertEquals(Camunda7Listeners.Implementation.DELEGATE_EXPRESSION, listener.implementation());
    assertEquals("Event_Done", listener.elementId());
    assertTrue(listener.implementation().servable());

  }

  @Test
  @DisplayName("An expression listener is recognised the same way and keeps its own form")
  public void anExpressionListenerIsRecognised() {

    final var listeners = listenersOf("""
            <bpmn:intermediateThrowEvent id="Event_Note">
              <bpmn:extensionElements>
                <camunda:executionListener event="start" expression="${noteTheStep}" />
              </bpmn:extensionElements>
            </bpmn:intermediateThrowEvent>
        """);

    assertEquals(1, listeners.size());
    assertEquals(Camunda7Listeners.Implementation.EXPRESSION, listeners.getFirst().implementation());
    assertEquals("noteTheStep", listeners.getFirst().taskDefinition());
    assertEquals("start", listeners.getFirst().event());

  }

  @Test
  @DisplayName("A listener on any element is recognised, not only on the two version 1 allowed")
  public void aListenerOnAnyElementIsRecognised() {

    final var listeners = listenersOf("""
            <bpmn:serviceTask id="Activity_Approve" camunda:expression="${approve}">
              <bpmn:extensionElements>
                <camunda:executionListener event="end" delegateExpression="${auditTheApproval}" />
              </bpmn:extensionElements>
            </bpmn:serviceTask>
        """);

    assertEquals(
        List.of("auditTheApproval"),
        listeners
            .stream()
            .map(Camunda7Listeners.ModelledListener::taskDefinition)
            .toList(),
        "a restriction a modeller cannot see in their own model is worse than none");

  }

  @Test
  @DisplayName("A class listener and a script listener carry no task definition")
  public void aClassOrScriptListenerCarriesNoTaskDefinition() {

    final var listeners = listenersOf("""
            <bpmn:endEvent id="Event_Done">
              <bpmn:extensionElements>
                <camunda:executionListener event="end" class="com.acme.Archive" />
                <camunda:executionListener event="start">
                  <camunda:script scriptFormat="groovy">println 'hi'</camunda:script>
                </camunda:executionListener>
              </bpmn:extensionElements>
            </bpmn:endEvent>
        """);

    assertEquals(2, listeners.size());
    assertEquals(Camunda7Listeners.Implementation.CLASS, listeners.getFirst().implementation());
    assertEquals("com.acme.Archive", listeners.getFirst().rawExpression(), "what a message has to quote");
    assertNull(listeners.getFirst().taskDefinition(), "nothing a @WorkflowTask method could be matched by");
    assertEquals(Camunda7Listeners.Implementation.SCRIPT, listeners.getLast().implementation());
    assertEquals("groovy", listeners.getLast().rawExpression());
    assertTrue(
        listeners
            .stream()
            .noneMatch(listener -> listener.implementation().servable()),
        "so the property is not the way out for either, and the engine runs both itself");

  }

  @Test
  @DisplayName("An expression VanillaBP cannot read is recognised and left without a task definition")
  public void anUnreadableExpressionIsRecognised() {

    final var listeners = listenersOf("""
            <bpmn:endEvent id="Event_Done">
              <bpmn:extensionElements>
                <camunda:executionListener event="end" expression="prefix ${archive} suffix" />
              </bpmn:extensionElements>
            </bpmn:endEvent>
        """);

    assertEquals(1, listeners.size());
    assertNull(
        listeners.getFirst().taskDefinition(),
        "the deployment decides what to do about it, because only it knows whether the model can "
            + "still be changed");

  }

  @Test
  @DisplayName("A task listener is not read, because VanillaBP notifies a user task itself")
  public void aTaskListenerIsNotRead() {

    final var listeners = listenersOf("""
            <bpmn:userTask id="Activity_Approve" camunda:formKey="approve">
              <bpmn:extensionElements>
                <camunda:taskListener event="create" delegateExpression="${whenCreated}" />
              </bpmn:extensionElements>
            </bpmn:userTask>
        """);

    assertTrue(
        listeners.isEmpty(),
        () -> "version 1 never read one either, and the creation and the cancellation of a user task "
            + "already reach the @WorkflowTask method: "
            + listeners);

  }

  @Test
  @DisplayName("A listener of another process of the same file is not read for this one")
  public void aListenerOfAnotherProcessIsNotRead() {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="TestProcess" isExecutable="true">
            <bpmn:endEvent id="Event_Here">
              <bpmn:extensionElements>
                <camunda:executionListener event="end" delegateExpression="${here}" />
              </bpmn:extensionElements>
            </bpmn:endEvent>
          </bpmn:process>
          <bpmn:process id="OtherProcess" isExecutable="true">
            <bpmn:endEvent id="Event_There">
              <bpmn:extensionElements>
                <camunda:executionListener event="end" delegateExpression="${there}" />
              </bpmn:extensionElements>
            </bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """;
    final var model = Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

    assertEquals(
        List.of("here"),
        Camunda7Listeners
            .listenersOf(model, "TestProcess", READ_EXPRESSION)
            .stream()
            .map(Camunda7Listeners.ModelledListener::taskDefinition)
            .toList(),
        "the property is keyed by the process, so the reading has to be too");

  }

  @Test
  @DisplayName("Two listeners of one element under one expression are found as the pair they are")
  public void twoListenersUnderOneExpressionAreFound() {

    final var listeners = listenersOf("""
            <bpmn:endEvent id="Event_Done">
              <bpmn:extensionElements>
                <camunda:executionListener event="start" delegateExpression="${archive}" />
                <camunda:executionListener event="end" delegateExpression="${archive}" />
              </bpmn:extensionElements>
            </bpmn:endEvent>
        """);

    final var sharing = Camunda7Listeners.listenersSharingATaskDefinition(listeners);
    assertEquals(1, sharing.size());
    assertEquals(
        List.of("start", "end"),
        sharing
            .getFirst()
            .stream()
            .map(Camunda7Listeners.ModelledListener::event)
            .toList(),
        "version 1 picked one of these by findFirst and nobody could tell which");

  }

  @Test
  @DisplayName("Two listeners of one element under two expressions are two tasks, not a clash")
  public void twoListenersUnderTwoExpressionsAreNoClash() {

    final var listeners = listenersOf("""
            <bpmn:endEvent id="Event_Done">
              <bpmn:extensionElements>
                <camunda:executionListener event="start" delegateExpression="${theEventIsReached}" />
                <camunda:executionListener event="end" delegateExpression="${theEventIsDone}" />
              </bpmn:extensionElements>
            </bpmn:endEvent>
        """);

    assertEquals(2, listeners.size());
    assertTrue(
        Camunda7Listeners.listenersSharingATaskDefinition(listeners).isEmpty(),
        "an expression per event is what makes the event part of the identity");

  }

  @Test
  @DisplayName("The three levels are printed so a reader can paste them")
  public void theLevelsArePrintedAsABlock() {

    assertEquals(
        """
            vanillabp.adapters.c7.allow-listeners
            vanillabp.workflow-modules.loans.adapters.c7.allow-listeners
            vanillabp.workflow-modules.loans.workflows.Loan.adapters.c7.allow-listeners""",
        Camunda7Listeners.levelsOf("c7", "loans", "Loan"),
        "least specific first, which is the order a reader decides in");
    assertEquals("vanillabp.adapters.c7.allow-listeners", Camunda7Listeners.propertyKeyOf("c7"));

  }

  @Test
  @DisplayName("A key at task level earns one warning naming the levels which work")
  public void aKeyAtTaskLevelIsReported() {

    final var warnings = new ArrayList<String>();

    Camunda7Listeners.reportKeysSetAtTaskLevel("c7", List.of(), warnings::add);
    assertTrue(warnings.isEmpty(), "a configuration which does nothing wrong says nothing");

    Camunda7Listeners
        .reportKeysSetAtTaskLevel(
            "c7",
            List.of("vanillabp.workflow-modules.loans.workflows.Loan.tasks.approve.adapters.c7.allow-listeners"),
            warnings::add);
    assertEquals(1, warnings.size());
    assertTrue(
        warnings.getFirst().contains("does not resolve this key"),
        () -> "what the value does, which is nothing: "
            + warnings);
    assertTrue(
        warnings.getFirst().contains("vanillabp.workflow-modules.<m>.adapters.c7.allow-listeners"),
        () -> "and where it would work: "
            + warnings);

  }

  @Test
  @DisplayName("What both adapters say about the cost is the same text")
  public void bothAdaptersSayTheSameAboutTheCost() {

    assertTrue(
        Camunda7Listeners.WHAT_IT_COSTS.contains("stops being portable"),
        "the sentence a reader meets in the refusal and in the report");
    assertTrue(
        Camunda7Listeners.WHAT_IT_COSTS.contains("gap 16 and 17"),
        "with the evidence behind the portability claim, which is the Process-Engine-API's GAPS.md");
    assertTrue(
        Camunda7Listeners.WHAT_IT_COSTS.contains("A listener knows two events and no more"),
        "and what a served method is told");

  }

  @Test
  @DisplayName("Which listener hears a cancellation through itself and which one needs VanillaBP")
  public void whichListenerNeedsACancellation() {

    assertTrue(
        Camunda7Listeners.isACancellation(
            new Camunda7Listeners.ModelledListener(
                "Loan", "Activity_Approve", "end", Camunda7Listeners.Implementation.EXPRESSION, "${auditIt}", "auditIt")),
        "this engine fires an END execution listener on a cancellation too, so such a method "
            + "already hears the moment");
    assertFalse(
        Camunda7Listeners.isACancellation(
            new Camunda7Listeners.ModelledListener(
                "Loan", "Activity_Approve", "start", Camunda7Listeners.Implementation.EXPRESSION, "${auditIt}", "auditIt")),
        "a start listener never fires for an element a boundary event takes away");
    assertFalse(
        Camunda7Listeners.isACancellation(
            new Camunda7Listeners.ModelledListener(
                "Loan", "Flow_Approved", "take", Camunda7Listeners.Implementation.EXPRESSION, "${auditIt}", "auditIt")),
        "a take listener needs one as far as this rule is concerned, and the parse listener then "
            + "finds no activity for its sequence flow");

  }

}
