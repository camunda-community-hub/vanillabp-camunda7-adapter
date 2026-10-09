package io.vanillabp.camunda7.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The process service of a workflow writes values into a called process as well, and each
 * process may have a serialization format of its own
 * ({@code vanillabp.workflow-modules.<module>.workflows.<process>.adapters.<id>.serialization-format}).
 * <p>
 * The operations are called on the process service of the process at the top, so the request
 * names that process. A task, a waiting message or a scope of the called process belongs to the
 * called process, though, and so does the format its values are written in. The task path, where
 * the engine hands a task to the application, has always used the format of the process the task
 * belongs to. These tests hold the operations of the process service to the same rule.
 * <p>
 * The engine is a real one, in memory. The format resolution is a recorder, because the engine
 * here knows only Java serialization, and every format would read the same.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7SettingsOfACalledProcessTest {

  private static final String MODULE = "called-settings-module";

  private static final String CALLER = "SettingsCaller";

  private static final String CHILD = "SettingsChild";

  private static final String AGGREGATE_ID = "4711";

  private ProcessEngine engine;

  private Camunda7ProcessService<Object> processService;

  /**
   * Every process the format was asked for, in the order the adapter asked.
   */
  private final List<String> processesAskedForTheirFormat = new CopyOnWriteArrayList<>();

  /**
   * The process at the top: it calls the child and passes the business key, which is what the
   * deployment adds to a call activity whose called process shares the workflow aggregate.
   */
  private static String caller() {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
            id="caller-defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="callChild" />
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
        """.formatted(CALLER, CHILD);

  }

  /**
   * The called process: four branches which wait at the same time, each for one of the
   * operations under test. A task the application completes or cancels, a user task, a message,
   * and a gateway waiting for a value the aggregate shares.
   */
  private static String child() {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            id="child-defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:message id="hello" name="childHello" />
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:startEvent id="start"><bpmn:outgoing>f0</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="f0" sourceRef="start" targetRef="split" />
            <bpmn:parallelGateway id="split">
              <bpmn:incoming>f0</bpmn:incoming>
              <bpmn:outgoing>toWork</bpmn:outgoing><bpmn:outgoing>toReview</bpmn:outgoing>
              <bpmn:outgoing>toHello</bpmn:outgoing><bpmn:outgoing>toWait</bpmn:outgoing>
            </bpmn:parallelGateway>

            <bpmn:sequenceFlow id="toWork" sourceRef="split" targetRef="childWork" />
            <bpmn:receiveTask id="childWork"><bpmn:incoming>toWork</bpmn:incoming><bpmn:outgoing>workDone</bpmn:outgoing></bpmn:receiveTask>
            <bpmn:sequenceFlow id="workDone" sourceRef="childWork" targetRef="endWork" />
            <bpmn:endEvent id="endWork"><bpmn:incoming>workDone</bpmn:incoming></bpmn:endEvent>

            <bpmn:sequenceFlow id="toReview" sourceRef="split" targetRef="childReview" />
            <bpmn:userTask id="childReview"><bpmn:incoming>toReview</bpmn:incoming><bpmn:outgoing>reviewDone</bpmn:outgoing></bpmn:userTask>
            <bpmn:sequenceFlow id="reviewDone" sourceRef="childReview" targetRef="endReview" />
            <bpmn:endEvent id="endReview"><bpmn:incoming>reviewDone</bpmn:incoming></bpmn:endEvent>

            <bpmn:sequenceFlow id="toHello" sourceRef="split" targetRef="childHello" />
            <bpmn:intermediateCatchEvent id="childHello">
              <bpmn:incoming>toHello</bpmn:incoming><bpmn:outgoing>helloDone</bpmn:outgoing>
              <bpmn:messageEventDefinition messageRef="hello" />
            </bpmn:intermediateCatchEvent>
            <bpmn:sequenceFlow id="helloDone" sourceRef="childHello" targetRef="endHello" />
            <bpmn:endEvent id="endHello"><bpmn:incoming>helloDone</bpmn:incoming></bpmn:endEvent>

            <bpmn:sequenceFlow id="toWait" sourceRef="split" targetRef="childWaits" />
            <bpmn:eventBasedGateway id="childWaits">
              <bpmn:incoming>toWait</bpmn:incoming>
              <bpmn:outgoing>toReady</bpmn:outgoing><bpmn:outgoing>toTimeout</bpmn:outgoing>
            </bpmn:eventBasedGateway>
            <bpmn:sequenceFlow id="toReady" sourceRef="childWaits" targetRef="childReady" />
            <bpmn:intermediateCatchEvent id="childReady">
              <bpmn:incoming>toReady</bpmn:incoming><bpmn:outgoing>readyDone</bpmn:outgoing>
              <bpmn:conditionalEventDefinition>
                <bpmn:condition xsi:type="bpmn:tFormalExpression"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">${execution.getVariable('ready') == true}</bpmn:condition>
              </bpmn:conditionalEventDefinition>
            </bpmn:intermediateCatchEvent>
            <bpmn:sequenceFlow id="readyDone" sourceRef="childReady" targetRef="endReady" />
            <bpmn:endEvent id="endReady"><bpmn:incoming>readyDone</bpmn:incoming></bpmn:endEvent>
            <bpmn:sequenceFlow id="toTimeout" sourceRef="childWaits" targetRef="childTimeout" />
            <bpmn:intermediateCatchEvent id="childTimeout">
              <bpmn:incoming>toTimeout</bpmn:incoming><bpmn:outgoing>timeoutDone</bpmn:outgoing>
              <bpmn:timerEventDefinition><bpmn:timeDuration>PT1H</bpmn:timeDuration></bpmn:timerEventDefinition>
            </bpmn:intermediateCatchEvent>
            <bpmn:sequenceFlow id="timeoutDone" sourceRef="childTimeout" targetRef="endTimeout" />
            <bpmn:endEvent id="endTimeout"><bpmn:incoming>timeoutDone</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(CHILD);

  }

  @BeforeEach
  public void startTheCallerWhichWaitsInTheChild() {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration
        .setJdbcUrl("jdbc:h2:mem:called-settings-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    // the engine refuses to parse a model without one, and a test needs no cleanup policy
    configuration.setHistoryTimeToLive("P30D");
    engine = configuration.buildProcessEngine();
    // without a name-clash-avoidance support the workflow module id is the tenant
    engine
        .getRepositoryService()
        .createDeployment()
        .tenantId(MODULE)
        .addString(CALLER
            + ".bpmn", caller())
        .addString(CHILD
            + ".bpmn", child())
        .deploy();

    final var sync = mock(WorkflowAggregateSync.class);
    when(sync.syncedValues(any(), any())).thenReturn(Map.of("ready", Boolean.TRUE));
    processService = new Camunda7ProcessService<>(
        "c7", engine.getRuntimeService(), engine.getTaskService(), engine.getRepositoryService(), engine
            .getHistoryService(), TestCollaborators.builder().workflowAggregateSync(sync).build());
    processService
        .setSerializationFormats((
            workflowModuleId,
            bpmnProcessId) -> {
          processesAskedForTheirFormat.add(bpmnProcessId);
          return null;
        });
    // what the deployment registers, so the adapter can name the process an execution runs in
    final var taskRegistry = new Camunda7TaskRegistry();
    taskRegistry.registerProcess(MODULE, CALLER, CALLER);
    taskRegistry.registerProcess(MODULE, CHILD, CHILD);
    processService.setTaskRegistry(taskRegistry);

    engine
        .getRuntimeService()
        .createProcessInstanceByKey(CALLER)
        .processDefinitionTenantId(MODULE)
        .businessKey(AGGREGATE_ID)
        .execute();

  }

  @AfterEach
  public void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  @Test
  @DisplayName("A push without a task reaches a gateway which waits in the called process")
  public void aPushWithoutATaskReachesTheCalledProcess() {

    assertTrue(isWaitingAt("childWaits"), "the child waits for the value before the push");

    run(PhaseOperation.AGGREGATE_CHANGED, Map.of());

    assertTrue(
        !isWaitingAt("childWaits"),
        "the called process is a process instance of its own in Camunda 7, with variables of its own, so a value"
            + " written only into the caller never reaches the condition in the child");
    assertTrue(
        processesAskedForTheirFormat.contains(CHILD),
        "the values written into the child are written in the child's format: "
            + processesAskedForTheirFormat);

  }

  @Test
  @DisplayName("Completing a task of the called process writes in the format of the called process")
  public void completingATaskUsesTheFormatOfItsProcess() {

    run(PhaseOperation.COMPLETE_TASK, Map.of(PhaseTwoCall.ARG_TASK_ID, executionAt("childWork")));

    assertEquals(List.of(CHILD), processesAskedForTheirFormat);
    assertTrue(!isWaitingAt("childWork"), "the task was completed");

  }

  @Test
  @DisplayName("Cancelling a task of the called process writes in the format of the called process")
  public void cancellingATaskUsesTheFormatOfItsProcess() {

    run(
        PhaseOperation.CANCEL_TASK,
        Map.of(PhaseTwoCall.ARG_TASK_ID, executionAt("childWork"), PhaseTwoCall.ARG_BPMN_ERROR_CODE, "nope"));

    assertEquals(List.of(CHILD), processesAskedForTheirFormat);

  }

  @Test
  @DisplayName("Completing a user task of the called process writes in the format of the called process")
  public void completingAUserTaskUsesTheFormatOfItsProcess() {

    final var userTaskId = engine
        .getTaskService()
        .createTaskQuery()
        .taskDefinitionKey("childReview")
        .singleResult()
        .getId();

    run(PhaseOperation.COMPLETE_USER_TASK, Map.of(PhaseTwoCall.ARG_TASK_ID, userTaskId));

    assertEquals(List.of(CHILD), processesAskedForTheirFormat);

  }

  @Test
  @DisplayName("A push for a task of the called process writes in the format of the called process")
  public void aPushForATaskUsesTheFormatOfItsProcess() {

    run(PhaseOperation.AGGREGATE_CHANGED, Map.of(PhaseTwoCall.ARG_TASK_ID, executionAt("childWork")));

    assertEquals(List.of(CHILD), processesAskedForTheirFormat);

  }

  @Test
  @DisplayName("A message the called process waits for travels in the format of the called process")
  public void aMessageUsesTheFormatOfTheProcessWhichWaits() {

    run(PhaseOperation.CORRELATE_MESSAGE, Map.of(PhaseTwoCall.ARG_MESSAGE_NAME, "childHello"));

    assertEquals(List.of(CHILD), processesAskedForTheirFormat);
    assertTrue(!isWaitingAt("childHello"), "the message was correlated");

  }

  private void run(
      final PhaseOperation operation,
      final Map<String, String> args) {

    processService
        .phaseOperations()
        .get(operation)
        .phaseTwo(new PhaseTwoRequest<>(MODULE, CALLER, persistence(), AGGREGATE_ID, args));

  }

  private String executionAt(
      final String activityId) {

    return engine
        .getRuntimeService()
        .createExecutionQuery()
        .activityId(activityId)
        .singleResult()
        .getId();

  }

  private boolean isWaitingAt(
      final String activityId) {

    return engine
        .getRuntimeService()
        .createExecutionQuery()
        .activityId(activityId)
        .count() > 0;

  }

  private static AggregatePersistenceAware<Object> persistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Object loadById(
          final Object workflowAggregateId) {

        return workflowAggregateId;

      }

    };

  }

}
