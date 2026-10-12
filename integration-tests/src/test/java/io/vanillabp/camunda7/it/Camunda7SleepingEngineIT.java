package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import io.vanillabp.camunda7.engine.Camunda7SleepingAcquisition;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.camunda7.springboot.engine.Camunda7SleepingSpringJobExecutor;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A booted Spring Boot application whose Camunda 7 engine was allowed to sleep, measured at
 * its datasource. While nothing is due, not one connection is taken. This feature lives by
 * that claim, and it is the only one which a cloud bill can read.
 * <p>
 * What is measured, and where the two bounds come from? The test asserts the silence
 * between the moment the acquisition falls asleep and the moment it would wake up again.
 * Both moments are read from the engine, not from the clock:
 * <ul>
 * <li>The lower bound is the cycle which found nothing to do. Only such a cycle waits for a
 * due date. A cycle which found work backs off instead, which is decision 18 in the
 * repository's DECISIONS.md.</li>
 * <li>The upper bound is the due date it would wake up at. The engine holds no job at that
 * point. So there is no due date to reach, and the sleep lasts until something wakes it.
 * Both ends of the measurement check that. So a window is only judged while it really lies
 * inside a sleep.</li>
 * </ul>
 * The pause on the wall clock between the two readings decides how much of the sleep is
 * sampled, and nothing else. A busy machine shortens what the test sees. But it can no
 * longer move the measurement outside the sleep. That used to make this test red under
 * load, while the engine behaved perfectly.
 * <p>
 * Whose connections count? The datasource is shared, so a total says nothing about the
 * engine. Every take is written down with the thread which made it. The test asserts the
 * takes of the acquisition thread. The adapter answers which thread that is, the same
 * question which {@code Camunda7WakeupAfterCommit} asks about every committing thread. The
 * outbox of VanillaBP polls the same datasource, and it is turned down to ten minutes here.
 * The feature itself switches off the own metrics reporter of the engine.
 * <p>
 * Nothing may ask the engine anything while the window is open. Every committed engine
 * command asks the acquisition to wake up, so a question about the sleep would end it.
 * <p>
 * The waiting rule itself is proven against a real engine in
 * {@code Camunda7DueDateSleepTest} of the core. That test includes a job due later, a
 * commit which shortens a sleep, and an engine which holds no job at all. What this test
 * adds is the wiring: a Spring Boot application which reads the property really gets that
 * engine.
 */
