package io.vanillabp.camunda7.engine;

import java.time.Instant;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.camunda.bpm.engine.impl.ProcessEngineImpl;
import org.camunda.bpm.engine.impl.jobexecutor.BackoffJobAcquisitionStrategy;
import org.camunda.bpm.engine.impl.jobexecutor.JobAcquisitionContext;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.camunda.bpm.engine.impl.util.ClassLoaderUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How long the job acquisition waits between two cycles, for an engine which has nothing
 * to do. The engine's own answer is a fixed interval of 5 seconds widening to 60, and
 * every one of those cycles is a database command. This one asks the database once when
 * the next job is due and waits exactly that long.
 * <p>
 * Only a cycle which found nothing is changed here, and found nothing means
 * all four of these at once: every registered engine handed out fewer jobs than were asked
 * for, no job was lost to another node's lock, the acquisition itself did not fail, and
 * the threads executing jobs were not full. Anything else leaves the decision with the
 * superclass, because then the wait is about load rather than about a due date.
 * <p>
 * A cycle which found nothing is asked one question: which job has the earliest due date
 * later than the moment that cycle started. The wait is the distance to it. A job due at or
 * before that moment is deliberately not part of the answer, because the cycle which just
 * ran asked for exactly those and got nothing. Where no job is due at all there is nothing
 * to wake up for, and the acquisition waits until somebody wakes it, which is what
 * {@link Camunda7WakeupAfterCommit} does after a transaction which wrote one.
 * <p>
 * Nothing is held open across the wait. Each acquisition ran as an engine command which
 * gave its connection back when it finished, and there is one acquisition thread per
 * executor, so the sleep is a thread waiting on a monitor and nothing else.
 * <p>
 * Why the wait is driven by a due date, and why it uses the clock of the cycle which ran
 * rather than a fresh one, is decision 18 in the repository's DECISIONS.md.
 */
public class Camunda7SleepUntilSomethingIsDue extends BackoffJobAcquisitionStrategy {

  private static final Logger log = LoggerFactory.getLogger(Camunda7SleepUntilSomethingIsDue.class);

  /**
   * The wait used where no job is due at all. It is a number rather than "forever" because
   * the acquisition loop subtracts the start of the cycle from whatever this strategy
   * returns: {@code Long.MAX_VALUE} overflows there into a negative value, the loop clamps
   * that to zero and the executor spins instead of sleeping. A year is long enough that
   * the one query it costs cannot be measured, and small enough to stay out of that
   * arithmetic.
   */
  private static final long UNTIL_SOMEBODY_WAKES_US = TimeUnit.DAYS.toMillis(365);

  private final String adapterId;

  private final JobExecutor jobExecutor;

  private long waitTime;

  /**
   * The strategy answering how long the acquisition waits, in place of the widening
   * interval the engine brings.
   *
   * @param adapterId The adapter id whose engine this belongs to, for the log
   * @param jobExecutor The engine's job executor, whose waiting this replaces
   */
  public Camunda7SleepUntilSomethingIsDue(
      final String adapterId,
      final JobExecutor jobExecutor) {

    super(jobExecutor);
    this.adapterId = adapterId;
    this.jobExecutor = jobExecutor;

  }

  @Override
  public long getWaitTime() {

    return waitTime;

  }

  @Override
  public void reconfigure(
      final JobAcquisitionContext context) {

    super.reconfigure(context);
    waitTime = super.getWaitTime();
    if (!foundNothing(context)) {
      return;
    }

    // the clock of the cycle which just ran, not the current one. The cycle asked the
    // database for everything due at that moment and got nothing, so a job due later than
    // that moment is what is left to wait for. Reading the clock again here would open a
    // gap: a job falling due between the acquisition and this question would be in
    // neither answer, and the acquisition would sleep through it. The loop subtracts the
    // same moment from the wait it gets back, so a job which came due meanwhile lets the
    // next cycle start immediately
    final var cycleStarted = context.getAcquisitionTime();
    try {
      final var nextDueDate = earliestDueDate(cycleStarted);
      if (nextDueDate == null) {
        waitTime = UNTIL_SOMEBODY_WAKES_US;
        log
            .debug(
                "Camunda7[{}]: no job is due, the acquisition waits until something wakes it",
                adapterId);
      } else {
        waitTime = Math.max(0, nextDueDate.longValue() - cycleStarted);
        log
            .debug(
                "Camunda7[{}]: nothing to do, the acquisition waits until {}",
                adapterId,
                Instant.ofEpochMilli(nextDueDate.longValue()));
      }
    } catch (final RuntimeException e) {
      // a question which failed must not turn into a long sleep: falling back to the
      // superclass's wait time is the timing of an engine without this feature, which is
      // the one behaviour that is always safe here
      log
          .warn(
              "Camunda7[{}]: asking for the next due job failed, waiting {} ms instead",
              adapterId,
              Long.valueOf(waitTime),
              e);
    }

  }

  /**
   * Whether the cycle this context describes left the engine with nothing to do, the only
   * case whose wait this strategy decides (see the class comment).
   * <p>
   * A cycle somebody asked to wake up for is not such a cycle, however little it found.
   * The wake-up says a job was written after the acquisition read the database, so the
   * answer to the due-date question is older than the job and the engine's own timing is
   * the right one: the superclass returns no wait at all for it and the next cycle starts
   * at once.
   *
   * @param context What the cycle which just ran reported
   * @return Whether the due date decides the next wait
   */
  private boolean foundNothing(
      final JobAcquisitionContext context) {

    return context.areAllEnginesIdle() && !context.isJobAdded() && !context
        .hasJobAcquisitionLockFailureOccurred() && (context.getAcquisitionException() == null) && !executionSaturated;

  }

  /**
   * When the next job of any engine this executor serves is due, or <code>null</code>
   * where none is.
   */
  private Long earliestDueDate(
      final long cycleStarted) {

    // the engine's classloader has to be in place while an engine command runs, which is
    // why the acquisition loop switches to it around its own commands and why this
    // question does the same
    final var callersClassLoader = ClassLoaderUtil.switchToProcessEngineClassloader();
    try {
      Long earliest = null;
      final var engines = jobExecutor.engineIterator();
      while (engines.hasNext()) {
        final var engine = engines.next();
        if (!jobExecutor.hasRegisteredEngine(engine)) {
          // unregistered while this loop was running
          continue;
        }
        final var dueDate = earliestDueDateOf(engine, cycleStarted);
        if ((dueDate != null) && ((earliest == null) || (dueDate.longValue() < earliest.longValue()))) {
          earliest = dueDate;
        }
      }
      return earliest;
    } finally {
      ClassLoaderUtil.setContextClassloader(callersClassLoader);
    }

  }

  private Long earliestDueDateOf(
      final ProcessEngineImpl engine,
      final long cycleStarted) {

    // ONE row, not the list of every future job: the question is when to wake up, and
    // every further row is read by the database and looked at by nobody
    final var jobs = engine
        .getManagementService()
        .createJobQuery()
        .active()
        .withRetriesLeft()
        .duedateHigherThan(new Date(cycleStarted))
        .orderByJobDuedate()
        .asc()
        .listPage(0, 1);
    if (jobs.isEmpty()) {
      return null;
    }
    final var dueDate = jobs
        .get(0)
        .getDuedate();
    // the filter asks for a due date later than the cycle's clock, so a row without one
    // cannot come back; if an engine ever let one through, such a job is due immediately
    return (dueDate == null)
        ? Long.valueOf(cycleStarted)
        : Long.valueOf(dueDate.getTime());

  }

}
