package io.vanillabp.camunda7.quarkus.it;

import static io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck.A_VERSION_OF_A_PROCESS;
import static io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck.STILL_RUN_ON_THIS_VERSION;
import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The startup check about the versions the engine still holds, on Quarkus. An application
 * only ever brings its newest model, and what its {@code @WorkflowTask} methods have to keep
 * serving are the workflows still running on the versions before it - so the check reads the
 * engine's own models, counts the workflows on them and reports what the application no
 * longer serves.
 * <p>
 * <strong>Why this test looked impossible.</strong> The check has nothing to report unless
 * two generations of one model met one database, and a Quarkus prod-mode test builds its
 * application once and boots it once per class. It does not have to: the application is
 * built once here and STARTED TWICE, because three things this repository already has line
 * up. {@code QuarkusProdModeTest} can stop and start the built artifact on demand; the
 * resources location is runtime configuration, which the build-time resource index was made
 * for, so one archive can carry both generations and each boot picks one; and the datasource
 * url is runtime configuration too, so the two boots can meet on a file database which
 * outlives the first JVM. The alternative was to measure this one check on Spring Boot only
 * and write that into the coverage gate, which would have been the first entry of a list
 * that grows.
 * <p>
 * What the second boot finds: version 1 with a workflow parked in the task which version 2
 * dropped, a method which still serves that task for that version, and a method naming a
 * version the engine never held.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7OldProcessVersionsTest {

  private static final String MODULE_ID = "c7-versions";

  private static final int PORT = OneFreePortPerJvm.getPort();

  /**
   * A file database rather than an in-memory one: the first application's JVM is gone before
   * the second one boots, and what this test is about is the two of them meeting.
   */
  private static final Path DATABASE = Path
      .of("target", "c7-old-process-versions")
      .toAbsolutePath();

  static {
    // a database left behind by an earlier build would hold workflows this run never
    // started, and the check would report them
    removeTheDatabaseOfAnEarlierBuild();
  }

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addPackage("io.vanillabp.camunda7.quarkus.test.versions")
          .addAsResource("old-versions/application.yaml", "application.yaml")
          .addAsResource(
              "c7-versions/v1/old-process-versions-v1.bpmn",
              "c7-versions/v1/old-process-versions-v1.bpmn")
          .addAsResource(
              "c7-versions/v2/old-process-versions-v2.bpmn",
              "c7-versions/v2/old-process-versions-v2.bpmn")
          .addAsResource("workflow-module-descriptor/c7-versions", "META-INF/workflow-module"))
      // JVM args needed for tracking coverage - check this module's POM for the
      // systemPropertyVariables feeding 'jacoco.agent'
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .setRun(true)
      .setRuntimeProperties(theBootDeploying("v1"));

  /**
   * How long the test waits for the workflow of the first boot to park in the task version 2
   * drops. Generous, because a build machine running other builds is allowed to be slow.
   */
  private static final long PATIENCE = 60000;

  /**
   * What one boot of this application is configured with. Both values are runtime
   * configuration, which is what lets one built artifact be two generations of an
   * application.
   *
   * @param generation The directory of the model generation this boot deploys
   * @return The runtime properties of that boot
   */
  private static Map<String, String> theBootDeploying(
      final String generation) {

    final var properties = new LinkedHashMap<String, String>();
    properties.put("quarkus.http.port", Integer.toString(PORT));
    properties.put("quarkus.datasource.jdbc.url", "jdbc:h2:file:%s".formatted(DATABASE));
    properties
        .put(
            "vanillabp.workflow-modules.%s.adapters.c7.resources-location".formatted(MODULE_ID),
            "classpath:c7-versions/%s".formatted(generation));
    return properties;

  }

  private static void removeTheDatabaseOfAnEarlierBuild() {

    try {
      for (final var suffix : List.of(".mv.db", ".trace.db")) {
        Files.deleteIfExists(Path.of(DATABASE + suffix));
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

  }

  @Test
  @DisplayName("the boot after a model change reports the version its workflows still run on")
  public void theSecondBootReportsWhatStillRunsOnVersionOne() throws Exception {

    startAWorkflowAndLetItParkInTheDroppedTask();

    prodModeTest.stop();
    prodModeTest.setRuntimeProperties(theBootDeploying("v2"));
    prodModeTest.start();

    final var reported = prodModeTest.getStartupConsoleOutput();

    assertTrue(
        reported.contains(A_VERSION_OF_A_PROCESS.formatted("1", "OldProcessVersionsProcess")),
        () -> "the check has to report the version the parked workflow runs on: "
            + reported);
    assertTrue(
        reported.contains(STILL_RUN_ON_THIS_VERSION),
        () -> "and it has to count the workflows sitting on that version: "
            + reported);
    assertTrue(
        reported.contains("OldProcessVersionsProcess"),
        () -> "the report has to name the BPMN process: "
            + reported);
    // asserted in the shape the case below asks NOT to be there, so rewording that
    // sentence fails here rather than making the negative assertion go quiet
    assertTrue(
        reported.contains("definition(s) 'servedForAnUnknownVersion'"),
        () -> "the report has to name the task definition nobody serves for that version: "
            + reported);
    assertTrue(
        !reported.contains("definition(s) 'droppedInVersionTwo'"),
        () -> "the task the version-1 method still serves must not be reported as unserved: "
            + reported);
    assertTrue(
        reported.contains("outfaded-versions"),
        () -> "the report has to say how an operator retires a version: "
            + reported);

  }

  /**
   * Starts a workflow on the model of the first boot and waits until it sits in the task
   * version 2 no longer has. That parked workflow is the whole point: without it the engine
   * would hold an older version nobody runs on, and the check would have nothing to say.
   */
  private void startAWorkflowAndLetItParkInTheDroppedTask() throws InterruptedException {

    final var aggregateId = RestAssured
        .given()
        .baseUri("http://localhost")
        .port(PORT)
        .post("versions/workflows")
        .then()
        .statusCode(200)
        .extract()
        .asString();

    final var deadline = System.currentTimeMillis() + PATIENCE;
    for (;;) {
      final var parked = aggregates()
          .stream()
          .anyMatch(aggregate -> aggregate.startsWith(aggregateId
              + "|") && !aggregate.endsWith("|null"));
      if (parked) {
        return;
      }
      assertTrue(
          System.currentTimeMillis() < deadline,
          () -> "the workflow never parked in the task version 2 drops: "
              + aggregates());
      Thread.sleep(250);
    }

  }

  private List<String> aggregates() {

    return RestAssured
        .given()
        .baseUri("http://localhost")
        .port(PORT)
        .get("versions/aggregates")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getList("$", String.class);

  }

}