@SpringBootTest(classes = {
    TestApplication.class, CountedDataSourceConfiguration.class
}, properties = {
    "vanillabp.adapters.c7.sleep-until-something-is-due=true",
    // the outbox is the other poller of a quiet application. A measurement of the silence
    // of the engine has to be able to tell the two apart.
    "vanillabp.outbox.poll-interval=PT10M"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done. This IT has a datasource of its own, and so a context of
// its own. An engine which outlives its test would keep working on a database which the
// next classes use.
@DirtiesContext
public class Camunda7SleepingEngineIT {

  /**
   * How much of the sleep is sampled. The assertion does not rest on it. Whatever happens
   * in here is judged against the sleep which the engine itself reports. A shorter pause
   * looks at less and a longer one at more, and neither can change the verdict.
   */
  private static final long SAMPLED = 4000;

  /**
   * How long the test waits for the application to finish what booting left behind: the
   * deployment, the workflow which the timer start event of the module begins, its outbox
   * entry, and the acquisition cycle which finds all of that done.
   */
  private static final long PATIENCE = 60000;

  /**
   * How often the acquisition is asked whether it has fallen asleep. Only its context is
   * read, and that costs the database nothing.
   */
  private static final long ASKING_INTERVAL = 250;

  @Autowired
  private Camunda7EngineHolder engineHolder;

  @Test
  @DisplayName("the property puts the sleeping acquisition into the application's engine")
  public void theApplicationGetsTheSleepingExecutor() {

    final var jobExecutor = ((ProcessEngineConfigurationImpl) engineHolder
        .getProcessEngine()
        .getProcessEngineConfiguration()).getJobExecutor();

    assertInstanceOf(Camunda7SleepingSpringJobExecutor.class, jobExecutor);
    assertTrue(engineHolder.isJobExecutorActive(), "processing was started at boot");

  }

  @Test
  @DisplayName("no connection is taken from the database while nothing is due")
  public void nothingIsAskedOfTheDatabaseWhileNothingIsDue() throws Exception {

    waitUntilNothingIsDueAnyMore();

    final var runningWorkflows = runningWorkflows();
    assertNull(nextDueDate(), "a job fell due again");

    final var acquisitionThread = waitUntilTheAcquisitionFellAsleep();
    final var fellAsleepAt = lastCycleAt();
    final var takenWhenItFellAsleep = CountedDataSourceConfiguration.takenBy(acquisitionThread);

    // sampling the sleep. Nothing in here asks the engine anything, because a committed
    // engine command is exactly what ends a sleep.
    Thread.sleep(SAMPLED);

    final var takenAfterwards = CountedDataSourceConfiguration.takenBy(acquisitionThread);
    final var cycleAfterwards = lastCycleAt();

    assertEquals(
        fellAsleepAt,
        cycleAfterwards,
        "the acquisition ran another cycle while nothing was due, so it did not sleep");
    assertEquals(
        takenWhenItFellAsleep,
        takenAfterwards,
        "a sleeping engine must not take a single connection while nothing is due");
    assertNull(nextDueDate(), "a job fell due while the test was looking, so the window was not a sleep");
    assertEquals(runningWorkflows, runningWorkflows(),
        "nothing may have started a workflow while the test was looking");

  }

  /**
   * Waits until the engine holds no job at all. That makes the sleep a sleep without an
   * end. The acquisition has no due date to wake up at, so the whole window which the test
   * looks at lies inside one wait.
   * <p>
   * The boot has to finish first: the deployment, the workflow which the timer start event
   * of the module begins, and the outbox entry which starts it.
   */
  private void waitUntilNothingIsDueAnyMore() throws InterruptedException {

    final var deadline = System.currentTimeMillis() + PATIENCE;
    for (;;) {
      final var dueAt = nextDueDate();
      if (dueAt == null) {
        return;
      }
      assertTrue(
          System.currentTimeMillis() < deadline,
          () -> "the engine still held a job due at %s after the boot".formatted(dueAt));
      Thread.sleep(ASKING_INTERVAL);
    }

  }

  /**
   * Waits until the acquisition has run a cycle which found nothing to do. Before that
   * cycle the engine is not asleep at all. It is backing off after work, and a measurement
   * started there measures the backoff, not the sleep. That made this test red on a busy
   * machine, while the engine behaved exactly as promised.
   * <p>
   * Its thread has to be parked as well. The cycle which found nothing still runs for a
   * moment after it says so. The connection it gives back at its end would land inside a
   * window opened too early.
   *
   * Nothing beyond that is waited for, and on purpose no wake-up is waited for. The own
   * questions of the test to the engine are engine commands. A committed engine command
   * asks the acquisition to wake up. So an earlier version of this test sent such a wake-up
   * itself and waited for the cycle it should cause. That timed out in four runs out of
   * five on a loaded machine (Camunda 7.24.0, 2026-09-14). The acquisition copies the flag
   * which a wake-up sets into its cycle, and clears it a few steps later. The due-date
   * query of this adapter runs in between, so a wake-up which arrives there is dropped.
   * Waiting for the parked thread needs none of that. By then the wake-up has either been
   * answered or been lost, and neither leaves anything on its way into the window.
   *
   * @return The thread running the acquisition cycles
   */
  private long waitUntilTheAcquisitionFellAsleep() throws InterruptedException {

    final var deadline = System.currentTimeMillis() + PATIENCE;
    for (;;) {
      final var context = acquisition().getAcquisitionContext();
      final var foundNothing = context.areAllEnginesIdle() && !context
          .hasJobAcquisitionLockFailureOccurred() && (context.getAcquisitionException() == null);
      final var acquisitionThread = threadRunningTheAcquisition();
      if (foundNothing && isParked(acquisitionThread)) {
        return acquisitionThread;
      }
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the acquisition never ran a cycle which found nothing to do");
      Thread.sleep(ASKING_INTERVAL);
    }

  }

  /**
   * The acquisition loop of the engine of the application. Reading its context costs the
   * database nothing. That lets the test look while the window is open.
   */
  private Camunda7SleepingAcquisition acquisition() {

    final var runnable = ((ProcessEngineConfigurationImpl) engineHolder
        .getProcessEngine()
        .getProcessEngineConfiguration())
        .getJobExecutor()
        .getAcquireJobsRunnable();
    return assertInstanceOf(Camunda7SleepingAcquisition.class, runnable, "the engine has to run the sleeping loop");

  }

  private long lastCycleAt() {

    return acquisition()
        .getAcquisitionContext()
        .getAcquisitionTime();

  }

  /**
   * Which thread runs the acquisition cycles right now. The adapter answers that question
   * itself - {@code Camunda7WakeupAfterCommit} asks it about every committing thread - so
   * the threads of the application are held against that answer instead of against a name
   * somebody guessed.
   */
  private boolean isParked(
      final long threadId) {

    return Thread
        .getAllStackTraces()
        .keySet()
        .stream()
        .filter(thread -> thread.threadId() == threadId)
        .anyMatch(
            thread -> (thread.getState() == Thread.State.WAITING) || (thread.getState() == Thread.State.TIMED_WAITING));

  }

  private long threadRunningTheAcquisition() {

    final var acquisition = acquisition();
    return Thread
        .getAllStackTraces()
        .keySet()
        .stream()
        .filter(acquisition::runsTheAcquisition)
        .mapToLong(Thread::threadId)
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("no thread was running the acquisition, so there was no sleep to measure"));

  }

  /**
   * When the next job of the engine falls due, which is the moment a sleeping acquisition
   * wakes up at. It is the same question which the sleep itself asks: the earliest due date
   * of an active job which still has retries left.
   * <p>
   * Asking it costs a connection and wakes the acquisition, because every committed engine
   * command does. So it is asked before a measurement and after one, never in between.
   *
   * @return The due date in epoch milliseconds, or <code>null</code> where the engine holds
   *         no job at all. A job the engine gave no due date is due now, and is reported as
   *         the start of the epoch rather than as nothing
   */
  private Long nextDueDate() {

    final var due = engineHolder
        .getProcessEngine()
        .getManagementService()
        .createJobQuery()
        .active()
        .withRetriesLeft()
        .orderByJobDuedate()
        .asc()
        .listPage(0, 1);
    if (due.isEmpty()) {
      return null;
    }
    final var dueDate = due
        .get(0)
        .getDuedate();
    return Long.valueOf(dueDate == null ? 0L : dueDate.getTime());

  }

  private long runningWorkflows() {

    return engineHolder
        .getRuntimeService()
        .createProcessInstanceQuery()
        .count();

  }

}
