package io.vanillabp.camunda7.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.camunda.bpm.engine.runtime.ProcessInstanceQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter asks the engine while an application boots, counted.
 * <p>
 * The startup check for old process versions asks the catalog two things about every
 * version older than the one the boot deployed: the model of that version and how many
 * workflows still run on it. Both need the engine's process definition id, and looking
 * that up used to be a definition query of its own - so a process with fifty versions
 * behind it paid a hundred queries nobody could see, on top of the one list which had
 * already brought every id back.
 * <p>
 * Decision 10 in the repository's DECISIONS.md is what this holds: an engine query while
 * booting is counted, and the count belongs to the versions, never to the workflows.
 * <p>
 * The same counting applies to the one question the job acquisition asks while it is idle.
 * Version 1 listed every job with a due date in the future and used the first row, which is
 * a list whose length is the application's backlog; the answer needed is one row.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7StartupQuestionCostTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /**
   * How many versions the engine holds - enough that a query per version is a different
   * number from a query per process.
   */
  private static final int VERSIONS = 4;

  /**
   * How many of the questions about an older version read its model: its tasks, what starts
   * it, and which of its elements can put a second token into a workflow. The engine parses
   * a definition once and answers the rest from its deployment cache, so the number to watch
   * is the definition query below.
   */
  private static final int MODEL_READING_QUESTIONS = 3;

  /**
   * How many BPMN processes the workflow module brings - a process definition key takes a
   * batch filter, so the question about what the engine already holds is asked once for all
   * of them.
   */
  private static final int PROCESSES = 2;

  /**
   * How many decision tables the workflow module brings. A decision definition key has no
   * batch filter, so this is the one number the count grows with, and it is a property of the
   * application rather than of its history.
   */
  private static final int DECISIONS = 3;

  /**
   * The deployment every definition of these tests belongs to - this adapter's own, so
   * nothing here needs a second deployment query to attribute a finding.
   */
  private static final String OUR_DEPLOYMENT = "deployment-1";

  private RepositoryService repositoryService;

  private RuntimeService runtimeService;

  private Camunda7ProcessVersions versions;

  /**
   * Every query the engine handed out, by kind.
   */
  private final java.util.Map<String, Integer> queries = new java.util.TreeMap<>();

  private ProcessDefinition definition(
      final int version) {

    final var definition = Mockito.mock(ProcessDefinition.class);
    Mockito
        .lenient()
        .when(definition.getId())
        .thenReturn("%s:%d:key".formatted(PROCESS, version));
    Mockito
        .lenient()
        .when(definition.getVersion())
        .thenReturn(version);
    Mockito
        .lenient()
        .when(definition.getDeploymentId())
        .thenReturn(OUR_DEPLOYMENT);
    return definition;

  }

  @BeforeEach
  public void setUp() {

    final var definitions = java.util.stream.IntStream
        .rangeClosed(1, VERSIONS)
        .mapToObj(this::definition)
        .toList();

    final var definitionQuery = Mockito.mock(ProcessDefinitionQuery.class, Mockito.RETURNS_SELF);
    Mockito
        .lenient()
        .when(definitionQuery.list())
        .thenReturn(definitions);
    // the engine answers with the version it was asked for, whether or not the list above
    // held it: a version deployed by another node after the list was read is exactly the
    // case the second test is about
    final var askedFor = new int[]{
        1
    };
    Mockito
        .lenient()
        .when(definitionQuery.processDefinitionVersion(Mockito.anyInt()))
        .thenAnswer(invocation -> {
          askedFor[0] = invocation.getArgument(0);
          return definitionQuery;
        });
    Mockito
        .lenient()
        .when(definitionQuery.singleResult())
        .thenAnswer(invocation -> definition(askedFor[0]));

    final var instanceQuery = Mockito.mock(ProcessInstanceQuery.class, Mockito.RETURNS_SELF);

    final var decisionQuery = Mockito
        .mock(org.camunda.bpm.engine.repository.DecisionDefinitionQuery.class, Mockito.RETURNS_SELF);
    Mockito.lenient().when(decisionQuery.list()).thenReturn(java.util.List.of());

    final var ourDeployment = Mockito.mock(org.camunda.bpm.engine.repository.Deployment.class);
    Mockito.lenient().when(ourDeployment.getId()).thenReturn(OUR_DEPLOYMENT);
    final var deploymentQuery = Mockito
        .mock(org.camunda.bpm.engine.repository.DeploymentQuery.class, Mockito.RETURNS_SELF);
    Mockito.lenient().when(deploymentQuery.list()).thenReturn(java.util.List.of(ourDeployment));

    repositoryService = Mockito.mock(RepositoryService.class);
    Mockito
        .lenient()
        .when(repositoryService.createProcessDefinitionQuery())
        .thenAnswer(invocation -> {
          queries.merge("createProcessDefinitionQuery", 1, Integer::sum);
          return definitionQuery;
        });
    Mockito
        .lenient()
        .when(repositoryService.createDecisionDefinitionQuery())
        .thenAnswer(invocation -> {
          queries.merge("createDecisionDefinitionQuery", 1, Integer::sum);
          return decisionQuery;
        });
    Mockito
        .lenient()
        .when(repositoryService.createDeploymentQuery())
        .thenAnswer(invocation -> {
          queries.merge("createDeploymentQuery", 1, Integer::sum);
          return deploymentQuery;
        });
    Mockito
        .lenient()
        .when(repositoryService.getBpmnModelInstance(Mockito.anyString()))
        .thenAnswer(invocation -> {
          queries.merge("getBpmnModelInstance", 1, Integer::sum);
          return null;
        });

    runtimeService = Mockito.mock(RuntimeService.class);
    Mockito
        .lenient()
        .when(runtimeService.createProcessInstanceQuery())
        .thenAnswer(invocation -> {
          queries.merge("createProcessInstanceQuery", 1, Integer::sum);
          return instanceQuery;
        });

    versions = new Camunda7ProcessVersions(
        "c7", repositoryService, (
            workflowModuleId,
            bpmnProcessId) -> bpmnProcessId, workflowModuleId -> null, new HeldModelsAnsweringNothing());
    versions.setRuntimeService(runtimeService);
    queries.clear();

  }

  /**
   * What the startup check does per BPMN process: it asks for the versions once and then
   * asks four questions about every older one.
   */
  private void whatAStartAsks() {

    final var deployed = versions.deployedVersionsOf(MODULE, PROCESS);
    deployed
        .stream()
        .map(io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion::version)
        .filter(version -> !String.valueOf(VERSIONS).equals(version))
        .forEach(version -> {
          versions.activeInstanceCountOf(MODULE, PROCESS, version);
          versions.tasksOfVersion(MODULE, PROCESS, version);
          versions.startEventsOfVersion(MODULE, PROCESS, version);
          versions.concurrentTokenElementsOfVersion(MODULE, PROCESS, version);
        });

  }

  @Test
  @DisplayName("The version list is the only definition query a start needs")
  public void theVersionListAnswersEveryLaterQuestion() {

    whatAStartAsks();

    assertEquals(
        1,
        queries.getOrDefault("createProcessDefinitionQuery", 0),
        () -> "one list of definitions holds every id the later questions need, but was "
            + queries);
    assertEquals(
        VERSIONS - 1,
        queries.getOrDefault("createProcessInstanceQuery", 0),
        () -> "one count per version older than the deployed one, but was "
            + queries);
    assertEquals(
        MODEL_READING_QUESTIONS * (VERSIONS - 1),
        queries.getOrDefault("getBpmnModelInstance", 0),
        () -> "one model read per question about an older version, which the engine answers "
            + "from the definition it parsed for the first of them, but was "
            + queries);

  }

  /**
   * The identifiers of a workflow module, keyed by what the engine knows them as - the shape
   * the check about what the engine already holds is asked in.
   */
  private static java.util.Map<String, String> identifiers(
      final String prefix,
      final int howMany) {

    return java.util.stream.IntStream
        .rangeClosed(1, howMany)
        .boxed()
        .collect(
            java.util.stream.Collectors
                .toMap(number -> prefix + number, number -> prefix + number));

  }

  @Test
  @DisplayName("What the engine already holds is one query per workflow module, plus one per decision")
  public void whatTheEngineAlreadyHoldsIsAskedPerWorkflowModule() {

    io.vanillabp.camunda7.deployment.Camunda7IdentifiersTheEngineHolds
        .reportWhatTheEngineAlreadyHolds(
            "c7",
            MODULE,
            identifiers("Process", PROCESSES),
            identifiers("decision", DECISIONS),
            null,
            repositoryService,
            new io.vanillabp.camunda7.RecordingScoping(
                io.vanillabp.integration.adapter.spi.NameClashAvoidance.NONE));

    assertEquals(
        1,
        queries.getOrDefault("createProcessDefinitionQuery", 0),
        () -> "every process of the module is asked about in one statement, and the engine holding "
            + VERSIONS
            + " versions of each changes nothing about that, but was "
            + queries);
    assertEquals(
        DECISIONS,
        queries.getOrDefault("createDecisionDefinitionQuery", 0),
        () -> "a decision definition key has no batch filter, so one statement each, but was "
            + queries);
    assertEquals(
        1,
        queries.getOrDefault("createDeploymentQuery", 0),
        () -> "which deployments this adapter made is one question per workflow module, and a "
            + "definition belonging to one of them needs none of its own, but was "
            + queries);
    assertEquals(
        0,
        queries.getOrDefault("createProcessInstanceQuery", 0),
        () -> "nothing here asks about a running workflow, but was "
            + queries);

  }

  @Test
  @DisplayName("An idle acquisition cycle asks for one job row per engine, and for no list")
  public void theDueDateQuestionAsksForOneRow() {

    final var jobQuery = Mockito.mock(org.camunda.bpm.engine.runtime.JobQuery.class, Mockito.RETURNS_SELF);
    Mockito
        .lenient()
        .when(jobQuery.listPage(Mockito.anyInt(), Mockito.anyInt()))
        .thenAnswer(invocation -> {
          queries.merge("jobQuery.listPage", 1, Integer::sum);
          return java.util.List.of();
        });
    Mockito
        .lenient()
        .when(jobQuery.list())
        .thenAnswer(invocation -> {
          queries.merge("jobQuery.list", 1, Integer::sum);
          return java.util.List.of();
        });

    final var managementService = Mockito.mock(org.camunda.bpm.engine.ManagementService.class);
    Mockito.lenient().when(managementService.createJobQuery()).thenReturn(jobQuery);
    final var engine = Mockito.mock(org.camunda.bpm.engine.impl.ProcessEngineImpl.class);
    Mockito.lenient().when(engine.getManagementService()).thenReturn(managementService);

    final var jobExecutor = Mockito.mock(org.camunda.bpm.engine.impl.jobexecutor.JobExecutor.class);
    Mockito
        .lenient()
        .when(jobExecutor.engineIterator())
        .thenAnswer(invocation -> java.util.List.of(engine).iterator());
    Mockito.lenient().when(jobExecutor.hasRegisteredEngine(engine)).thenReturn(Boolean.TRUE);

    final var context = Mockito.mock(org.camunda.bpm.engine.impl.jobexecutor.JobAcquisitionContext.class);
    Mockito.lenient().when(context.areAllEnginesIdle()).thenReturn(Boolean.TRUE);
    // an idle cycle asked for three jobs and got none, which is not the same thing as not
    // having asked: an empty map reads to the engine as an executor whose threads are full
    Mockito
        .lenient()
        .when(context.getAcquiredJobsByEngine())
        .thenReturn(
            java.util.Map
                .of("c7", new org.camunda.bpm.engine.impl.jobexecutor.AcquiredJobs(3)));
    Mockito.lenient().when(context.getRejectedJobsByEngine()).thenReturn(java.util.Map.of());
    Mockito.lenient().when(context.getAdditionalJobsByEngine()).thenReturn(java.util.Map.of());

    new io.vanillabp.camunda7.engine.Camunda7SleepUntilSomethingIsDue("c7", jobExecutor)
        .reconfigure(context);

    assertEquals(
        1,
        queries.getOrDefault("jobQuery.listPage", 0),
        () -> "when to wake up is one question per engine, but was "
            + queries);
    assertEquals(
        0,
        queries.getOrDefault("jobQuery.list", 0),
        () -> "the list of every future job is read by the database and looked at by nobody, "
            + "so it is never asked for, but was "
            + queries);
    Mockito
        .verify(jobQuery)
        .listPage(0, 1);

  }

  @Test
  @DisplayName("Whether this adapter's isolation separates two workflow modules costs no query")
  public void theIsolationQuestionIsAnsweredFromConfiguration() {

    final var service = new io.vanillabp.camunda7.deployment.Camunda7DeploymentService(
        "c7", repositoryService, null, io.vanillabp.camunda7.TestCollaborators.complete(), null);
    service.setConfiguredTenants(workflowModuleId -> null);

    assertTrue(
        service.ownIsolationSeparatesWorkflowModules(MODULE, "another-module"),
        "a tenant named after each workflow module separates the two");

    assertEquals(
        0,
        queries
            .values()
            .stream()
            .mapToInt(Integer::intValue)
            .sum(),
        () -> "which tenant a workflow module is deployed into is configuration, and the core asks "
            + "the question once per PAIR of modules, so an engine on a bad day must not be part "
            + "of the answer, but was "
            + queries);

  }

  @Test
  @DisplayName("A version the list did not hold is looked up once, not once per question")
  public void aVersionDeployedLaterIsLookedUpOnce() {

    // a version another cluster node deployed after the list was read: the engine has to
    // be asked for it, and asked once
    versions.activeInstanceCountOf(MODULE, PROCESS, "7");
    versions.tasksOfVersion(MODULE, PROCESS, "7");

    assertEquals(
        1,
        queries.getOrDefault("createProcessDefinitionQuery", 0),
        () -> "the second question is answered from what the first one learned, but was "
            + queries);

  }

}
