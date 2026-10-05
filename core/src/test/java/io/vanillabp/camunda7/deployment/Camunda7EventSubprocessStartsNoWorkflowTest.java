package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.delegate.BaseDelegateExecution;
import org.camunda.bpm.engine.delegate.DelegateListener;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7AsyncBpmnParseListener;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * A start event inside an event subprocess is no start of a WORKFLOW. It fires while the
 * workflow is already running and already has its aggregate, so the application owes it
 * neither a <code>&#64;WorkflowStartedByBpms</code> method nor a second aggregate.
 * <p>
 * Both places which read a start event are held against that here: the deployment tells
 * the core which starts a BPMN process has, and the parse listener decides which start
 * event of the engine's process definition gets the listener building an aggregate.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7EventSubprocessStartsNoWorkflowTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS_ID = "loan_approval";

  private static final String PROCESS_START = "Event_nightly";

  private static final String EVENT_SUBPROCESS_START = "Event_creditRefused";

  private static final String WAITING_PROCESS_ID = "loan_review";

  private static final String WAITING_EVENT_SUBPROCESS_START = "Event_customerCalled";

  private ProcessEngine engine;

  @AfterEach
  public void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  /**
   * A process the engine starts every night, with an event subprocess the engine also
   * fires on its own once the workflow runs.
   */
  private static final String A_PROCESS_WITH_AN_EVENT_SUBPROCESS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="loan_approval" isExecutable="true" camunda:historyTimeToLive="P1D">
          <bpmn:startEvent id="Event_nightly">
            <bpmn:outgoing>Flow_toCheck</bpmn:outgoing>
            <bpmn:timerEventDefinition id="Timer_nightly">
              <bpmn:timeCycle>R/PT24H</bpmn:timeCycle>
            </bpmn:timerEventDefinition>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="Flow_toCheck" sourceRef="Event_nightly" targetRef="Activity_check" />
          <bpmn:serviceTask id="Activity_check" camunda:expression="${checkCredit}">
            <bpmn:incoming>Flow_toCheck</bpmn:incoming>
            <bpmn:outgoing>Flow_toEnd</bpmn:outgoing>
          </bpmn:serviceTask>
          <bpmn:sequenceFlow id="Flow_toEnd" sourceRef="Activity_check" targetRef="Event_done" />
          <bpmn:endEvent id="Event_done">
            <bpmn:incoming>Flow_toEnd</bpmn:incoming>
          </bpmn:endEvent>
          <bpmn:subProcess id="Activity_takeOver" triggeredByEvent="true">
            <bpmn:startEvent id="Event_creditRefused" isInterrupting="true">
              <bpmn:outgoing>Flow_toTakenOver</bpmn:outgoing>
              <bpmn:timerEventDefinition id="Timer_refused">
                <bpmn:timeDuration>PT1H</bpmn:timeDuration>
              </bpmn:timerEventDefinition>
            </bpmn:startEvent>
            <bpmn:sequenceFlow id="Flow_toTakenOver" sourceRef="Event_creditRefused" targetRef="Event_takenOver" />
            <bpmn:endEvent id="Event_takenOver">
              <bpmn:incoming>Flow_toTakenOver</bpmn:incoming>
            </bpmn:endEvent>
          </bpmn:subProcess>
        </bpmn:process>
      </bpmn:definitions>
      """;

  @Test
  @DisplayName("The core hears about the timer of the process and not about the timer of its event subprocess")
  public void onlyTheStartEventOfTheProcessIsReported() {

    final var startEvents = startEventsReportedToTheCore();

    assertEquals(
        List.of(BpmsInitiatedStartSpec.of(PROCESS_START, BpmsStartTrigger.Kind.TIMER)),
        List.copyOf(startEvents),
        () -> "the event subprocess fires inside a running workflow, so it starts none: "
            + startEvents);

  }

  @Test
  @DisplayName("The listener building an aggregate goes onto the start event of the process and onto no other")
  public void onlyTheStartEventOfTheProcessCarriesTheListener() {

    final var startListener = mock(ExecutionListener.class);
    engine = anEngineParsingWith(new Camunda7AsyncBpmnParseListener(null, null, kind -> startListener));

    engine
        .getRepositoryService()
        .createDeployment()
        .addString("loan-approval.bpmn", A_PROCESS_WITH_AN_EVENT_SUBPROCESS)
        .deploy();

    assertTrue(
        listenersOf(PROCESS_START).contains(startListener),
        "the workflow the engine starts every night needs its aggregate built");
    assertFalse(
        listenersOf(EVENT_SUBPROCESS_START).contains(startListener),
        "an aggregate built here would be the second one of a workflow which already has "
            + "its own");

  }

  /**
   * A process the application starts, which waits at a timer it never reaches the end of
   * because an event subprocess takes it over on a signal.
   */
  private static final String A_WORKFLOW_AN_EVENT_SUBPROCESS_TAKES_OVER = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_2" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:signal id="Signal_called" name="CustomerCalled" />
        <bpmn:process id="loan_review" isExecutable="true" camunda:historyTimeToLive="P1D">
          <bpmn:startEvent id="Event_started">
            <bpmn:outgoing>Flow_toWait</bpmn:outgoing>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="Flow_toWait" sourceRef="Event_started" targetRef="Event_wait" />
          <bpmn:intermediateCatchEvent id="Event_wait">
            <bpmn:incoming>Flow_toWait</bpmn:incoming>
            <bpmn:outgoing>Flow_toNotReached</bpmn:outgoing>
            <bpmn:timerEventDefinition id="Timer_wait">
              <bpmn:timeDuration>PT1H</bpmn:timeDuration>
            </bpmn:timerEventDefinition>
          </bpmn:intermediateCatchEvent>
          <bpmn:sequenceFlow id="Flow_toNotReached" sourceRef="Event_wait" targetRef="Event_notReached" />
          <bpmn:endEvent id="Event_notReached">
            <bpmn:incoming>Flow_toNotReached</bpmn:incoming>
          </bpmn:endEvent>
          <bpmn:subProcess id="Activity_takeOver" triggeredByEvent="true">
            <bpmn:startEvent id="Event_customerCalled" isInterrupting="true">
              <bpmn:outgoing>Flow_toTakenOver</bpmn:outgoing>
              <bpmn:signalEventDefinition id="SignalDefinition_called" signalRef="Signal_called" />
            </bpmn:startEvent>
            <bpmn:sequenceFlow id="Flow_toTakenOver" sourceRef="Event_customerCalled" targetRef="Event_takenOver" />
            <bpmn:endEvent id="Event_takenOver">
              <bpmn:incoming>Flow_toTakenOver</bpmn:incoming>
            </bpmn:endEvent>
          </bpmn:subProcess>
        </bpmn:process>
      </bpmn:definitions>
      """;

  @Test
  @DisplayName("The event subprocess still takes the running workflow over, and its start event tells VanillaBP nothing")
  public void theEventSubprocessStillFires() {

    // which start events the listener was notified at: the plain one of the process is
    // among them since story 653, the one of the event subprocess is not
    final var notifiedAt = new java.util.ArrayList<String>();
    final ExecutionListener startListener = execution -> notifiedAt.add(execution.getCurrentActivityId());
    engine = anEngineParsingWith(new Camunda7AsyncBpmnParseListener(null, null, kind -> startListener));
    engine
        .getRepositoryService()
        .createDeployment()
        .addString("loan-review.bpmn", A_WORKFLOW_AN_EVENT_SUBPROCESS_TAKES_OVER)
        .deploy();
    final var workflow = engine
        .getRuntimeService()
        .startProcessInstanceByKey(WAITING_PROCESS_ID);

    engine.getRuntimeService().signalEventReceived("CustomerCalled");

    assertEquals(
        1,
        engine
            .getHistoryService()
            .createHistoricActivityInstanceQuery()
            .processInstanceId(workflow.getId())
            .activityId(WAITING_EVENT_SUBPROCESS_START)
            .count(),
        "the event subprocess heard the signal and ran");
    assertNull(
        engine
            .getRuntimeService()
            .createProcessInstanceQuery()
            .processInstanceId(workflow.getId())
            .singleResult(),
        "it interrupts, so the workflow it took over is done");
    assertEquals(
        List.of("Event_started"),
        notifiedAt,
        () -> "the plain start event of the process reports its start, the one of the event "
            + "subprocess reports none: "
            + notifiedAt);

  }

  /**
   * The start listeners the engine parsed onto one element of the deployed process.
   *
   * @param elementId The BPMN element to read them from
   * @return What fires when the engine enters that element
   */
  private List<DelegateListener<? extends BaseDelegateExecution>> listenersOf(
      final String elementId) {

    final var configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    final var definitionId = engine
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(PROCESS_ID)
        .singleResult()
        .getId();
    // the parsed process definition is read through a command: the engine's deployment
    // cache asks the command context for the definition it does not hold yet
    return configuration
        .getCommandExecutorTxRequired()
        .execute(commandContext -> {
          final ActivityImpl activity = commandContext
              .getProcessEngineConfiguration()
              .getDeploymentCache()
              .findDeployedProcessDefinitionById(definitionId)
              .findActivity(elementId);
          return List.copyOf(activity.getListeners(ExecutionListener.EVENTNAME_START));
        });

  }

  /**
   * An engine which parses models with the given listener in place and runs nothing: the
   * job executor stays off, so the nightly timer of the model creates a job and nobody
   * picks it up.
   *
   * @param parseListener What sees every element while the engine parses it
   * @return The engine, to be closed by the test
   */
  private static ProcessEngine anEngineParsingWith(
      final Camunda7AsyncBpmnParseListener parseListener) {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName("event-subprocess-test");
    configuration.setJobExecutorActivate(false);
    configuration.setCustomPreBPMNParseListeners(new java.util.ArrayList<>(List.of(parseListener)));
    return configuration.buildProcessEngine();

  }

  /**
   * What the deployment reports to the core about the start events of that model, read
   * through the walk a version the engine holds goes through - the same walk a model this
   * boot deploys goes through.
   *
   * @return The start events the core judges the application's methods by
   */
  private static Collection<BpmsInitiatedStartSpec> startEventsReportedToTheCore() {

    final var service = new Camunda7DeploymentService(
        "c7", AnEngineHolding.theseModels(PROCESS_ID, Map.of("3", A_PROCESS_WITH_AN_EVENT_SUBPROCESS)), mock(
            Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
                .builder()
                .build(), new Camunda7TaskRegistry());
    return service
        .processVersionCatalogOf(MODULE, PROCESS_ID)
        .startEventsOfVersion(MODULE, PROCESS_ID, "3");

  }

}
