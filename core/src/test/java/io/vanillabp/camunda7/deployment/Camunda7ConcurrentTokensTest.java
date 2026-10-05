package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.CompensationSpec;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which elements of a model can put a SECOND token into a running workflow -
 * the finding this adapter reports to the core, which turns it into the hint about two
 * writers on one workflow aggregate.
 * <p>
 * Every construct is asserted together with the variant that does NOT produce a second
 * token, since a hint given for a sequential model would be worse than none.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7ConcurrentTokensTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS_ID = "TestProcess";

  private static BpmnModelInstance model(
      final String processContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="D" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="TestProcess" isExecutable="true">
        %s
          </bpmn:process>
          <bpmn:process id="OtherProcess" isExecutable="true">
            <bpmn:parallelGateway id="OtherFork">
              <bpmn:outgoing>OtherFlow_1</bpmn:outgoing>
              <bpmn:outgoing>OtherFlow_2</bpmn:outgoing>
            </bpmn:parallelGateway>
            <bpmn:task id="OtherTask_1" />
            <bpmn:task id="OtherTask_2" />
            <bpmn:sequenceFlow id="OtherFlow_1" sourceRef="OtherFork" targetRef="OtherTask_1" />
            <bpmn:sequenceFlow id="OtherFlow_2" sourceRef="OtherFork" targetRef="OtherTask_2" />
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(processContent);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private static List<String> elementsOf(
      final String processContent) {

    return Camunda7ConcurrentTokens.elementIdsOf(model(processContent), PROCESS_ID);

  }

  private static List<CompensationSpec> compensationOf(
      final String processContent) {

    return Camunda7ConcurrentTokens.compensationOf(model(processContent), PROCESS_ID);

  }

  /**
   * Two activities which were compensated, drawn the way a modeller draws them: a
   * compensation boundary event on each, and an association to the handler undoing it.
   */
  private static final String TWO_ACTIVITIES_WITH_A_HANDLER = """
          <bpmn:serviceTask id="Activity_Book" camunda:delegateExpression="${book}" />
          <bpmn:serviceTask id="Activity_Pay" camunda:delegateExpression="${pay}" />
          <bpmn:boundaryEvent id="Event_BookWasMade" attachedToRef="Activity_Book">
            <bpmn:compensateEventDefinition id="Compensate_Book" />
          </bpmn:boundaryEvent>
          <bpmn:boundaryEvent id="Event_PaymentWasMade" attachedToRef="Activity_Pay">
            <bpmn:compensateEventDefinition id="Compensate_Pay" />
          </bpmn:boundaryEvent>
          <bpmn:serviceTask id="Activity_CancelBooking" isForCompensation="true" camunda:delegateExpression="${cancelBooking}" />
          <bpmn:serviceTask id="Activity_RefundPayment" isForCompensation="true" camunda:delegateExpression="${refundPayment}" />
          <bpmn:association id="To_CancelBooking" associationDirection="One" sourceRef="Event_BookWasMade" targetRef="Activity_CancelBooking" />
          <bpmn:association id="To_RefundPayment" associationDirection="One" sourceRef="Event_PaymentWasMade" targetRef="Activity_RefundPayment" />
      """;

  @Test
  @DisplayName("A throw event compensating everything names every handler it starts")
  public void aThrowEventCompensatingEverything() {

    final var found = compensationOf("""
            <bpmn:intermediateThrowEvent id="Event_UndoEverything">
              <bpmn:compensateEventDefinition id="Throw_All" />
            </bpmn:intermediateThrowEvent>
        """ + TWO_ACTIVITIES_WITH_A_HANDLER);

    assertEquals(
        List
            .of(
                new CompensationSpec(
                    "Event_UndoEverything", List.of("Activity_CancelBooking", "Activity_RefundPayment"))),
        found);

  }

  @Test
  @DisplayName("A throw event naming ONE activity starts that activity's handler only")
  public void aThrowEventNamingOneActivity() {

    final var found = compensationOf("""
            <bpmn:intermediateThrowEvent id="Event_UndoTheBooking">
              <bpmn:compensateEventDefinition id="Throw_One" activityRef="Activity_Book" />
            </bpmn:intermediateThrowEvent>
        """ + TWO_ACTIVITIES_WITH_A_HANDLER);

    assertEquals(
        List.of(new CompensationSpec("Event_UndoTheBooking", List.of("Activity_CancelBooking"))), found);

  }

  @Test
  @DisplayName("An end event throwing compensation is read like an intermediate one")
  public void anEndEventThrowingCompensation() {

    final var found = compensationOf("""
            <bpmn:endEvent id="Event_EndUndoing">
              <bpmn:compensateEventDefinition id="Throw_End" />
            </bpmn:endEvent>
        """ + TWO_ACTIVITIES_WITH_A_HANDLER);

    assertEquals(
        List
            .of(
                new CompensationSpec(
                    "Event_EndUndoing", List.of("Activity_CancelBooking", "Activity_RefundPayment"))),
        found);

  }

  @Test
  @DisplayName("A throw event inside a subprocess undoes that subprocess only")
  public void aThrowEventInsideASubProcess() {

    final var found = compensationOf(
        """
                <bpmn:subProcess id="SubProcess_Trip">
                  <bpmn:serviceTask id="Activity_Seat" camunda:delegateExpression="${seat}" />
                  <bpmn:boundaryEvent id="Event_SeatWasTaken" attachedToRef="Activity_Seat">
                    <bpmn:compensateEventDefinition id="Compensate_Seat" />
                  </bpmn:boundaryEvent>
                  <bpmn:serviceTask id="Activity_CancelSeat" isForCompensation="true" camunda:delegateExpression="${cancelSeat}" />
                  <bpmn:intermediateThrowEvent id="Event_UndoTheTrip">
                    <bpmn:compensateEventDefinition id="Throw_Trip" />
                  </bpmn:intermediateThrowEvent>
                  <bpmn:association id="To_CancelSeat" associationDirection="One" sourceRef="Event_SeatWasTaken" targetRef="Activity_CancelSeat" />
                </bpmn:subProcess>
            """ + TWO_ACTIVITIES_WITH_A_HANDLER);

    // the two handlers of the process scope are not started by a throw event of the
    // subprocess, so this one starts one handler and is no finding for the caller
    assertEquals(
        List.of(new CompensationSpec("Event_UndoTheTrip", List.of("Activity_CancelSeat"))), found);

  }

  @Test
  @DisplayName("A model without compensation reports nothing")
  public void aModelWithoutCompensation() {

    assertTrue(compensationOf("""
            <bpmn:serviceTask id="Activity_Book" camunda:delegateExpression="${book}" />
            <bpmn:intermediateThrowEvent id="Event_Signal">
              <bpmn:signalEventDefinition id="Signal_1" />
            </bpmn:intermediateThrowEvent>
        """).isEmpty());

  }

  @Test
  @DisplayName("A compensation handler is no concurrent-token element of its own")
  public void aCompensationHandlerIsNoElementOfTheFlatList() {

    final var found = elementsOf("""
            <bpmn:intermediateThrowEvent id="Event_UndoEverything">
              <bpmn:compensateEventDefinition id="Throw_All" />
            </bpmn:intermediateThrowEvent>
        """ + TWO_ACTIVITIES_WITH_A_HANDLER);

    // what compensation means is reported with its shape, so nothing of it leaks into the
    // flat list the other constructs are reported through
    assertTrue(found.isEmpty(), found.toString());

  }

  @Test
  @DisplayName("A boundary event which does not cancel its activity produces a second token")
  public void nonInterruptingBoundaryEvent() {

    final var found = elementsOf("""
            <bpmn:serviceTask id="Activity_Approve" camunda:delegateExpression="${approve}" />
            <bpmn:boundaryEvent id="Event_Reminder" cancelActivity="false" attachedToRef="Activity_Approve">
              <bpmn:timerEventDefinition id="Timer_1" />
            </bpmn:boundaryEvent>
            <bpmn:boundaryEvent id="Event_Timeout" attachedToRef="Activity_Approve">
              <bpmn:timerEventDefinition id="Timer_2" />
            </bpmn:boundaryEvent>
        """);

    assertEquals(List.of("Event_Reminder"), found);

  }

  @Test
  @DisplayName("A parallel or inclusive gateway counts when it forks, not when it joins")
  public void forkingGateways() {

    final var found = elementsOf("""
            <bpmn:parallelGateway id="Gateway_Fork">
              <bpmn:outgoing>Flow_1</bpmn:outgoing>
              <bpmn:outgoing>Flow_2</bpmn:outgoing>
            </bpmn:parallelGateway>
            <bpmn:inclusiveGateway id="Gateway_Inclusive">
              <bpmn:outgoing>Flow_3</bpmn:outgoing>
              <bpmn:outgoing>Flow_4</bpmn:outgoing>
            </bpmn:inclusiveGateway>
            <bpmn:parallelGateway id="Gateway_Join">
              <bpmn:incoming>Flow_1</bpmn:incoming>
              <bpmn:incoming>Flow_2</bpmn:incoming>
              <bpmn:outgoing>Flow_5</bpmn:outgoing>
            </bpmn:parallelGateway>
            <bpmn:task id="Task_1" />
            <bpmn:task id="Task_2" />
            <bpmn:task id="Task_3" />
            <bpmn:sequenceFlow id="Flow_1" sourceRef="Gateway_Fork" targetRef="Gateway_Join" />
            <bpmn:sequenceFlow id="Flow_2" sourceRef="Gateway_Fork" targetRef="Gateway_Join" />
            <bpmn:sequenceFlow id="Flow_3" sourceRef="Gateway_Inclusive" targetRef="Task_1" />
            <bpmn:sequenceFlow id="Flow_4" sourceRef="Gateway_Inclusive" targetRef="Task_2" />
            <bpmn:sequenceFlow id="Flow_5" sourceRef="Gateway_Join" targetRef="Task_3" />
        """);

    assertEquals(List.of("Gateway_Fork", "Gateway_Inclusive"), found);

  }

  @Test
  @DisplayName("A multi-instance activity counts when it is parallel, not when it is sequential")
  public void parallelMultiInstance() {

    final var found = elementsOf("""
            <bpmn:serviceTask id="Activity_Parallel" camunda:delegateExpression="${each}">
              <bpmn:multiInstanceLoopCharacteristics isSequential="false" />
            </bpmn:serviceTask>
            <bpmn:serviceTask id="Activity_Sequential" camunda:delegateExpression="${each}">
              <bpmn:multiInstanceLoopCharacteristics isSequential="true" />
            </bpmn:serviceTask>
        """);

    assertEquals(List.of("Activity_Parallel"), found);

  }

  @Test
  @DisplayName("An event subprocess counts when its start event does not interrupt the process")
  public void nonInterruptingEventSubProcess() {

    final var found = elementsOf("""
            <bpmn:subProcess id="SubProcess_Reminder" triggeredByEvent="true">
              <bpmn:startEvent id="Start_Reminder" isInterrupting="false">
                <bpmn:messageEventDefinition id="Message_1" />
              </bpmn:startEvent>
            </bpmn:subProcess>
            <bpmn:subProcess id="SubProcess_Cancel" triggeredByEvent="true">
              <bpmn:startEvent id="Start_Cancel">
                <bpmn:messageEventDefinition id="Message_2" />
              </bpmn:startEvent>
            </bpmn:subProcess>
            <bpmn:subProcess id="SubProcess_Embedded">
              <bpmn:startEvent id="Start_Embedded" />
            </bpmn:subProcess>
        """);

    assertEquals(List.of("SubProcess_Reminder"), found);

  }

  @Test
  @DisplayName("A sequential model reports nothing, and another process' elements never leak in")
  public void aSequentialProcessReportsNothing() {

    final var found = elementsOf("""
            <bpmn:startEvent id="Start" />
            <bpmn:exclusiveGateway id="Gateway_Decision">
              <bpmn:outgoing>Flow_1</bpmn:outgoing>
              <bpmn:outgoing>Flow_2</bpmn:outgoing>
            </bpmn:exclusiveGateway>
            <bpmn:task id="Task_1" />
            <bpmn:task id="Task_2" />
            <bpmn:sequenceFlow id="Flow_1" sourceRef="Gateway_Decision" targetRef="Task_1" />
            <bpmn:sequenceFlow id="Flow_2" sourceRef="Gateway_Decision" targetRef="Task_2" />
        """);

    // the forking parallel gateway of 'OtherProcess' belongs to the other process
    assertTrue(found.isEmpty(), found.toString());

  }

  @Test
  @DisplayName("The elements of a version the ENGINE holds are answered from its model")
  public void aHeldVersionIsWalkedLikeADeployedOne() {

    final var found = elementsOfHeldVersion("""
            <bpmn:parallelGateway id="Gateway_Dropped">
              <bpmn:outgoing>Flow_1</bpmn:outgoing>
              <bpmn:outgoing>Flow_2</bpmn:outgoing>
            </bpmn:parallelGateway>
            <bpmn:serviceTask id="Activity_Approve" camunda:delegateExpression="${approve}" />
            <bpmn:serviceTask id="Activity_Notify" camunda:delegateExpression="${notify}" />
            <bpmn:sequenceFlow id="Flow_1" sourceRef="Gateway_Dropped" targetRef="Activity_Approve" />
            <bpmn:sequenceFlow id="Flow_2" sourceRef="Gateway_Dropped" targetRef="Activity_Notify" />
        """);

    assertEquals(
        List.of("Gateway_Dropped"),
        found,
        "the gateway a newer model dropped keeps forking the workflows started before it");

  }

  @Test
  @DisplayName("A held version without such an element is an empty answer, not 'cannot say'")
  public void aSequentialHeldVersionIsAnEmptyAnswer() {

    final var found = elementsOfHeldVersion("""
            <bpmn:startEvent id="Start" />
            <bpmn:serviceTask id="Activity_Approve" camunda:delegateExpression="${approve}" />
        """);

    assertNotNull(
        found,
        "this adapter reads the model, so null would switch the core's check off although "
            + "the answer is known");
    assertTrue(found.isEmpty(), found.toString());

  }

  /**
   * The same question about version 3 of a process the ENGINE holds, asked the way the
   * core's check asks it: through the version catalog rather than about a model at hand.
   */
  private static Collection<String> elementsOfHeldVersion(
      final String processContent) {

    final var service = new Camunda7DeploymentService(
        "c7", AnEngineHolding.theseModels(PROCESS_ID, Map.of("3", Bpmn.convertToString(model(processContent)))), mock(
            Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
                .builder()
                .build(), new Camunda7TaskRegistry());
    return service
        .processVersionCatalogOf(MODULE, PROCESS_ID)
        .concurrentTokenElementsOfVersion(MODULE, PROCESS_ID, "3");

  }

}
