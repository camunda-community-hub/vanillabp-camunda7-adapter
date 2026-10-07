package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstanceQuery;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.Camunda7ProcessingContext;
import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter says about a standard loop. The engine runs such an activity once and
 * says nothing (see {@code Camunda7StandardLoopTest}), so a model this boot deploys is refused
 * where the application claims its process, and a warning where nobody does. A version the
 * engine already holds is a warning where workflows still run on it.
 * <p>
 * Every refusal stands next to the model which must NOT be refused: a multi-instance element
 * is the form which does repeat an activity, and the message points to it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7StandardLoopRefusalTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  private static final String OLD_ID = "loan_approval";

  /**
   * The words the warning about a held version starts its verdict with. The positive and the
   * negative assertion below both quote it.
   */
  private static final String THE_WARNING = "carries a standard loop";

  private static final String A_TASK_WITH_A_STANDARD_LOOP = """
          <bpmn:serviceTask id="Activity_remind" camunda:delegateExpression="${remind}">
            <bpmn:standardLoopCharacteristics loopMaximum="3">
              <bpmn:loopCondition xsi:type="bpmn:tFormalExpression">${true}</bpmn:loopCondition>
            </bpmn:standardLoopCharacteristics>
          </bpmn:serviceTask>
      """;

  private static final String A_TASK_IN_A_SUBPROCESS_WITH_A_STANDARD_LOOP = """
          <bpmn:subProcess id="Subprocess_reminders">
            <bpmn:serviceTask id="Activity_remind" camunda:delegateExpression="${remind}">
              <bpmn:standardLoopCharacteristics />
            </bpmn:serviceTask>
          </bpmn:subProcess>
      """;

  private static final String A_MULTI_INSTANCE_TASK = """
          <bpmn:serviceTask id="Activity_remind" camunda:delegateExpression="${remind}">
            <bpmn:multiInstanceLoopCharacteristics isSequential="true" camunda:collection="${reminders}" camunda:elementVariable="reminder" />
          </bpmn:serviceTask>
      """;

  private static final String A_PLAIN_TASK = """
          <bpmn:serviceTask id="Activity_remind" camunda:delegateExpression="${remind}" />
      """;

  /**
   * One file with the process under test and a second process carrying a standard loop, which
   * is the finding of that other process only.
   */
  private static BpmnModelInstance model(
      final String processContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
          <bpmn:process id="OtherProcess" isExecutable="true">
            <bpmn:task id="Activity_ofTheOtherProcess">
              <bpmn:standardLoopCharacteristics />
            </bpmn:task>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, processContent);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private static void deploy(
      final BpmnModelInstance model) {

    deploy(model, true);

  }

  /**
   * Deploys the model with a core in which a <code>&#64;WorkflowService</code> class claims the
   * process or none does. A claimed process is one whose workflow aggregate the core knows.
   */
  private static void deploy(
      final BpmnModelInstance model,
      final boolean claimed) {

    final var core = mock(WorkflowTaskWiring.class);
    if (claimed) {
      when(core.resolveWorkflowAggregateIdName(MODULE, PROCESS)).thenReturn("loanId");
    }
    final var service = new Camunda7DeploymentService(
        "c7", null, mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .workflowTaskWiring(core)
            .build(), new Camunda7TaskRegistry());
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
    service.wireBpmn(MODULE, FILE, PROCESS, model, context);

  }

  @Test
  @DisplayName("A standard loop ends the deployment and the message names the two forms which repeat")
  public void aStandardLoopEndsTheDeployment() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> deploy(model(A_TASK_WITH_A_STANDARD_LOOP)));

    final var message = refused.getMessage();
    assertTrue(
        message.contains("BPMN process 'LoanApproval' of workflow module 'loan-approval'"),
        () -> "the process and the workflow module: "
            + message);
    assertTrue(
        message.contains("the activity 'Activity_remind'"),
        () -> "the element a modeller has to find: "
            + message);
    assertTrue(
        message.contains("runs the activity once"),
        () -> "what the engine does instead: "
            + message);
    assertTrue(
        message.contains("loop in the sequence flow"),
        () -> "the first form which repeats: "
            + message);
    assertTrue(
        message.contains("@MultiInstanceElement, @MultiInstanceIndex and @MultiInstanceTotal"),
        () -> "and the second one, with what a handler reads there: "
            + message);
    assertFalse(
        message.contains("Activity_ofTheOtherProcess"),
        () -> "the other process of the file is a finding of its own: "
            + message);

  }

  @Test
  @DisplayName("A standard loop in a process nobody claims is a warning, and the boot goes on")
  public void aStandardLoopNobodyClaimsIsAWarning(
      final CapturedOutput output) {

    assertDoesNotThrow(() -> deploy(model(A_TASK_WITH_A_STANDARD_LOOP), false));

    final var logged = output.getAllOfThisTest();
    assertTrue(
        logged.contains("BPMN process 'LoanApproval' of workflow module 'loan-approval' carries a standard loop"),
        () -> "the process and the module: "
            + logged);
    assertTrue(
        logged.contains("No @WorkflowService class of this application claims this process"),
        () -> "and why the boot goes on: "
            + logged);

  }

  @Test
  @DisplayName("A standard loop inside a subprocess is found as well")
  public void aStandardLoopInsideASubprocessIsFound() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> deploy(model(A_TASK_IN_A_SUBPROCESS_WITH_A_STANDARD_LOOP)));

    assertTrue(
        refused.getMessage().contains("the activity 'Activity_remind'"),
        refused::getMessage);

  }

  @Test
  @DisplayName("A multi-instance element deploys")
  public void aMultiInstanceElementDeploys() {

    assertDoesNotThrow(() -> deploy(model(A_MULTI_INSTANCE_TASK)));

  }

  @Test
  @DisplayName("A held version with a standard loop is a warning while workflows still run on it")
  public void aHeldVersionWithWorkflowsIsAWarning(
      final CapturedOutput output) {

    final var service = adapterHolding(Map.of("1", heldModel(A_TASK_WITH_A_STANDARD_LOOP)), 2L);

    assertDoesNotThrow(
        () -> service.startWorkflowProcessing(MODULE, new Camunda7ProcessingContext(MODULE)),
        "nobody can change a model the engine holds, so the boot goes on");

    final var logged = output.getAllOfThisTest();
    assertTrue(
        logged.contains("Version '1' of BPMN process 'loan_approval' (workflow module 'loan-approval') "
            + THE_WARNING),
        () -> "the version, the process and the module: "
            + logged);
    assertTrue(
        logged.contains("the activity 'Activity_remind', and 2 workflows still run on it"),
        () -> "the element and how many workflows are on it: "
            + logged);

  }

  @Test
  @DisplayName("A held version no workflow runs on any more is passed over")
  public void aHeldVersionWithoutWorkflowsIsPassedOver(
      final CapturedOutput output) {

    final var service = adapterHolding(
        Map.of("1", heldModel(A_TASK_WITH_A_STANDARD_LOOP), "2", heldModel(A_PLAIN_TASK)), 0L);

    service.startWorkflowProcessing(MODULE, new Camunda7ProcessingContext(MODULE));

    final var logged = output.getAllOfThisTest();
    assertFalse(
        logged.contains(THE_WARNING),
        () -> "a version nothing runs on can do no harm: "
            + logged);

  }

  @Test
  @DisplayName("Only the activities of the process asked about are reported")
  public void onlyTheActivitiesOfTheProcessAreReported() {

    assertEquals(
        List.of(),
        Camunda7StandardLoops.elementIdsOf(model(A_MULTI_INSTANCE_TASK), PROCESS));
    assertEquals(
        List.of("Activity_ofTheOtherProcess"),
        Camunda7StandardLoops.elementIdsOf(model(A_MULTI_INSTANCE_TASK), "OtherProcess"));

  }

  private static String heldModel(
      final String processContent) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="Definitions_Old" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="loan_approval" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(processContent);

  }

  /**
   * An adapter whose core declares one BPMN process id without a model, whose engine holds the
   * given models under it, and where the given number of workflows runs on each version.
   */
  private static Camunda7DeploymentService adapterHolding(
      final Map<String, String> modelsByVersion,
      final long running) {

    final var core = mock(WorkflowTaskWiring.class);
    when(core.taskWiringOfProcessesNobodyDeployed(MODULE))
        .thenReturn(Map.of(OLD_ID, List.<String>of("remind")));
    final var query = mock(ProcessInstanceQuery.class, RETURNS_SELF);
    final var runtime = mock(RuntimeService.class);
    when(query.count()).thenReturn(running);
    when(runtime.createProcessInstanceQuery()).thenReturn(query);
    final var service = new Camunda7DeploymentService(
        "c7", AnEngineHolding.theseModels(OLD_ID, modelsByVersion), mock(
            Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
                .builder()
                .workflowTaskWiring(core)
                .build(), new Camunda7TaskRegistry());
    service.setRuntimeService(runtime);
    return service;

  }

}
