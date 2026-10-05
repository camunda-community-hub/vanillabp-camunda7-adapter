package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

import java.util.Collection;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * What the engine starts on its own in a version it still HOLDS. The BPMN process id a
 * renamed process left behind is declared without a model, so nothing wires it while this
 * application boots and the <code>&#64;WorkflowStartedByBpms</code> methods kept for it
 * are judged by nothing - while the engine keeps firing the old model's timer and keeps
 * matching its signal subscription.
 * <p>
 * The answer is the same walk a model this boot deploys goes through, so the core hears
 * about a start event of an old version in the words it hears about a new one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7StartEventsOfHeldVersionsTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS_ID = "loan_approval";

  /**
   * The version which fires every night, next to a process of the same file which fires
   * as well - only the one asked about belongs in the answer.
   */
  private static final String A_VERSION_STARTED_BY_A_TIMER = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_Old" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="loan_approval" isExecutable="true">
          <bpmn:startEvent id="Event_nightly">
            <bpmn:timerEventDefinition id="Timer_1">
              <bpmn:timeCycle>R/PT24H</bpmn:timeCycle>
            </bpmn:timerEventDefinition>
          </bpmn:startEvent>
          <bpmn:serviceTask id="Activity_check" camunda:expression="${checkCredit}" />
        </bpmn:process>
        <bpmn:process id="loan_reporting" isExecutable="true">
          <bpmn:startEvent id="Event_monthly">
            <bpmn:timerEventDefinition id="Timer_2">
              <bpmn:timeCycle>R/P1M</bpmn:timeCycle>
            </bpmn:timerEventDefinition>
          </bpmn:startEvent>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * The version whose subscription still matches a broadcast.
   */
  private static final String A_VERSION_STARTED_BY_A_SIGNAL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_Old" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:signal id="Signal_1" name="loan_rejected" />
        <bpmn:process id="loan_approval" isExecutable="true">
          <bpmn:startEvent id="Event_rejected">
            <bpmn:signalEventDefinition id="SignalDefinition_1" signalRef="Signal_1" />
          </bpmn:startEvent>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * The version with nothing but a plain start event.
   */
  private static final String A_VERSION_THE_APPLICATION_STARTS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_Old" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="loan_approval" isExecutable="true">
          <bpmn:startEvent id="Event_started" />
          <bpmn:serviceTask id="Activity_check" camunda:expression="${checkCredit}" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  @Test
  @DisplayName("A timer start event of a version the engine holds is reported, and only the asked process is")
  public void theTimerOfAHeldVersionIsReported() {

    final var startEvents = startEventsOfHeldVersion(A_VERSION_STARTED_BY_A_TIMER);

    assertEquals(
        1,
        startEvents.size(),
        () -> "the timer of the process asked about, and nothing of the process next to it: "
            + startEvents);
    final var timer = startEvents.iterator().next();
    assertEquals("Event_nightly", timer.elementId());
    assertEquals(
        BpmsStartTrigger.Kind.TIMER,
        timer.kind(),
        "a @WorkflowStartedByBpms method kept for the old id names the kind it serves");

  }

  @Test
  @DisplayName("A signal start event of a held version is reported with the PLAIN signal name")
  public void theSignalOfAHeldVersionIsReportedPlain() {

    final var startEvents = startEventsOfHeldVersion(A_VERSION_STARTED_BY_A_SIGNAL);

    final var signal = startEvents.iterator().next();
    assertEquals("Event_rejected", signal.elementId());
    assertEquals(BpmsStartTrigger.Kind.SIGNAL, signal.kind());
    assertEquals(
        "loan_rejected",
        signal.signalName(),
        "name-clash avoidance stays invisible above the SPI boundary, so what the model was "
            + "deployed under is not what the application is told");

  }

  @Test
  @DisplayName("A version with a plain start event answers that one, not 'cannot say'")
  public void aVersionWithoutSuchAStartEventIsAnEmptyAnswer() {

    final var startEvents = startEventsOfHeldVersion(A_VERSION_THE_APPLICATION_STARTS);

    assertNotNull(
        startEvents,
        "this adapter can read the model, so null would switch the core's judgement off "
            + "although the answer is known");
    assertEquals(
        1,
        startEvents.size(),
        () -> "every start event of the model is reported, the plain one included: "
            + startEvents);
    final var plain = startEvents.iterator().next();
    assertEquals("Event_started", plain.elementId());
    assertEquals(
        BpmsStartTrigger.Kind.NONE,
        plain.kind(),
        "the kind is what tells the core it may not demand a method for this one");

  }

  /**
   * Reads the start events of version 3 of a process the engine holds, through the
   * version catalog the core asks for a declared BPMN process id.
   */
  private static Collection<BpmsInitiatedStartSpec> startEventsOfHeldVersion(
      final String model) {

    final var service = new Camunda7DeploymentService(
        "c7", AnEngineHolding.theseModels(PROCESS_ID, Map.of("3", model)), mock(
            Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
                .builder()
                .build(), new Camunda7TaskRegistry());
    return service
        .processVersionCatalogOf(MODULE, PROCESS_ID)
        .startEventsOfVersion(MODULE, PROCESS_ID, "3");

  }

}
