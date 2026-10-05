package io.vanillabp.camunda7.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The awareness probes of this adapter answer for the scope they are
 * ASKED about, which is what the election contract of {@code MigratableProcessService}
 * demands.
 * <p>
 * A Camunda 7 business key is the workflow-aggregate id, and an engine may hold
 * processes this adapter never wired: models of another application sharing the
 * database, or definitions left by a version of this application which knew other
 * workflow modules. Answering {@code ACTIVE} for one of those claims a workflow this
 * adapter cannot serve, and in a migration it wins the election against the BPMS which
 * really holds it.
 * <p>
 * What the adapter compares is what the wiring registered: the process definition keys
 * of its workflow modules, and the tenant each module runs in. The engine here is a real
 * one, in memory, because the queries doing the comparison are the subject.
 * <p>
 * Comparing what the adapter has DEPLOYED is not enough: two workflow modules of one
 * adapter would both be its own scope, and a foreign-module instance with the same
 * aggregate id would still be claimed. The probe is therefore told which workflow module
 * and which BPMN processes are meant, and that case is the third test below.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7AwarenessScopeTest {

  private static final String OWN_PROCESS = "OwnProcess";

  private static final String FOREIGN_PROCESS = "ForeignProcess";

  private static final String MODULE = "own-module";

  /**
   * Without a name-clash-avoidance support the adapter behaves like {@code by-adapter},
   * its version-1 mode: the workflow module id IS the Camunda tenant. That is what makes
   * the tenant half of the scope observable here without stubbing the core's scoping.
   */
  private static final String OWN_TENANT = MODULE;

  /**
   * Another application on the same engine, with a process definition of the same key.
   */
  private static final String FOREIGN_TENANT = "someone-else";

  /**
   * A second process of the same {@code @WorkflowService} ({@code secondaryBpmnProcesses}):
   * it runs on the same workflow aggregate, so an instance of it is a legitimate answer as
   * long as the scope names it.
   */
  private static final String SECONDARY_PROCESS = "SecondaryProcess";

  private static final String AGGREGATE_ID = "1";

  private ProcessEngine engine;

  private Camunda7ProcessService<Object> processService;

  /**
   * What a probe of the own workflow module is asked about.
   */
  private static final io.vanillabp.integration.adapter.spi.WorkflowScope SCOPE = io.vanillabp.integration.adapter.spi.WorkflowScope
      .of(MODULE, OWN_PROCESS);

  /**
   * Another workflow module of the SAME adapter id, with a process of its own. Without a
   * name-clash-avoidance support the module id is the tenant, so this is what a second
   * module looks like to the engine.
   */
  private static final String OTHER_MODULE = "other-module";

  private static final String OTHER_MODULE_PROCESS = "OtherModuleProcess";

  /**
   * A process with a user task, so an instance stays running and offers both a task and
   * an execution to probe.
   */
  private static String bpmn(
      final String processId) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
            id="defs_%s" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:startEvent id="start"><bpmn:outgoing>f1</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="wait" />
            <bpmn:userTask id="wait"><bpmn:incoming>f1</bpmn:incoming><bpmn:outgoing>f2</bpmn:outgoing></bpmn:userTask>
            <bpmn:sequenceFlow id="f2" sourceRef="wait" targetRef="end" />
            <bpmn:endEvent id="end"><bpmn:incoming>f2</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """.formatted(processId, processId);

  }

  @BeforeEach
  public void bootTheEngine() {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration
        .setJdbcUrl("jdbc:h2:mem:awareness-scope-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    configuration.setHistory("full");
    // the engine refuses to parse a model without one, and a test needs no cleanup policy
    configuration.setHistoryTimeToLive("P30D");
    engine = configuration.buildProcessEngine();
    deploy(OWN_TENANT, OWN_PROCESS, FOREIGN_PROCESS, SECONDARY_PROCESS);
    // another workflow module of the same adapter id
    deploy(OTHER_MODULE, OTHER_MODULE_PROCESS);
    // the same process key in the tenant of somebody else - what the scope has to tell
    // apart although the definition key is identical
    deploy(FOREIGN_TENANT, OWN_PROCESS);
    processService = new Camunda7ProcessService<>(
        "c7", engine.getRuntimeService(), engine.getTaskService(), engine.getRepositoryService(), engine
            .getHistoryService(), io.vanillabp.camunda7.TestCollaborators.complete());

  }

  @AfterEach
  public void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  private void deploy(
      final String tenantId,
      final String... processIds) {

    var deployment = engine
        .getRepositoryService()
        .createDeployment()
        .tenantId(tenantId);
    for (final var processId : processIds) {
      deployment = deployment
          .addString(
              processId
                  + ".bpmn",
              bpmn(processId));
    }
    deployment.deploy();

  }

  private String start(
      final String processId) {

    return start(processId, OWN_TENANT);

  }

  private String start(
      final String processId,
      final String tenantId) {

    return engine
        .getRuntimeService()
        .createProcessInstanceByKey(processId)
        .processDefinitionTenantId(tenantId)
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

  @Test
  @DisplayName("A workflow of this adapter's own scope is claimed")
  public void ownWorkflowIsClaimed() {

    start(OWN_PROCESS);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        processService.awarenessOfWorkflow(SCOPE, null, AGGREGATE_ID),
        "the adapter holds this workflow");

  }

  @Test
  @DisplayName("A workflow of a process this adapter never wired is not claimed, however the business key reads")
  public void aForeignWorkflowIsNotClaimed() {

    start(FOREIGN_PROCESS);

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfWorkflow(SCOPE, null, AGGREGATE_ID),
        "the business key matches and the process is not this adapter's - the election has to continue");

  }

  @Test
  @DisplayName("An ended workflow of the own scope is COMPLETED, an ended foreign one stays unknown")
  public void theHistoryIsScopedAsWell() {

    final var own = start(OWN_PROCESS);
    finish(own);

    assertEquals(
        WorkflowAwareness.COMPLETED,
        processService.awarenessOfWorkflow(SCOPE, null, AGGREGATE_ID),
        "an ended workflow of this adapter is reported as ended, not as unknown");

    final var foreign = start(FOREIGN_PROCESS);
    finish(foreign);
    engine
        .getHistoryService()
        .deleteHistoricProcessInstance(own);

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfWorkflow(SCOPE, null, AGGREGATE_ID),
        "the history of a foreign process says nothing about this adapter");

  }

  @Test
  @DisplayName("A user task of a foreign process is not claimed, one of the own scope is")
  public void userTasksAreScoped() {

    final var foreign = start(FOREIGN_PROCESS);
    final var foreignTask = engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(foreign)
        .singleResult();

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfUserTask(SCOPE, AGGREGATE_ID, foreignTask.getId()),
        "a task key of this engine is addressable and still not this adapter's business");

    final var own = start(OWN_PROCESS);
    final var ownTask = engine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(own)
        .singleResult();

    assertEquals(
        WorkflowAwareness.ACTIVE,
        processService.awarenessOfUserTask(SCOPE, AGGREGATE_ID, ownTask.getId()));

  }

  @Test
  @DisplayName("A task of a foreign process is not claimed, one of the own scope is")
  public void serviceTasksAreScoped() {

    final var foreign = start(FOREIGN_PROCESS);

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfTask(SCOPE, AGGREGATE_ID, foreign),
        "the execution of a foreign workflow is not this adapter's task");

    final var own = start(OWN_PROCESS);

    assertEquals(
        WorkflowAwareness.ACTIVE,
        processService.awarenessOfTask(SCOPE, AGGREGATE_ID, own));

  }

  @Test
  @DisplayName("The wrong business key stays unknown, scope or not")
  public void theBusinessKeyStillCounts() {

    start(OWN_PROCESS);

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfWorkflow(SCOPE, null, "another-aggregate"));

  }

  @Test
  @DisplayName("A workflow of ANOTHER workflow module of this adapter is not claimed")
  public void anotherModuleOfTheSameAdapterIsNotClaimed() {

    // both modules belong to this adapter id, and only the scope of the CALL says
    // which one is meant
    start(OTHER_MODULE_PROCESS, OTHER_MODULE);

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfWorkflow(SCOPE, null, AGGREGATE_ID),
        "the aggregate id is the same and the workflow module is not the one asked about");
    assertEquals(
        WorkflowAwareness.ACTIVE,
        processService
            .awarenessOfWorkflow(
                io.vanillabp.integration.adapter.spi.WorkflowScope.of(OTHER_MODULE, OTHER_MODULE_PROCESS),
                null,
                AGGREGATE_ID),
        "and asked for its own module it is found");

  }

  @Test
  @DisplayName("A secondary process of the same workflow service is part of the scope")
  public void aSecondaryProcessIsClaimed() {

    // a @WorkflowService may declare secondaryBpmnProcesses: they run on the same
    // workflow aggregate, so the scope names them and an instance of one is an answer
    start(SECONDARY_PROCESS);

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfWorkflow(SCOPE, null, AGGREGATE_ID),
        "a scope naming only the primary process does not see it");
    assertEquals(
        WorkflowAwareness.ACTIVE,
        processService
            .awarenessOfWorkflow(
                new io.vanillabp.integration.adapter.spi.WorkflowScope(
                    MODULE, java.util.List.of(OWN_PROCESS, SECONDARY_PROCESS)),
                null,
                AGGREGATE_ID),
        "the scope of the real process service names both, and then it is this workflow");

  }

  @Test
  @DisplayName("The same process key in another tenant is not this adapter's scope")
  public void theTenantIsPartOfTheScope() {

    start(OWN_PROCESS, FOREIGN_TENANT);

    assertEquals(
        WorkflowAwareness.UNKNOWN_TO_BPMS,
        processService.awarenessOfWorkflow(SCOPE, null, AGGREGATE_ID),
        "the definition key is the adapter's own and the tenant is somebody else's");

  }

  /**
   * The same question for the WRITE behind {@code aggregateChanged}, which the
   * probes' fix had missed. It is the worse half of the defect - a probe answering for a
   * foreign workflow reports something wrong, a write into one ADVANCES it, because a
   * variable write is what makes Camunda 7 re-evaluate conditional events.
   * <p>
   * The aggregate shares nothing here (no sync model in this service), so what the push
   * writes is the technical marker. That is deliberate: it is the case which reaches a
   * foreign instance even when the two applications have not a single field in common.
   */
  private void push(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskId) {

    processService
        .phaseOperations()
        .get(io.vanillabp.integration.spi.PhaseOperation.AGGREGATE_CHANGED)
        .phaseTwo(
            new io.vanillabp.integration.adapter.spi.PhaseTwoRequest<>(
                workflowModuleId, bpmnProcessId, persistence(), AGGREGATE_ID, taskId == null
                    ? java.util.Map.of()
                    : java.util.Map
                        .of(io.vanillabp.integration.spi.PhaseTwoCall.ARG_TASK_ID, taskId)));

  }

  private static io.vanillabp.integration.spi.AggregatePersistenceAware<Object> persistence() {

    return new io.vanillabp.integration.spi.AggregatePersistenceAware<>() {

      @Override
      public Object loadById(
          final Object workflowAggregateId) {
        return workflowAggregateId;
      }

    };

  }

  private boolean wasWrittenTo(
      final String processInstanceId) {

    return engine
        .getRuntimeService()
        .getVariables(processInstanceId)
        .containsKey(Camunda7ProcessService.AGGREGATE_CHANGED_MARKER);

  }

  @Test
  @DisplayName("A changed aggregate is pushed into the own workflow, not into the one of another module")
  public void aPushReachesTheOwnWorkflowOnly() {

    final var own = start(OWN_PROCESS);
    final var otherModule = engine
        .getRuntimeService()
        .createProcessInstanceByKey(OTHER_MODULE_PROCESS)
        .processDefinitionTenantId(OTHER_MODULE)
        .businessKey(AGGREGATE_ID)
        .execute()
        .getId();

    push(MODULE, OWN_PROCESS, null);

    assertTrue(wasWrittenTo(own), "the workflow of the calling scope receives the values");
    assertFalse(
        wasWrittenTo(otherModule),
        "the workflow of the other module counts aggregate ids from the same number, and a write "
            + "there would re-evaluate ITS conditional events");

  }

  @Test
  @DisplayName("A push whose own workflow ended is tolerated instead of reaching a foreign one")
  public void aPushOfAnEndedWorkflowIsTolerated() {

    final var own = start(OWN_PROCESS);
    final var foreign = start(OWN_PROCESS, FOREIGN_TENANT);
    finish(own);

    // phase two is at-least-once, so this is the redelivery of a push whose workflow has
    // meanwhile ended: nothing to write, and above all nothing to write ELSEWHERE
    push(MODULE, OWN_PROCESS, null);

    assertFalse(
        wasWrittenTo(foreign),
        "the workflow of another tenant is not the tolerated case, it is a foreign workflow");

  }

  @Test
  @DisplayName("In a migration the push goes into the workflow of its own adapter id")
  public void aPushInAMigrationStaysWithItsAdapterId() {

    // two adapter ids on one engine, which is what a migration between two Camunda 7
    // installations of one database looks like: the second id runs the same workflow
    // module under a tenant of its own, and both count aggregate ids alike
    final var foreignAdapterTenant = "c7-prefixed";
    deploy(foreignAdapterTenant, OWN_PROCESS);
    final var own = start(OWN_PROCESS);
    final var otherAdapterId = engine
        .getRuntimeService()
        .createProcessInstanceByKey(OWN_PROCESS)
        .processDefinitionTenantId(foreignAdapterTenant)
        .businessKey(AGGREGATE_ID)
        .execute()
        .getId();

    push(MODULE, OWN_PROCESS, null);

    assertTrue(wasWrittenTo(own), "the workflow of this adapter id receives the values");
    assertFalse(
        wasWrittenTo(otherAdapterId),
        "the workflow deployed by the other adapter id is none of this one's business");

  }

}
