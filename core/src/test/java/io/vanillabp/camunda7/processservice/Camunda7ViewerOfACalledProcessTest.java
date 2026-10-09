package io.vanillabp.camunda7.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the viewer reports about a called process: the definition it RAN on, and only for a
 * called instance of the workflow which was asked about.
 * <p>
 * The engines are real ones, in memory: one with the history this adapter usually reads, and one
 * with history level {@code none}, where the viewer falls back to what is running.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7ViewerOfACalledProcessTest {

  private static final String MODULE = "viewer-called-module";

  private static final String CALLER = "ViewedCaller";

  private static final String CHILD = "ViewedChild";

  private ProcessEngine engine;

  private Camunda7WorkflowViewer viewer;

  private static String caller() {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
            id="caller-defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:startEvent id="start"><bpmn:outgoing>f0</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="f0" sourceRef="start" targetRef="beforeTheCall" />
            <bpmn:userTask id="beforeTheCall"><bpmn:incoming>f0</bpmn:incoming><bpmn:outgoing>f1</bpmn:outgoing></bpmn:userTask>
            <bpmn:sequenceFlow id="f1" sourceRef="beforeTheCall" targetRef="callChild" />
            <bpmn:callActivity id="callChild" calledElement="%s">
              <bpmn:extensionElements>
                <camunda:in businessKey="#{execution.processBusinessKey}" />
              </bpmn:extensionElements>
              <bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing>
            </bpmn:callActivity>
            <bpmn:sequenceFlow id="f2" sourceRef="callChild" targetRef="end" />
            <bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(CALLER, CHILD);

  }

  /**
   * @param waitAt The id of the user task the child waits at, which is what makes two versions
   *          of it different
   */
  private static String child(
      final String waitAt) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            id="child-defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="%s" />
            <bpmn:userTask id="%s"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:userTask>
            <bpmn:sequenceFlow id="f2" sourceRef="%s" targetRef="end" />
            <bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """.formatted(CHILD, waitAt, waitAt, waitAt);

  }

  private void bootAnEngine(
      final String historyLevel) {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration
        .setJdbcUrl("jdbc:h2:mem:viewer-called-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    configuration.setHistory(historyLevel);
    // the engine refuses to parse a model without one, and a test needs no cleanup policy
    configuration.setHistoryTimeToLive("P30D");
    engine = configuration.buildProcessEngine();
    deploy(CALLER, caller());
    deploy(CHILD, child("firstVersionWaits"));
    viewer = new Camunda7WorkflowViewer(
        "c7", engine.getRepositoryService(), engine.getHistoryService(), engine.getRuntimeService());

  }

  private void deploy(
      final String processId,
      final String bpmn) {

    engine
        .getRepositoryService()
        .createDeployment()
        .tenantId(MODULE)
        .addString(processId
            + ".bpmn", bpmn)
        .deploy();

  }

  /**
   * Starts a workflow which waits in front of its call activity.
   */
  private String startBeforeTheCall(
      final String aggregateId) {

    return engine
        .getRuntimeService()
        .createProcessInstanceByKey(CALLER)
        .processDefinitionTenantId(MODULE)
        .businessKey(aggregateId)
        .execute()
        .getId();

  }

  /**
   * Starts a workflow and lets it reach the called process.
   */
  private String start(
      final String aggregateId) {

    final var callerInstanceId = startBeforeTheCall(aggregateId);
    final var taskService = engine.getTaskService();
    taskService
        .complete(taskService
            .createTaskQuery()
            .processInstanceId(callerInstanceId)
            .singleResult()
            .getId());
    return callerInstanceId;

  }

  private String childOf(
      final String callerInstanceId) {

    return engine
        .getRuntimeService()
        .createProcessInstanceQuery()
        .superProcessInstanceId(callerInstanceId)
        .singleResult()
        .getId();

  }

  @AfterEach
  public void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  @Test
  @DisplayName("The called process is reported in the version the workflow ran, not in the latest one")
  public void theCalledProcessIsReportedInTheVersionItRan() {

    bootAnEngine("full");
    start("1");
    // a newer version of the called process arrives while the workflow waits in the first one
    deploy(CHILD, child("secondVersionWaits"));

    final var definitions = viewer.getProcessDefinitions(MODULE, CALLER, MODULE, "1", null);

    assertEquals(2, definitions.size(), () -> "the caller and its called process, but got "
        + definitions);
    final var called = definitions.get(1);
    assertEquals(CHILD, called.bpmnProcessId());
    assertEquals(List.of("callChild"), called.usedByElements());
    assertEquals(
        "1",
        called.version(),
        "the workflow runs the first version of the child, so a viewer drawing the second one shows tasks which"
            + " never ran");

  }

  @Test
  @DisplayName("A call activity which has not called anything yet reports the version it would call")
  public void aCallActivityWhichDidNotRunReportsTheLatestVersion() {

    bootAnEngine("full");
    startBeforeTheCall("1");
    deploy(CHILD, child("secondVersionWaits"));

    final var definitions = viewer.getProcessDefinitions(MODULE, CALLER, MODULE, "1", null);

    assertEquals(2, definitions.size(), () -> "the caller and its called process, but got "
        + definitions);
    assertEquals("2", definitions.get(1).version(), "nothing ran yet, so the version called next is the answer");

  }

  @Test
  @DisplayName("Without history the called process is still reported in the version the workflow ran")
  public void withoutHistoryTheCalledProcessIsReportedInTheVersionItRan() {

    bootAnEngine("none");
    start("1");
    deploy(CHILD, child("secondVersionWaits"));

    final var definitions = viewer.getProcessDefinitions(MODULE, CALLER, MODULE, "1", null);

    assertEquals(2, definitions.size(), () -> "the caller and its called process, but got "
        + definitions);
    assertEquals("1", definitions.get(1).version());

  }

  @Test
  @DisplayName("Without history a called instance of another workflow is not shown")
  public void withoutHistoryACalledInstanceOfAnotherWorkflowIsRejected() {

    bootAnEngine("none");
    final var ownWorkflow = start("1");
    final var otherWorkflow = start("2");

    assertNotNull(
        viewer.getWorkflowHistory(MODULE, CALLER, MODULE, "1", childOf(ownWorkflow)),
        "a called instance of the workflow asked about is shown");
    assertNull(
        viewer.getWorkflowHistory(MODULE, CALLER, MODULE, "1", childOf(otherWorkflow)),
        "the called instance belongs to the workflow of aggregate '2', and the history check has always rejected"
            + " it. Without history the same context has to be rejected as well");
    assertEquals(
        List.of(),
        viewer.getProcessDefinitions(MODULE, CALLER, MODULE, "1", childOf(otherWorkflow)),
        "the definitions of a foreign called instance are not shown either");

  }

}
