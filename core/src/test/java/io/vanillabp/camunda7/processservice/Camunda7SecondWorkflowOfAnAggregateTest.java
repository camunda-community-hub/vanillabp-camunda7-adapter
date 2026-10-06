package io.vanillabp.camunda7.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.util.ClockUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An aggregate may carry a second workflow once its first one ended. This is the probe the
 * core asks before it dispatches a start a second time, and the case where it used to lose
 * that second workflow:
 * <ol>
 * <li>the first workflow of the aggregate runs and ends;</li>
 * <li>a second start of the same aggregate is planned;</li>
 * <li>the first attempt to dispatch it fails before the engine created anything;</li>
 * <li>the core repeats the dispatch and first asks the adapter whether the workflow exists.</li>
 * </ol>
 * The history holds the first workflow. A probe which counts it answers "it is there", the
 * core consumes the entry, and the second workflow never starts. The probe counts only the
 * workflows started at or after the moment the start was planned.
 * <p>
 * The engine is a real one, in memory, because the history query is the subject. The engine's
 * clock is set by hand, so the start times do not depend on how fast the test runs.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7SecondWorkflowOfAnAggregateTest {

  private static final String PROCESS = "SecondWorkflowProcess";

  private static final String MODULE = "second-workflow-module";

  private static final WorkflowScope SCOPE = WorkflowScope.of(MODULE, PROCESS);

  private static final String AGGREGATE_ID = "42";

  /**
   * When the first workflow of the aggregate started.
   */
  private static final Instant FIRST_STARTED = Instant.parse("2026-10-01T08:00:00.000Z");

  /**
   * When the second start of the aggregate was planned, a day later.
   */
  private static final Instant SECOND_PLANNED = FIRST_STARTED.plus(Duration.ofDays(1));

  private ProcessEngine engine;

  private Camunda7ProcessService<Object> processService;

  /**
   * A process with a user task, so an instance stays running until the test completes it.
   */
  private static String bpmn() {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait" />
            <bpmn:userTask id="wait"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:userTask>
            <bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="end" />
            <bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """.formatted(PROCESS);

  }

  @BeforeEach
  public void bootTheEngine() {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration
        .setJdbcUrl("jdbc:h2:mem:second-workflow-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    configuration.setHistory("full");
    // the engine refuses to parse a model without one, and a test needs no cleanup policy
    configuration.setHistoryTimeToLive("P30D");
    engine = configuration.buildProcessEngine();
    // without a name-clash-avoidance support the workflow module id is the tenant
    engine
        .getRepositoryService()
        .createDeployment()
        .tenantId(MODULE)
        .addString(PROCESS
            + ".bpmn", bpmn())
        .deploy();
    processService = new Camunda7ProcessService<>(
        "c7", engine.getRuntimeService(), engine.getTaskService(), engine.getRepositoryService(), engine
            .getHistoryService(), io.vanillabp.camunda7.TestCollaborators.complete());

  }

  @AfterEach
  public void closeTheEngine() {

    ClockUtil.reset();
    if (engine != null) {
      engine.close();
    }

  }

  private String startAt(
      final Instant moment) {

    ClockUtil.setCurrentTime(Date.from(moment));
    return engine
        .getRuntimeService()
        .createProcessInstanceByKey(PROCESS)
        .processDefinitionTenantId(MODULE)
        .businessKey(AGGREGATE_ID)
        .execute()
        .getId();

  }

  private void finish(
      final String processInstanceId) {

    final var task = engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(processInstanceId)
        .singleResult();
    engine.getTaskService().complete(task.getId());

  }

  private WorkflowAwareness probeTheRetriedStartPlannedAt(
      final Instant plannedAt) {

    return processService.awarenessOfWorkflowForRedispatch(SCOPE, null, AGGREGATE_ID, plannedAt);

  }

  @Test
  @DisplayName("A retried second start does not take the ended first workflow for its own")
  public void theFirstWorkflowDoesNotCountForTheSecondStart() {

    finish(startAt(FIRST_STARTED));
    // the second start is planned, and its first dispatch fails before the engine creates
    // anything: the engine holds the first workflow and nothing else

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        probeTheRetriedStartPlannedAt(SECOND_PLANNED),
        "the first workflow started before the second start was planned, so the retry has to start");
    assertEquals(
        WorkflowAwareness.COMPLETED,
        processService.awarenessOfWorkflowForRedispatch(SCOPE, null, AGGREGATE_ID),
        "the question without the moment still sees the first workflow, which is the loss this probe avoids");

  }

  @Test
  @DisplayName("A second workflow started for the entry is found, running or ended")
  public void theSecondWorkflowCounts() {

    finish(startAt(FIRST_STARTED));
    // the first dispatch of the second start did create the workflow, and only its
    // acknowledgement got lost
    final var second = startAt(SECOND_PLANNED.plusMillis(250));

    assertEquals(
        WorkflowAwareness.ACTIVE,
        probeTheRetriedStartPlannedAt(SECOND_PLANNED),
        "the workflow this entry started runs, so the retry has to be skipped");

    finish(second);

    assertEquals(
        WorkflowAwareness.COMPLETED,
        probeTheRetriedStartPlannedAt(SECOND_PLANNED),
        "the workflow this entry started ended already, which still means it must not start again");

  }

  @Test
  @DisplayName("A workflow started in the very millisecond the start was planned counts")
  public void theMomentItselfCounts() {

    startAt(SECOND_PLANNED);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        probeTheRetriedStartPlannedAt(SECOND_PLANNED),
        "at or after the moment, not only after it");

  }

  @Test
  @DisplayName("An entry planned before the moment was recorded counts every workflow, as before")
  public void anEntryWithoutTheMomentCountsEveryWorkflow() {

    finish(startAt(FIRST_STARTED));

    assertEquals(
        WorkflowAwareness.COMPLETED,
        probeTheRetriedStartPlannedAt(null),
        "without the moment nothing tells the two workflows apart");

  }

  @Test
  @DisplayName("The moment does not widen the scope: a workflow of another module stays unknown")
  public void theScopeStillCounts() {

    finish(startAt(FIRST_STARTED));
    startAt(SECOND_PLANNED.plusSeconds(1));

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService
            .awarenessOfWorkflowForRedispatch(
                WorkflowScope.of("another-module", PROCESS),
                null,
                AGGREGATE_ID,
                SECOND_PLANNED),
        "the history query is narrowed to the scope like every other probe");

  }

}
