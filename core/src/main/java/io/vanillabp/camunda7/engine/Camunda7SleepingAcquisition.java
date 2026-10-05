package io.vanillabp.camunda7.engine;

import java.util.concurrent.atomic.AtomicBoolean;

import org.camunda.bpm.engine.impl.jobexecutor.JobAcquisitionContext;
import org.camunda.bpm.engine.impl.jobexecutor.JobAcquisitionStrategy;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.camunda.bpm.engine.impl.jobexecutor.SequentialJobAcquisitionRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The acquisition loop of an executor which sleeps until something is due. The engine
 * decides the waiting rule in the strategy its loop creates, and creating that strategy
 * is one of the two reasons this class exists.
 * <p>
 * It also remembers which thread runs the loop. The loop's own acquisition commits like
 * any other engine command, so a wake-up hung off every commit would wake the loop from
 * inside itself and the sleep would never happen. {@link Camunda7WakeupAfterCommit} asks
 * here instead of guessing from a thread name.
 *
 * <h2>The other reason: a wake-up must not be lost between two cycles</h2>
 *
 * The engine's loop copies its "a job was added" flag into the cycle's context, asks the
 * strategy for the next wait, and clears the flag afterwards. A wake-up arriving in
 * between sets a flag which is wiped a moment later, and one arriving after that races
 * the moment the loop starts listening on its monitor. Camunda lives with both, and pays
 * one idle interval for them, five to sixty seconds.
 * <p>
 * Here the price is not an interval. The wait this adapter computes reaches to the next
 * due date, and where no job is due at all it reaches a year, so a lost wake-up is a job
 * which does not run until something else happens to wake the engine. That is why this
 * loop keeps a wake-up of its own: it is set whenever somebody asks the executor to wake
 * up, it is cleared where the next cycle begins, and the loop refuses to suspend while it
 * is set. Everything written after the cycle read the database therefore ends the wait
 * instead of being swallowed by it.
 * <p>
 * Why the wait reaches to a due date at all, and why it has no cap, is decision 18 in the
 * repository's DECISIONS.md; why a wake-up in that window is kept is decision 23.
 */
public class Camunda7SleepingAcquisition extends SequentialJobAcquisitionRunnable {

  private static final Logger log = LoggerFactory.getLogger(Camunda7SleepingAcquisition.class);

  private final String adapterId;

  /**
   * The acquisition context of this loop. It exists to mark where a cycle begins:
   * {@code reset()} is the first thing the loop does with it, before any engine is asked
   * for jobs, so everything which happens from there on is news this cycle has not seen.
   */
  private static final class CycleOfItsOwn extends JobAcquisitionContext {

    private final Runnable cycleBegins;

    private CycleOfItsOwn(
        final Runnable cycleBegins) {

      this.cycleBegins = cycleBegins;

    }

    @Override
    public void reset() {

      super.reset();
      cycleBegins.run();

    }

  }

  /**
   * Whether somebody asked this acquisition to wake up since the current cycle began.
   * Set before the executor's own flag, so a wake-up which sets this one and then finds
   * the loop listening on its monitor cannot be missed by both.
   */
  private final AtomicBoolean wokenSinceTheCycleBegan = new AtomicBoolean();

  /**
   * The thread running the acquisition cycles, set while the loop runs and
   * <code>null</code> otherwise.
   */
  private volatile Thread acquisitionThread;

  /**
   * The acquisition loop this adapter runs instead of the engine's own, because the wait
   * it computes is long enough that a lost wake-up would be a job nobody runs.
   *
   * @param adapterId The adapter id whose engine this belongs to, for the log
   * @param jobExecutor The engine's job executor, whose acquisition cycle this runs
   */
  public Camunda7SleepingAcquisition(
      final String adapterId,
      final JobExecutor jobExecutor) {

    super(jobExecutor);
    this.adapterId = adapterId;

  }

  @Override
  public void run() {

    acquisitionThread = Thread.currentThread();
    try {
      super.run();
    } finally {
      acquisitionThread = null;
    }

  }

  @Override
  protected JobAcquisitionContext initializeAcquisitionContext() {

    // called from the superclass constructor, so nothing of this class may be read here -
    // the callback runs once the loop resets the context, which is long afterwards
    return new CycleOfItsOwn(this::aCycleBegins);

  }

  private void aCycleBegins() {

    wokenSinceTheCycleBegan.set(false);

  }

  @Override
  protected JobAcquisitionStrategy initializeAcquisitionStrategy() {

    return new Camunda7SleepUntilSomethingIsDue(adapterId, jobExecutor);

  }

  @Override
  public void jobWasAdded() {

    // before the executor's own flag: the monitor below is entered in the opposite order,
    // so one of the two always sees the other
    wokenSinceTheCycleBegan.set(true);
    super.jobWasAdded();

  }

  /**
   * Waits for the given time, unless somebody asked for a wake-up while this cycle was
   * deciding how long to wait.
   * <p>
   * The engine's own version reads no flag at all before it waits, which is where a
   * wake-up arriving late in the cycle is lost. This one reads it after it announced that
   * it is listening, and that order is what makes the two sides meet: a wake-up sets the
   * flag first and looks for a listener second, so either the read below sees the flag and
   * the wait is skipped, or the wake-up sees the listener and ends the wait.
   *
   * @param millis How long the strategy asked to wait
   */
  @Override
  protected void suspendAcquisition(
      final long millis) {

    if (millis <= 0) {
      return;
    }
    try {
      synchronized (MONITOR) {
        if (isInterrupted) {
          return;
        }
        isWaiting.set(true);
        if (wokenSinceTheCycleBegan.get()) {
          return;
        }
        MONITOR.wait(millis);
      }
    } catch (final InterruptedException e) {
      // the engine ends this loop through its own flag and a notification, not through an
      // interrupt, and restoring the interrupt here would turn every later wait into an
      // immediate return - which is the spin this whole feature exists to avoid
      log.debug("Camunda7[{}]: the job acquisition was interrupted while waiting", adapterId, e);
    } finally {
      isWaiting.set(false);
    }

  }

  /**
   * Whether a thread is the one acquiring jobs. It is asked before a commit wakes the
   * acquisition: the acquisition waking itself would be a loop rather than a wake-up.
   *
   * @param thread A thread about to commit something
   * @return Whether it is the thread running the acquisition cycles
   */
  public boolean runsTheAcquisition(
      final Thread thread) {

    return (acquisitionThread != null) && (acquisitionThread == thread);

  }

}
