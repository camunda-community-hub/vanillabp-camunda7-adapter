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
 * its datasource: while nothing is due, not one connection is taken. That is the claim this
 * feature lives by, and it is the only one a cloud bill can read.
 * <p>
 * What is measured, and where the two bounds come from. The silence
 * asserted is the one between the acquisition falling asleep and the moment it would wake up
 * again, and both of those are read from the engine rather than from the clock:
 * <ul>
 * <li>the lower bound is the cycle which found nothing to do. Only such a cycle waits for a
 * due date; a cycle which found work backs off instead, which is decision 18 in the
 * repository's DECISIONS.md;</li>
 * <li>the upper bound is the due date it would wake up at. The engine holds no job at that
 * point, so there is none to reach and the sleep lasts until something wakes it. Both ends
 * of the measurement check that, so a window is only ever judged while it really does lie
 * inside a sleep.</li>
 * </ul>
 * The wall-clock pause between the two readings decides how much of the sleep is sampled and
 * nothing else. A machine which is busy shortens what the test sees; it can no longer move
 * the measurement outside the sleep, which is what used to make this test red under load
 * while the engine behaved perfectly.
 * <p>
 * Whose connections count: the datasource is shared, so a total says
 * nothing about the engine. Every take is written down with the thread that made it, and
 * what is asserted are the takes of the acquisition thread - the adapter answers which
 * thread that is, the same question {@code Camunda7WakeupAfterCommit} asks about every
 * committing thread. VanillaBP's outbox polls the same datasource, which is turned down to
 * ten minutes here, and the engine's own metrics reporter is switched off by the feature
 * itself.
 * <p>
 * Nothing may ask the engine anything while the window is open: every committed engine
 * command asks the acquisition to wake up, so a question about the sleep would end it.
 * <p>
 * The waiting rule itself is proven against a real engine in the core's
 * {@code Camunda7DueDateSleepTest}, including a job due later, a commit which shortens a
 * sleep and an engine holding no job at all. What this test adds is the wiring: that a
 * Spring Boot application reading the property really gets that engine.
 */
@SpringBootTest(classes = {
    TestApplication.class, CountedDataSourceConfiguration.class
}, properties = {
    "vanillabp.adapters.c7.sleep-until-something-is-due=true",
    // the outbox is the other poller of a quiet application, and a measurement of the
    // engine's silence has to be able to tell the two apart
    "vanillabp.outbox.poll-interval=PT10M"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a datasource (and therefore a context) of its
// own, and an engine outliving its test would keep working on a database the next classes use
@DirtiesContext
public class Camunda7SleepingEngineIT {

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
   * How often the acquisition is asked whether it has fallen asleep. Only its context is
   * read, which costs the database nothing.
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
    // engine command is exactly what ends a sleep
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
   * Waits until the engine holds no job at all. That is what makes the sleep a sleep without
   * an end: the acquisition has no due date to wake up at, so the whole window the test
   * looks at lies inside one wait.
   * <p>
   * What has to finish first is the boot: the deployment, the workflow the module's timer
   * start event begins, and the outbox entry which starts it.
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
   * Waits until the acquisition has run a cycle which found nothing to do. Before that cycle
   * the engine is not asleep at all - it is backing off after work, and a measurement
   * started there measures the backoff rather than the sleep. That is what made this test
   * red on a busy machine while the engine behaved exactly as promised.
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
   * The acquisition loop of the application's engine. Reading its context costs the database
   * nothing, which is what lets the test look while the window is open.
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
   * When the engine's next job falls due, which is the moment a sleeping acquisition wakes
   * up at. The same question the sleep itself asks: the earliest due date of an active job
   * which still has retries left.
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
