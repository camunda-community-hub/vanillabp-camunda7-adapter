package io.vanillabp.camunda7.it;

import static io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck.A_VERSION_OF_A_PROCESS;
import static io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck.SERVED_BY_NO_METHOD;
import static io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck.STILL_RUN_ON_AN_OUTFADED_VERSION;
import static io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck.STILL_RUN_ON_THIS_VERSION;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.camunda.bpm.engine.RepositoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.camunda7.wiring.SuspendedProcessDefinitions;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The old-versions startup check against a REAL engine: the application deploys version
 * 1 of a process, leaves a workflow running on it, and boots again with a model which
 * dropped one of its tasks. What the application still serves of that older version is
 * what the engine can answer and this test proves.
 * <p>
 * The first boot is an earlier generation of the application
 * ({@link OldProcessVersionsBeforeWorkflowService}), which serves every task of version 1. The
 * boots after it run {@link OldProcessVersionsWorkflowService}, which no longer serves one of
 * them in version 1. The first boot cannot be that later generation: a start refuses the version
 * it deploys itself while a task of it has no method for that version.
 * <p>
 * The last two cases suspend version 1 and ask the same question again: a suspended
 * definition is not a deleted one, so it keeps its place in the check, and the only way
 * past it is the emergency exit which says on every start that it was taken.
 * <p>
 * Every case is a full boot, because the question is what a START reports, and the
 * findings are read from the captured output rather than from a log appender: Spring
 * Boot resets the logging context while it starts, which takes an appender attached
 * beforehand with it. The engine keeps its deployments in the database of this class,
 * so the boots build on each other and therefore run in order.
 * <p>
 * Every sentence a case here asks NOT to be in the output is asserted POSITIVELY by
 * another case of this class. A negative assertion on a text goes quiet the moment the
 * text is reworded, and nothing says so; the positive one beside it fails, and whoever
 * reworded the message is sent to both.
 */
