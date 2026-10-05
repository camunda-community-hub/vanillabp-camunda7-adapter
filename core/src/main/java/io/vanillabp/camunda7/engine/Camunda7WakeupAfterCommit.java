package io.vanillabp.camunda7.engine;

import org.camunda.bpm.engine.impl.cfg.TransactionState;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What wakes an acquisition which is sleeping until a due date. It hangs off the
 * TRANSACTION, not off a list of methods: every engine command registers a listener for
 * its own commit, and a job can only be written by an engine command, so everything which
 * creates one is covered. A workflow which is started, a message which is correlated, a
 * task which is completed, a signal, a continuation the engine wrote for itself, a retry
 * it rescheduled, a request to Cockpit or to the REST API all arrive here, and none of
 * them is named anywhere in this class.
 * <p>
 * The engine already does part of this by itself. {@code JobManager} hints its own executor
 * after a commit which inserted a job due NOW, and it hints for a timer only where the due
 * date falls inside the executor's wait time. So a job due LATER is the gap, and it is
 * exactly the gap the due-date sleep opens: without the sleep it shortens nothing, with
 * the sleep it is the difference between a timer firing on time and a timer firing when the
 * sleep happens to end.
 * <p>
 * The price is one extra acquisition cycle per committed engine command, which is
 * two indexed statements. The engine pays the same for every asynchronous continuation it
 * writes, and an application whose engine is never idle has no reason to switch the sleep
 * on. Several notifications for one transaction are one wake-up, because each of them sets
 * the same flag and notifies the same monitor.
 * <p>
 * One case stays uncovered: a job another application's node inserted into a shared
 * engine. That node's commit runs in its own process and reaches no listener here, so the
 * sleeping node learns about the job when its own due-date question next runs. Running two
 * applications against one engine is not a setup this adapter supports.
 * <p>
 * Why the waking hangs off the transaction, and why that transaction is reached through the
 * engine's command chain instead of a hook per platform, is decision 18 in the repository's
 * DECISIONS.md.
 */
public class Camunda7WakeupAfterCommit extends CommandInterceptor {

  private static final Logger log = LoggerFactory.getLogger(Camunda7WakeupAfterCommit.class);

  private final String adapterId;

  /**
   * Sits in the engine's command chain so that a transaction which wrote a job wakes the
   * acquisition when it commits.
   *
   * @param adapterId The adapter id whose engine this interceptor sits in, for the log
   */
  public Camunda7WakeupAfterCommit(
      final String adapterId) {

    this.adapterId = adapterId;

  }

  @Override
  public <T> T execute(
      final Command<T> command) {

    registerWakeup();
    return next.execute(command);

  }

  /**
   * Asks the current transaction to wake the acquisition once it committed. Registered
   * BEFORE the command runs, so a command which throws leaves nothing behind, and the
   * engine discards the listeners of a transaction which rolled back anyway.
   */
  private void registerWakeup() {

    final var commandContext = Context.getCommandContext();
    final var configuration = Context.getProcessEngineConfiguration();
    if ((commandContext == null) || (configuration == null)) {
      return;
    }
    final var jobExecutor = configuration.getJobExecutor();
    if (jobExecutor == null) {
      return;
    }
    // only the acquisition this adapter installed has a wait worth shortening: a stopped
    // executor has no loop at all, and an executor something else put in place has the
    // engine's own timing, where a wake-up from its own acquisition's commit would turn the
    // loop into a spin
    if (!(jobExecutor.getAcquireJobsRunnable() instanceof Camunda7SleepingAcquisition acquisition)) {
      return;
    }
    // the loop's own cycle commits like any other engine command, and waking the loop from
    // inside itself would end every wait immediately
    if (acquisition.runsTheAcquisition(Thread.currentThread())) {
      return;
    }

    try {
      // the executor is captured here rather than read in the listener: a transaction
      // listener may run on another thread than the command, so the engine's thread-local
      // context is not available where the wake-up happens
      commandContext
          .getTransactionContext()
          .addTransactionListener(
              TransactionState.COMMITTED,
              context -> jobExecutor.jobWasAdded());
    } catch (final RuntimeException e) {
      // a wake-up which cannot be registered is a sleep which ends a bit later, never a
      // reason to fail the caller's command
      log
          .debug(
              "Camunda7[{}]: could not ask this transaction to wake the job acquisition",
              adapterId,
              e);
    }

  }

}
