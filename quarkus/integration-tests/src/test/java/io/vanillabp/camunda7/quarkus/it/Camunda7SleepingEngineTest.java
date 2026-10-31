package io.vanillabp.camunda7.quarkus.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
 * A booted Quarkus application whose Camunda 7 engine may sleep, measured at its
 * datasource. While nothing is due, not one connection is taken. This feature lives by
 * that claim, and it is the only one a cloud bill can read.
 * <p>
 * Version 1 had no Quarkus artifact at all, so this is the half which did not exist. Three
 * things here are bound to Quarkus: the engine's own job executor, the JTA transaction which
 * the wake-up hangs off, and the runtime configuration key. The waiting rule itself belongs
 * to the core, and {@code Camunda7DueDateSleepTest} checks it against a real engine.
 * <p>
 * What is measured, and where the two bounds come from. The test checks the silence between
 * the moment the acquisition falls asleep and the moment it would wake up again. Both are
 * read from the engine, not from the clock:
 * <ul>
 * <li>the lower bound is the cycle which found nothing to do. The application reports it
 * as {@code lastCycleAt}. A cycle which found nothing is the one case in which the
 * acquisition waits for a due date, and does not back off after work. That is decision 18
 * in the repository's DECISIONS.md.</li>
 * <li>the upper bound is the due date at which the acquisition would wake up. The engine
 * holds no job at that point. So there is no due date to reach, and the sleep lasts until
 * something wakes it. Both ends of the measurement check that. So a window is only judged
 * while it really lies inside a sleep.</li>
 * </ul>
 * The wall-clock pause between the two readings only decides how much of the sleep is
 * sampled. A busy machine shortens what the test sees. But it can no longer move the
 * measurement outside the sleep. That used to make this test red under load, while the
 * engine behaved perfectly.
 * <p>
 * Whose connections count: the pool is shared, so a total says nothing about the engine.
 * Every take is recorded with the thread which made it. The test checks the takes of the
 * acquisition thread. The adapter itself answers which thread that is. It is the same
 * question {@code Camunda7WakeupAfterCommit} asks about every committing thread.
 * VanillaBP's outbox polls the same pool. Its interval is set to ten minutes here, so a poll
 * is rare and can be told apart. The feature itself switches off the engine's own metrics
 * reporter.
 * <p>
 * Nothing may ask the engine anything while the window is open. Every committed engine
 * command asks the acquisition to wake up, so a question about the sleep would end it. So
 * the readings inside the window go to the endpoint which reads memory only.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7SleepingEngineTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addPackage("io.vanillabp.camunda7.quarkus.test")
          .addAsResource("application.yaml")
          .addAsResource("c7-e2e/processes/task-matrix.bpmn")
          .addAsResource("c7-e2e/processes/signal-catch.bpmn")
          .addAsResource("c7-e2e/processes/aggregate-changed.bpmn")
          .addAsResource("c7-e2e/processes/timer-start.bpmn")
          .addAsResource("c7-e2e/processes/versioned-process.bpmn")
          .addAsResource("c7-e2e/processes/message-time-to-live.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // JVM args needed for tracking coverage. See this module's POM for the
      // systemPropertyVariables which feed 'jacoco.agent'
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .setRun(true)
      .setRuntimeProperties(Map.of(
          "quarkus.http.port", Integer.toString(OneFreePortPerJvm.getPort()),
          "quarkus.log.file.enable", "true",
          "quarkus.log.file.path", Path
              .of("target", "c7-sleeping-engine-application.log")
              .toAbsolutePath()
              .toString(),
          "vanillabp.adapters.c7.sleep-until-something-is-due", "true",
          // the outbox is the other poller of a quiet application. A measurement of the
          // engine's silence has to tell the two apart
          "vanillabp.outbox.poll-interval", "PT10M",
          "quarkus.datasource.jdbc.background-validation-interval", "0"));

  /**
   * How much of the sleep is sampled. The assertion does not rest on it. Whatever happens
   * in here is judged against the sleep which the engine itself reports. A shorter pause
   * looks at less, a longer one at more, and neither can change the verdict.
   */
  private static final long SAMPLED = 4000;

  /**
   * How long the test waits for the application to finish what booting left behind: the
   * deployment, the workflow which the module's timer start event begins, its outbox entry,
   * and the acquisition cycle which finds all of that done.
   */
  private static final long PATIENCE = 60000;

  /**
   * How often the acquisition is asked whether it has fallen asleep. Only the endpoint
   * which reads memory is asked, so this costs the database nothing.
   */
  private static final long ASKING_INTERVAL = 250;

  private static String plainText(
      final String path) {

    return RestAssured
        .given()
        .baseUri("http://localhost")
        .port(OneFreePortPerJvm.getPort())
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .asString();

  }

  private static Map<String, Object> json(
      final String path) {

    return RestAssured
        .given()
        .baseUri("http://localhost")
        .port(OneFreePortPerJvm.getPort())
        .get(path)
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getMap("$");

  }

  /**
   * What the acquisition is doing, read from memory only. Asking this does not wake it.
   */
  private static Map<String, Object> acquisition() {

    return json("introspect/engine/acquisition");

  }

  /**
   * When the engine's next job is due, and how many workflows still run. Asking this is an
   * engine command. So it costs a connection and wakes the acquisition. It belongs before a
   * measurement or after one, never inside.
   */
  private static Map<String, Object> whatIsStillDue() {

    return json("introspect/engine/next-due-date");

  }

  private static long lastCycleAt(
      final Map<String, Object> acquisition) {

    return ((Number) acquisition.get("lastCycleAt")).longValue();

  }

  @SuppressWarnings("unchecked")
  private static List<String> takenByTheAcquisition(
      final Map<String, Object> acquisition) {

    return (List<String>) acquisition.get("takenByTheAcquisition");

  }

  @Test
  @DisplayName("the property puts the sleeping acquisition into the application's engine")
  public void theApplicationGetsTheSleepingExecutor() {

    assertEquals("Camunda7SleepingJobExecutor", plainText("introspect/engine/job-executor"));

  }

  @Test
  @DisplayName("no connection is taken from the database while nothing is due")
  public void nothingIsAskedOfTheDatabaseWhileNothingIsDue() throws Exception {

    waitUntilNothingIsDueAnyMore();

    final var nothingLeft = whatIsStillDue();
    assertEquals("", nothingLeft.get("dueAt"), () -> "a job fell due again: %s".formatted(nothingLeft));

    final var fellAsleep = waitUntilTheAcquisitionFellAsleep();
    assertTrue(
        ((Number) fellAsleep.get("thread")).longValue() > 0,
        () -> "no thread was running the acquisition, so there was no sleep to measure: %s"
            .formatted(fellAsleep));

    // sampling the sleep. Nothing in here asks the engine anything, because a committed
    // engine command is exactly what ends a sleep
    Thread.sleep(SAMPLED);

    final var afterwards = acquisition();
    final var stillNothingDue = whatIsStillDue();

    assertEquals(
        lastCycleAt(fellAsleep),
        lastCycleAt(afterwards),
        () -> "the acquisition ran another cycle while nothing was due, so it did not sleep: %s"
            .formatted(afterwards));
    assertEquals(
        takenByTheAcquisition(fellAsleep),
        takenByTheAcquisition(afterwards),
        "a sleeping engine must not take a single connection while nothing is due");
    assertEquals(
        "",
        stillNothingDue.get("dueAt"),
        () -> "a job fell due while the test was looking, so the window was not a sleep: %s"
            .formatted(stillNothingDue));
    assertEquals(
        nothingLeft.get("runningWorkflows"),
        stillNothingDue.get("runningWorkflows"),
        "nothing may have started a workflow while the test was looking");

  }

  /**
   * Waits until the engine holds no job at all. That makes the sleep a sleep without an
   * end. The acquisition has no due date to wake up at. So the whole window the test looks
   * at lies inside one wait.
   * <p>
   * The boot has to finish first: the deployment, the workflow which the module's timer
   * start event begins, and the outbox entry which starts it.
   */
  private void waitUntilNothingIsDueAnyMore() throws InterruptedException {

    final var deadline = System.currentTimeMillis() + PATIENCE;
    for (;;) {
      final var due = whatIsStillDue();
      if ("".equals(due.get("dueAt"))) {
        return;
      }
      assertTrue(
          System.currentTimeMillis() < deadline,
          () -> "the engine still held a due job after the boot: %s".formatted(due));
      Thread.sleep(ASKING_INTERVAL);
    }

  }

  /**
   * Waits until the acquisition has run a cycle which found nothing to do. Before that cycle
   * the engine is not asleep at all. It backs off after work. A measurement started there
   * measures the backoff, not the sleep. That made this test red on a busy machine, while
   * the engine behaved exactly as promised.
   * <p>
   * Its thread has to be parked as well. The cycle which found nothing still runs for a
   * moment after it says so. The connection it gives back at its end would land inside a
   * window which was opened too early.
   *
   * Nothing beyond that is waited for. A wake-up is not waited for, on purpose. The test's
   * own questions to the engine are engine commands. A committed engine command asks the
   * acquisition to wake up. So an earlier version of this test sent such a wake-up itself,
   * and waited for the cycle it should cause. That timed out in four runs out of five on a
   * loaded machine (Camunda 7.24.0, 2026-09-14). The acquisition copies the flag which a
   * wake-up sets into its cycle, and clears it a few steps later. This adapter's due-date
   * query runs in between. So a wake-up which arrives there is dropped. Waiting for the
   * parked thread needs none of that. By then the wake-up was either answered or lost, and
   * neither leaves anything on its way into the window.
   *
   * @return What the acquisition reported once it was asleep
   */
  private Map<String, Object> waitUntilTheAcquisitionFellAsleep() throws InterruptedException {

    final var deadline = System.currentTimeMillis() + PATIENCE;
    for (;;) {
      final var acquisition = acquisition();
      if (Boolean.TRUE.equals(acquisition.get("foundNothing")) && Boolean.TRUE
          .equals(acquisition.get("parked"))) {
        return acquisition;
      }
      assertTrue(
          System.currentTimeMillis() < deadline,
          () -> "the acquisition never ran a cycle which found nothing to do: %s".formatted(acquisition));
      Thread.sleep(ASKING_INTERVAL);
    }

  }

}