@ExtendWith(SuppressOutputExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Camunda7OldProcessVersionsIT {

  private static final String DATABASE = "--spring.datasource.url=jdbc:h2:mem:c7-old-process-versions;DB_CLOSE_DELAY=-1";

  @Test
  @Order(1)
  @DisplayName("Version 1 is deployed and a workflow is left running on it")
  public void deployVersionOneAndLeaveAWorkflowRunning() throws Exception {

    // the generation which deploys version 1 serves every task of it - a start refuses the
    // version it deploys while a task of it has no method for that version. The boots after
    // this one are the next generation, which no longer serves one of those tasks
    final var application = new SpringApplicationBuilder(TestApplication.class)
        .run(DATABASE, resources("v1"), "--spring.profiles.active="
            + OldProcessVersionsBeforeWorkflowService.PROFILE);
    try {
      final var workflowService = application.getBean(OldProcessVersionsBeforeWorkflowService.class);
      final var repository = application.getBean(OldProcessVersionsRepository.class);
      final var aggregate = application
          .getBean(org.springframework.transaction.support.TransactionTemplate.class)
          .execute(status -> workflowService.startWorkflow());

      // the workflow walks to the task which stays open, and waits there for the
      // rest of this test class
      final var deadline = System.currentTimeMillis() + 30_000;
      while (repository.findById(aggregate.getId()).orElseThrow().getOpenTaskId() == null) {
        if (System.currentTimeMillis() > deadline) {
          throw new AssertionError("the workflow of version 1 did not reach its open task");
        }
        Thread.sleep(100);
      }
    } finally {
      application.close();
    }

  }

  @Test
  @Order(2)
  @DisplayName("What version 1 still needs is reported, and the workflow running on it makes it an error")
  public void theUnservedTaskOfTheOldVersionIsReported(
      final CapturedOutput output) {

    final var reported = whatIsReportedWhileBooting(output, "v2");
    // this case carries the positive side of every sentence the cases below ask NOT to be
    // there. A negative assertion on a text stops checking the moment the text is
    // reworded, and it says nothing about it; the positive next to it goes red instead
    assertTrue(
        reported.contains("definition(s) 'servedForAnUnknownVersion'"),
        "the unserved task of version 1 is named");
    assertTrue(
        reported.contains(SERVED_BY_NO_METHOD),
        "and the finding says what is missing for it");
    assertTrue(
        reported.contains(A_VERSION_OF_A_PROCESS.formatted("1", "OldProcessVersionsProcess")),
        "the finding is about version 1 of that process");
    assertTrue(reported.contains(STILL_RUN_ON_THIS_VERSION), "the workflow of version 1 is counted");
    assertTrue(reported.contains("OldProcessVersionsProcess"), "the process is named");
    assertTrue(reported.contains("outfaded-versions"), "the way out is named");
    // the method kept for version 1 serves its task, so that one is not demanded
    assertTrue(
        !reported.contains("definition(s) 'droppedInVersionTwo'"),
        "the task served by the version-1 method is not reported");
    // the method naming a version this engine never had never runs, and says so
    assertTrue(reported.contains("servedForAnUnknownVersion' (version '0')"), "the dead method is named");
    assertTrue(reported.contains("the method never runs"), "and what that means is said");

  }

  @Test
  @Order(3)
  @DisplayName("Fading version 1 out stops the demand and names the workflow left behind")
  public void fadingTheOldVersionOutChangesTheFinding(
      final CapturedOutput output) {

    final var reported = whatIsReportedWhileBooting(
        output,
        "v2",
        "--vanillabp.workflow-modules.c7-it.adapters.c7.outfaded-versions=<2");
    assertTrue(
        !reported.contains(SERVED_BY_NO_METHOD),
        "an outfaded version is not checked for unserved tasks");
    assertTrue(reported.contains(STILL_RUN_ON_AN_OUTFADED_VERSION.formatted("1")),
        "the workflow left behind is reported");
    assertTrue(reported.contains("outfaded-versions-in-use"), "and how to make that stop the start");
    // the method kept for version 1 serves nothing once that version is faded out
    assertTrue(reported.contains("droppedInVersionTwo"), "the method for the faded-out version is named");
    assertTrue(reported.contains("faded out by"), "and the reason is the configuration");

  }

  @Test
  @Order(4)
  @DisplayName("With the policy set, the workflow left behind stops the start")
  public void theStartCanBeMadeToFail() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> boot(
            "v2",
            "--vanillabp.workflow-modules.c7-it.adapters.c7.outfaded-versions=<2",
            "--vanillabp.adapters.c7.outfaded-versions-in-use=FAIL"));

    assertTrue(rootMessage(failure).contains(STILL_RUN_ON_AN_OUTFADED_VERSION.formatted("1")), rootMessage(failure));

  }

  @Test
  @Order(5)
  @DisplayName("Fading out the version this boot deploys is a configuration error")
  public void fadingOutTheDeployedVersionFailsTheStart() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> boot("v2", "--vanillabp.workflow-modules.c7-it.adapters.c7.outfaded-versions=*"));

    assertTrue(rootMessage(failure).contains("deployed during this boot"), rootMessage(failure));

  }

  @Test
  @Order(6)
  @DisplayName("A suspended version is checked like every other one")
  public void aSuspendedVersionIsStillReported(
      final CapturedOutput output) {

    suspendVersionOne();

    final var reported = whatIsReportedWhileBooting(output, "v2");
    assertTrue(
        reported.contains("'servedForAnUnknownVersion'"),
        "suspending version 1 does not answer what it still needs");
    assertTrue(
        reported.contains(A_VERSION_OF_A_PROCESS.formatted("1", "OldProcessVersionsProcess")),
        "the finding is still about version 1");
    assertTrue(reported.contains(STILL_RUN_ON_THIS_VERSION), "and its workflow is counted as before");

  }

  @Test
  @Order(7)
  @DisplayName("The emergency exit takes the suspended version out, loudly")
  public void theEmergencyExitTakesTheSuspendedVersionOut(
      final CapturedOutput output) {

    System.setProperty(SuspendedProcessDefinitions.IGNORE_PROPERTY, "true");
    try {
      final var reported = whatIsReportedWhileBooting(output, "v2");

      assertTrue(reported.contains("emergency exit for one start"), "the start says the switch was taken");
      assertTrue(
          reported.contains("version(s) 1 of BPMN process 'OldProcessVersionsProcess'"),
          "and names the version it left out");
      assertTrue(
          !reported.contains(SERVED_BY_NO_METHOD),
          "nothing is demanded of the suspended version any more");
      assertTrue(
          !reported.contains(STILL_RUN_ON_THIS_VERSION),
          "and its workflow is not counted either");
      // what the switch costs on the code side: version 1 is not among the versions the
      // check believes the engine holds, so the method kept for it looks dead
      assertTrue(reported.contains("droppedInVersionTwo' (version '1')"), "the method for it looks dead now");
    } finally {
      System.clearProperty(SuspendedProcessDefinitions.IGNORE_PROPERTY);
    }

  }

  /**
   * Suspends the older version at the engine, in a boot of its own - the cases after it
   * are about what the NEXT start makes of it.
   */
  private static void suspendVersionOne() {

    final var application = new SpringApplicationBuilder(TestApplication.class).run(DATABASE, resources("v2"));
    try {
      final var repositoryService = application.getBean(RepositoryService.class);
      final var versionOne = repositoryService
          .createProcessDefinitionQuery()
          .processDefinitionKey("OldProcessVersionsProcess")
          .tenantIdIn("c7-it")
          .processDefinitionVersion(1)
          .singleResult();
      if (versionOne == null) {
        throw new AssertionError("the engine does not hold version 1 any more - the cases before this one did");
      }
      repositoryService.suspendProcessDefinitionById(versionOne.getId());
    } finally {
      application.close();
    }

  }

  /**
   * What ONE boot wrote - the captured output accumulates over the whole class, so
   * every case looks at its own tail of it.
   */
  private static String whatIsReportedWhileBooting(
      final CapturedOutput output,
      final String version,
      final String... arguments) {

    final var before = output.getAll().length();
    boot(version, arguments);
    return output.getAll().substring(before);

  }

  private static void boot(
      final String version,
      final String... arguments) {

    final var boot = new String[arguments.length + 2];
    boot[0] = DATABASE;
    boot[1] = resources(version);
    System.arraycopy(arguments, 0, boot, 2, arguments.length);
    new SpringApplicationBuilder(TestApplication.class).run(boot).close();

  }

  private static String resources(
      final String version) {

    return "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/old-process-versions/%s"
        .formatted(version);

  }

  private static String rootMessage(
      final Throwable throwable) {

    var cause = throwable;
    while ((cause.getCause() != null) && (cause.getCause() != cause)) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

}
