package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot says about a user task no <code>@WorkflowTask</code> method serves: one line per
 * BPMN process the application claims, at INFO, and nothing at all for a process it does not
 * claim. The boot goes on in every case, which is where this engine parts with Camunda 8.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7UnservedUserTasksTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  private static final String ADAPTER_ID = "c7";

  private ProcessEngine engine;

  @AfterEach
  public void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  /**
   * @param userTasks The user tasks of the process, as BPMN
   * @return A model carrying them
   */
  private static BpmnModelInstance model(
      final String userTasks) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:startEvent id="start"><bpmn:outgoing>toApprove</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="toApprove" sourceRef="start" targetRef="approve" />
        %s
            <bpmn:sequenceFlow id="toEnd" sourceRef="approve" targetRef="done" />
            <bpmn:endEvent id="done"><bpmn:incoming>toEnd</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, userTasks);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private static BpmnModelInstance aUserTaskWithAFormKey() {

    return model("""
            <bpmn:userTask id="approve" name="Approve the loan" camunda:formKey="approveTheLoan">
              <bpmn:incoming>toApprove</bpmn:incoming><bpmn:outgoing>toEnd</bpmn:outgoing>
            </bpmn:userTask>
        """);

  }

  private static BpmnModelInstance aUserTaskWithoutAFormKey() {

    return model("""
            <bpmn:userTask id="approve">
              <bpmn:incoming>toApprove</bpmn:incoming><bpmn:outgoing>toEnd</bpmn:outgoing>
            </bpmn:userTask>
        """);

  }

  /**
   * The core of an application which claims this BPMN process: it answers the name of the
   * workflow aggregate's id for it, which is the question the adapter asks.
   */
  private static WorkflowTaskWiring aCoreClaimingTheProcess() {

    final var wiring = mock(WorkflowTaskWiring.class);
    when(wiring.resolveWorkflowAggregateIdName(anyString(), anyString())).thenReturn("loanId");
    return wiring;

  }

  /**
   * @param taskDefinitions What the application's <code>@WorkflowTask</code> methods are named
   *          after, be it a task definition or an element id
   * @return The core's answer about them
   */
  private static WorkflowTaskInvoker aCoreWithMethodsFor(
      final String... taskDefinitions) {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.workflowTaskHandlerExists(anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> List.of(taskDefinitions).contains(invocation.getArgument(2)));
    return invoker;

  }

  private Camunda7DeploymentService adapter(
      final WorkflowTaskWiring wiring,
      final WorkflowTaskInvoker invoker) {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:unserved-user-tasks-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    engine = configuration.buildProcessEngine();

    final var service = new Camunda7DeploymentService(
        ADAPTER_ID, engine.getRepositoryService(), mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .workflowTaskWiring(wiring)
            .workflowTaskInvoker(invoker)
            .build(), new Camunda7TaskRegistry());
    service.setRuntimeService(engine.getRuntimeService());
    return service;

  }

  /**
   * Runs the stages a model passes through while a workflow module boots, and answers what they
   * wrote.
   */
  private String deploy(
      final CapturedOutput output,
      final Camunda7DeploymentService service,
      final BpmnModelInstance model) {

    final var before = output.getAll().length();
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
    service.wireBpmn(MODULE, FILE, PROCESS, model, context);
    service.deployResources(MODULE, context);
    return output.getAll().substring(before);

  }

  /**
   * What stands in front of the given text on its own line, which is where the level of a log
   * line is.
   *
   * @param logged Everything which was written
   * @param text The text to find
   * @return The beginning of its line
   */
  private static String theLineCarrying(
      final String logged,
      final String text) {

    final var at = logged.indexOf(text);
    assertTrue(at >= 0, () -> "'%s' was never written: %s".formatted(text, logged));
    return logged.substring(logged.lastIndexOf('\n', at) + 1, at);

  }

  @Test
  @DisplayName("A user task no method serves is named once, with its form key and both ways to wire a method")
  public void theUserTaskNobodyServesIsNamed(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(aCoreClaimingTheProcess(), aCoreWithMethodsFor("somethingElse")),
        aUserTaskWithAFormKey());

    assertTrue(
        logged.contains("user task 'approve' (named 'Approve the loan' in the model)"),
        () -> "the element and what a modeller recognises it by: "
            + logged);
    assertTrue(
        logged.contains("whose form key is 'approveTheLoan'"),
        () -> "the form key, which is the task definition of a user task here: "
            + logged);
    assertTrue(
        logged.contains("@WorkflowTask(taskDefinition = \"approveTheLoan\")") && logged
            .contains("@WorkflowTask(id = \"approve\")"),
        () -> "both keys a method may be wired by, because either of them serves it: "
            + logged);
    assertTrue(
        logged.contains("no method of it is called when the task is created, and none when the task is canceled"),
        () -> "what the application loses, which is the whole point of the line: "
            + logged);
    assertTrue(
        logged.contains("nothing to do about it"),
        () -> "and that a model meant that way needs no change: "
            + logged);

  }

  @Test
  @DisplayName("The line is an INFO: the model may be meant that way and the boot goes on")
  public void theLineIsAnInfo(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(aCoreClaimingTheProcess(), aCoreWithMethodsFor("somethingElse")),
        aUserTaskWithAFormKey());

    final var level = theLineCarrying(logged, "BPMN process 'LoanApproval' of workflow module");
    assertTrue(level.contains("INFO"), () -> "an INFO and not a warning: "
        + level);
    assertTrue(
        logged.contains("wired 1 task(s) of BPMN process 'LoanApproval'"),
        () -> "and the wiring ran to its end, so nothing was refused: "
            + logged);

  }

  @Test
  @DisplayName("A user task without a form key is named by its element id alone")
  public void anElementWithoutAFormKeyIsNamedByItsId(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(aCoreClaimingTheProcess(), aCoreWithMethodsFor("somethingElse")),
        aUserTaskWithoutAFormKey());

    assertTrue(
        logged.contains("user task 'approve', which carries no form key"),
        () -> "the element, named without a name the model does not carry: "
            + logged);
    assertTrue(
        logged.contains("@WorkflowTask(id = \"approve\")") && logged.contains("name the method 'approve'"),
        () -> "the two ways left where there is no form key: "
            + logged);
    assertFalse(
        logged.contains("@WorkflowTask(taskDefinition ="),
        () -> "and not a task definition the model never named: "
            + logged);

  }

  @Test
  @DisplayName("A user task a method serves by its form key is not named")
  public void aServedUserTaskIsNotNamed(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(aCoreClaimingTheProcess(), aCoreWithMethodsFor("approveTheLoan")),
        aUserTaskWithAFormKey());

    assertFalse(
        logged.contains("no @WorkflowTask method serves"),
        () -> "which is the ordinary case and says nothing: "
            + logged);

  }

  @Test
  @DisplayName("A user task a method serves by its element id is not named either")
  public void aUserTaskServedByItsElementIdIsNotNamed(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(aCoreClaimingTheProcess(), aCoreWithMethodsFor("approve")),
        aUserTaskWithAFormKey());

    assertFalse(
        logged.contains("no @WorkflowTask method serves"),
        () -> "the element id is the second key a method is wired by, and asking by the form key "
            + "alone would name a task which is served: "
            + logged);

  }

  @Test
  @DisplayName("A process no @WorkflowService class claims is not reported about")
  public void anUnclaimedProcessSaysNothing(
      final CapturedOutput output) {

    final var unclaimed = mock(WorkflowTaskWiring.class);
    when(unclaimed.resolveWorkflowAggregateIdName(anyString(), anyString()))
        .thenThrow(new IllegalStateException("No @WorkflowService class is registered"));

    final var logged = deploy(
        output,
        adapter(unclaimed, aCoreWithMethodsFor("somethingElse")),
        aUserTaskWithAFormKey());

    assertFalse(
        logged.contains("no @WorkflowTask method serves"),
        () -> "no method of this application was meant to serve its tasks, so there is nothing to "
            + "ask of the reader: "
            + logged);

  }

  @Test
  @DisplayName("A core which answers no aggregate name says the process is unclaimed as well")
  public void aCoreAnsweringNothingMeansUnclaimed(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(mock(WorkflowTaskWiring.class), aCoreWithMethodsFor("somethingElse")),
        aUserTaskWithAFormKey());

    assertFalse(
        logged.contains("no @WorkflowTask method serves"),
        () -> "an answer of null is the same answer as a refusal to answer: "
            + logged);

  }

}
