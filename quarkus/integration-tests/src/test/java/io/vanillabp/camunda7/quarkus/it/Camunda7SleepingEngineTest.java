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
 * A booted Quarkus application whose Camunda 7 engine was allowed to sleep, measured at its
 * datasource: while nothing is due, not one connection is taken. That is the claim this
 * feature lives by, and it is the only one a cloud bill can read.
 * <p>
 * Version 1 had no Quarkus artifact at all, so this is the half which did not exist. What
 * is Quarkus-bound here is the engine's own job executor, the JTA transaction the wake-up
 * hangs off and the runtime configuration key; the waiting rule itself is the core's, and
 * {@code Camunda7DueDateSleepTest} holds it against a real engine.
 * <p>
 * What is measured, and where the two bounds come from. The silence
 * asserted is the one between the acquisition falling asleep and the moment it would wake
 * up again, and both of those are read from the engine rather than from the clock:
 * <ul>
 * <li>the lower bound is the cycle which found nothing to do. The application reports it
 * as {@code lastCycleAt}, and a cycle which found nothing is the one case in which the
 * acquisition waits for a due date instead of backing off after work, which is decision 18
 * in the repository's DECISIONS.md;</li>
 * <li>the upper bound is the due date the acquisition would wake up at. The engine holds no
 * job at that point, so there is no due date to reach and the sleep lasts until something
 * wakes it. Both ends of the measurement check that, so a window is only ever judged while
 * it really does lie inside a sleep.</li>
 * </ul>
 * The wall-clock pause between the two readings decides how much of the sleep is sampled
 * and nothing else. A machine which is busy shortens what the test sees; it can no longer
 * move the measurement outside the sleep, which is what used to make this test red under
 * load while the engine behaved perfectly.
 * <p>
 * Whose connections count: the pool is shared, so a total says nothing
 * about the engine. Every take is recorded with the thread that made it, and what is
 * asserted are the takes of the acquisition thread - the adapter itself answers which
 * thread that is, the same question {@code Camunda7WakeupAfterCommit} asks about every
 * committing thread. VanillaBP's outbox polls the same pool, which is turned down to ten
 * minutes here so that a poll is rare as well as attributable, and the engine's own metrics
 * reporter is switched off by the feature itself.
 * <p>
 * Nothing may ask the engine anything while the window is open. Every committed engine
 * command asks the acquisition to wake up, so a question about the sleep would end it. The
 * readings inside the window therefore go to the endpoint which reads memory only.
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
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // JVM args needed for tracking coverage - check this module's POM for the
      // systemPropertyVariables feeding 'jacoco.agent'
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
          // the outbox is the other poller of a quiet application, and a measurement of the
          // engine's silence has to be able to tell the two apart
          "vanillabp.outbox.poll-interval", "PT10M",
          "quarkus.datasource.jdbc.background-validation-interval", "0"));

  /**
   * How much of the sleep is sampled. It is not what the assertion rests on: whatever
   * happens in here is judged against the sleep the engine itself reports, so a shorter
   * pause looks at less and a longer one at more, and neither can change the verdict.
   */
  private static final long SAMPLED = 4000;

  /**
   * How long the test waits for the application to finish what booting left behind: the
   * deployment, the workflow the module's timer start event begins, the outbox entry of it,
   * and the acquisition cycle which finds all of that done.
   */
  private static final long PATIENCE = 60000;

  /**
   * How often the acquisition is asked whether it has fallen asleep. Only the endpoint
   * reading memory is asked, so this costs the database nothing.
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
   * When the engine's next job is due and how many workflows still run. Asking this is an
   * engine command, so it costs a connection and it wakes the acquisition - it belongs
   * before a measurement or after one, never inside.
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
   * Waits until the engine holds no job at all. That is what makes the sleep a sleep
   * without an end: the acquisition has no due date to wake up at, so the whole window the
   * test looks at lies inside one wait.
   * <p>
   * What has to finish first is the boot: the deployment, the workflow the module's timer
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
   * Waits until the acquisition has run a cycle which found nothing to do. Before that
   * cycle the engine is not asleep at all - it is backing off after work, and a
   * measurement started there measures the backoff rather than the sleep. That is what
   * made this test red on a busy machine while the engine behaved exactly as promised.
   * <p>
   * Its thread has to be parked as well. The cycle which found nothing is still running for a
   * moment after it says so, and the connection it gives back at the end of it would land
   * inside a window opened too early.
   *
   * Nothing beyond that is waited for, and a wake-up is deliberately not waited for. The
   * test's own questions of the engine are engine commands, and a committed engine command
   * asks the acquisition to wake up, so an earlier version of this test sent such a wake-up
   * itself and waited for the cycle it should cause. That timed out in four runs out of five
   * on a loaded machine (Camunda 7.24.0, 2026-09-14): the acquisition copies the flag a
   * wake-up sets into its cycle and clears it a few steps later, and this adapter's due-date
   * query runs in between, so a wake-up arriving there is dropped. Waiting for the parked
   * thread needs none of that - by then the wake-up has either been answered or been lost,
   * and neither leaves anything on its way into the window.
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
