package io.vanillabp.camunda7.engine;

import org.camunda.bpm.engine.impl.cfg.TransactionState;
import org.camunda.bpm.engine.impl.context.Context;
import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What wakes an acquisition which is sleeping until a due date. It hangs off the
 * TRANSACTION, not off a list of methods. Every engine command registers a listener for
 * its own commit. Only an engine command can write a job. So everything which creates a
 * job is covered. A workflow which is started, a message which is correlated, a task which
 * is completed, a signal, a continuation the engine wrote for itself, a retry it
 * rescheduled, a request to Cockpit or to the REST API: all of them arrive here. None of
 * them is named anywhere in this class.
 * <p>
 * The engine already does part of this by itself. {@code JobManager} hints its own executor
 * after a commit which inserted a job due NOW. It hints for a timer only where the due date
 * falls inside the executor's wait time. So a job due LATER is the gap. And it is exactly
 * the gap which the due-date sleep opens. Without the sleep, a wake-up here shortens
 * nothing. With the sleep, it decides whether a timer fires on time or only when the sleep
 * happens to end.
 * <p>
 * The price is one extra acquisition cycle per committed engine command. That is two
 * indexed statements. The engine pays the same for every asynchronous continuation it
 * writes. And an application whose engine is never idle has no reason to switch the sleep
 * on. Several notifications for one transaction are one wake-up, because each of them sets
 * the same flag and notifies the same monitor.
 * <p>
 * One case stays uncovered: a job which another application's node inserted into a shared
 * engine. That node's commit runs in its own process and reaches no listener here. So the
 * sleeping node learns about the job when its own due-date question runs next. This adapter
 * does not support two applications on one engine.
 * <p>
 * Decision 18 in the repository's DECISIONS.md says why the waking hangs off the
 * transaction. It also says why we reach that transaction through the engine's command
 * chain, and not through a hook per platform.
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
   * Asks the current transaction to wake the acquisition once it committed. It is
   * registered BEFORE the command runs. So a command which throws leaves nothing behind,
   * because the engine discards the listeners of a transaction which rolled back anyway.
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
    // only the acquisition this adapter installed has a wait worth shortening. A stopped
    // executor has no loop at all. An executor which something else put in place has the
    // engine's own timing. There a wake-up from its own acquisition's commit would turn the
    // loop into a spin
    if (!(jobExecutor.getAcquireJobsRunnable() instanceof Camunda7SleepingAcquisition acquisition)) {
      return;
    }
    // the loop's own cycle commits like any other engine command. Waking the loop from
    // inside itself would end every wait at once
    if (acquisition.runsTheAcquisition(Thread.currentThread())) {
      return;
    }

    try {
      // the executor is captured here and not read in the listener. A transaction
      // listener may run on another thread than the command. So the engine's thread-local
      // context is not there when the wake-up happens
      commandContext
          .getTransactionContext()
          .addTransactionListener(
              TransactionState.COMMITTED,
              context -> jobExecutor.jobWasAdded());
    } catch (final RuntimeException e) {
      // a wake-up which cannot be registered means a sleep which ends a bit later. It is
      // never a reason to fail the caller's command
      log
          .debug(
              "Camunda7[{}]: could not ask this transaction to wake the job acquisition",
              adapterId,
              e);
    }

  }

}
