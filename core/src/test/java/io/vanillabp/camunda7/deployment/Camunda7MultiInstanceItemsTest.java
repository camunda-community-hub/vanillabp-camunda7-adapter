package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot says about a handler which wants the item of an iteration the model hands
 * none over for.
 * <p>
 * Every refusal is asserted together with the model that must NOT be refused. An element
 * which iterates a number of times is a legitimate model, and so is a handler which reads
 * only how far the iteration got. Refusing either would end the boot of an application that
 * does nothing wrong.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7MultiInstanceItemsTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  private static BpmnModelInstance model(
      final String processContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
          <bpmn:process id="OtherProcess" isExecutable="true">
            <bpmn:subProcess id="Subprocess_ofTheOtherProcess">
              <bpmn:multiInstanceLoopCharacteristics>
                <bpmn:loopCardinality>3</bpmn:loopCardinality>
              </bpmn:multiInstanceLoopCharacteristics>
            </bpmn:subProcess>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, processContent);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /**
   * A subprocess which iterates a number of times, with one task inside it. Nothing here
   * says what the value of a round is called, because there are no values to begin with.
   */
  private static final String COUNTING_SUBPROCESS = """
          <bpmn:subProcess id="Subprocess_rounds">
            <bpmn:multiInstanceLoopCharacteristics>
              <bpmn:loopCardinality>3</bpmn:loopCardinality>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:serviceTask id="Activity_check" camunda:delegateExpression="${checkCredit}" />
          </bpmn:subProcess>
      """;

  /**
   * The same subprocess walking a collection and naming the variable each round's value
   * goes into, which is the model a handler asking for the item needs.
   */
  private static final String COLLECTING_SUBPROCESS = """
          <bpmn:subProcess id="Subprocess_rounds">
            <bpmn:multiInstanceLoopCharacteristics camunda:collection="${applications}" camunda:elementVariable="application">
              <bpmn:loopCardinality>3</bpmn:loopCardinality>
            </bpmn:multiInstanceLoopCharacteristics>
            <bpmn:serviceTask id="Activity_check" camunda:delegateExpression="${checkCredit}" />
          </bpmn:subProcess>
      """;

  /**
   * The core of an application whose methods want the item of the given elements, keyed by
   * what the method was wired by. Everything it is not asked about answers nothing, which
   * is what a method declaring no <code>@MultiInstanceElement</code> looks like.
   */
  private static WorkflowTaskWiring aCoreWantingTheItemOf(
      final Map<String, List<String>> wantedByWiringName) {

    final var wiring = mock(WorkflowTaskWiring.class);
    when(wiring.multiInstanceElementNames(anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> wantedByWiringName.getOrDefault(invocation.getArgument(2), List.of()));
    return wiring;

  }

  private static Camunda7DeploymentService adapter(
      final WorkflowTaskWiring wiring) {

    return new Camunda7DeploymentService(
        "c7", null, mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .workflowTaskWiring(wiring)
            .build(), new Camunda7TaskRegistry());

  }

  private static void deploy(
      final WorkflowTaskWiring wiring,
      final BpmnModelInstance model) {

    final var service = adapter(wiring);
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
    service.wireBpmn(MODULE, FILE, PROCESS, model, context);

  }

  @Test
  @DisplayName("A handler wanting the item of an element which hands none over ends the boot")
  public void anElementWithoutAnItemEndsTheBoot() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> deploy(
            aCoreWantingTheItemOf(Map.of("checkCredit", List.of("Subprocess_rounds"))),
            model(COUNTING_SUBPROCESS)));

    final var message = refused.getMessage();
    assertTrue(
        message.contains("'Subprocess_rounds'"),
        () -> "the element a modeller has to find in their own model: "
            + message);
    assertTrue(
        message.contains("'Activity_check'") && message.contains("'checkCredit'"),
        () -> "and the task whose method asked for it: "
            + message);
    assertTrue(
        message.contains("camunda:elementVariable"),
        () -> "what the model would have to carry: "
            + message);
    assertTrue(
        message.contains("@MultiInstanceIndex"),
        () -> "and the other way out, which is to stop asking for the item: "
            + message);

  }

  @Test
  @DisplayName("The same model naming the variable deploys")
  public void anElementNamingTheVariableDeploys() {

    assertDoesNotThrow(
        () -> deploy(
            aCoreWantingTheItemOf(Map.of("checkCredit", List.of("Subprocess_rounds"))),
            model(COLLECTING_SUBPROCESS)));

  }

  @Test
  @DisplayName("An element which iterates a number of times deploys where no handler wants its item")
  public void countingWithoutAskingForTheItemDeploys() {

    assertDoesNotThrow(() -> deploy(aCoreWantingTheItemOf(Map.of()), model(COUNTING_SUBPROCESS)));

  }

  @Test
  @DisplayName("An element this model does not know belongs to a caller and is no finding")
  public void anElementOfAnotherModelIsNoFinding() {

    assertDoesNotThrow(
        () -> deploy(
            aCoreWantingTheItemOf(Map.of("checkCredit", List.of("Subprocess_ofTheCaller"))),
            model(COUNTING_SUBPROCESS)),
        "the multi-instance chain crosses a call activity, so a task of a called process "
            + "asks for an element of its caller");

  }

  @Test
  @DisplayName("A method wired by the element id is found by that id")
  public void aMethodWiredByTheElementIdIsFoundToo() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> deploy(
            aCoreWantingTheItemOf(Map.of("Activity_check", List.of("Subprocess_rounds"))),
            model(COUNTING_SUBPROCESS)));

    assertTrue(refused.getMessage().contains("'Subprocess_rounds'"), refused::getMessage);

  }

  @Test
  @DisplayName("Every task of one process is named in ONE message")
  public void everyFindingOfAProcessIsReportedAtOnce() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> deploy(
            aCoreWantingTheItemOf(
                Map
                    .of(
                        "checkCredit",
                        List.of("Subprocess_rounds"),
                        "rateCredit",
                        List.of("Subprocess_rounds", "Activity_rate"))),
            model("""
                    <bpmn:subProcess id="Subprocess_rounds">
                      <bpmn:multiInstanceLoopCharacteristics>
                        <bpmn:loopCardinality>3</bpmn:loopCardinality>
                      </bpmn:multiInstanceLoopCharacteristics>
                      <bpmn:serviceTask id="Activity_check" camunda:delegateExpression="${checkCredit}" />
                      <bpmn:serviceTask id="Activity_rate" camunda:delegateExpression="${rateCredit}">
                        <bpmn:multiInstanceLoopCharacteristics>
                          <bpmn:loopCardinality>2</bpmn:loopCardinality>
                        </bpmn:multiInstanceLoopCharacteristics>
                      </bpmn:serviceTask>
                    </bpmn:subProcess>
                """)));

    final var message = refused.getMessage();
    assertTrue(
        message.contains("'Activity_check'") && message.contains("'Activity_rate'"),
        () -> "both tasks, because fixing one model should not need a second restart: "
            + message);
    assertTrue(
        message.contains("'Subprocess_rounds', 'Activity_rate'"),
        () -> "and both elements the second task wants an item of: "
            + message);

  }

  @Test
  @DisplayName("An element of another branch of the same process is no finding")
  public void anElementOfAnotherBranchIsNoFinding() {

    assertDoesNotThrow(
        () -> deploy(
            aCoreWantingTheItemOf(Map.of("checkCredit", List.of("Subprocess_otherRounds"))),
            model(
                """
                        <bpmn:subProcess id="Subprocess_rounds">
                          <bpmn:multiInstanceLoopCharacteristics camunda:collection="${applications}" camunda:elementVariable="application">
                            <bpmn:loopCardinality>3</bpmn:loopCardinality>
                          </bpmn:multiInstanceLoopCharacteristics>
                          <bpmn:serviceTask id="Activity_check" camunda:delegateExpression="${checkCredit}" />
                        </bpmn:subProcess>
                        <bpmn:subProcess id="Subprocess_otherRounds">
                          <bpmn:multiInstanceLoopCharacteristics>
                            <bpmn:loopCardinality>2</bpmn:loopCardinality>
                          </bpmn:multiInstanceLoopCharacteristics>
                          <bpmn:serviceTask id="Activity_rate" camunda:delegateExpression="${rateCredit}" />
                        </bpmn:subProcess>
                    """)),
        "the handler runs inside 'Subprocess_rounds' and the item of a branch it never "
            + "enters cannot reach it, so the model this boot brings is not the place to "
            + "refuse anything");

  }

  @Test
  @DisplayName("The chain of a task carries the enclosing elements, outermost first")
  public void theChainOfATaskIsRead() {

    final var counting = model(COUNTING_SUBPROCESS);
    assertEquals(
        List.of("Subprocess_rounds"),
        Camunda7MultiInstanceItems
            .elementsWithoutAnItemAround(counting.getModelElementById("Activity_check")));
    final var collecting = model(COLLECTING_SUBPROCESS);
    assertEquals(
        List.of(),
        Camunda7MultiInstanceItems
            .elementsWithoutAnItemAround(collecting.getModelElementById("Activity_check")));
    assertEquals(
        List.of(),
        Camunda7MultiInstanceItems
            .elementsWithoutAnItemAround(counting.getModelElementById("Subprocess_ofTheOtherProcess")
                .getParentElement()),
        "a process is no iteration, so the chain of anything ends there");

  }

}
