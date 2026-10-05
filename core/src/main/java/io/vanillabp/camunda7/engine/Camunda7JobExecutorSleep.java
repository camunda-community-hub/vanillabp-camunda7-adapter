package io.vanillabp.camunda7.engine;

import java.util.ArrayList;
import java.util.List;

import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.interceptor.CommandInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything the due-date sleep needs on an engine being built, for both platforms. What
 * stays with the platform is the executor itself, because the class it extends is the
 * platform's: {@link Camunda7SleepingJobExecutor} where the engine owns the thread pool,
 * the Spring Boot module's counterpart where the application owns it.
 * <p>
 * Three things happen here where the sleep is configured. The commit of every engine
 * command is asked to wake the acquisition ({@link Camunda7WakeupAfterCommit}). Jobs are
 * acquired by due date, because waking for the earliest due job and then acquiring in an
 * unrelated order would pick the wrong job where more are due than one cycle takes. And
 * the engine's metrics reporter is switched off, since it writes through a database command
 * every 900 seconds and would wake an engine four times an hour that was meant to stay
 * quiet.
 * <p>
 * The startup message says all of it, including what is still awake, because a developer
 * who switches this on and watches their database has to be able to tell which traffic is
 * whose.
 * <p>
 * The reasons behind each of the three are decision 18 in the repository's DECISIONS.md.
 */
public final class Camunda7JobExecutorSleep {

  private static final Logger log = LoggerFactory.getLogger(Camunda7JobExecutorSleep.class);

  private Camunda7JobExecutorSleep() {
    // static helper
  }

  /**
   * Applies what is the same on both platforms to an engine which is about to be built.
   * Called for every adapter id, whether or not the sleep is configured: the metrics
   * reporter is an engine setting of its own, and an id which configured neither key keeps
   * the engine's own behaviour down to the last statement.
   *
   * @param adapterId The adapter id whose engine is being built
   * @param configuration The engine configuration, before the engine is built
   * @param properties The adapter id's engine settings
   */
  public static void applyTo(
      final String adapterId,
      final ProcessEngineConfigurationImpl configuration,
      final Camunda7EngineProperties properties) {

    configuration.setDbMetricsReporterActivate(properties.reportsMetricsToTheDatabase());
    if (!properties.sleepsUntilSomethingIsDue()) {
      return;
    }

    configuration.setJobExecutorAcquireByDueDate(true);
    configuration
        .setCustomPostCommandInterceptorsTxRequired(
            withWakeup(adapterId, configuration.getCustomPostCommandInterceptorsTxRequired()));
    // a job which failed is rescheduled in a transaction of its own, and the new due date
    // of such a retry is exactly the kind of future job this feature sleeps on
    configuration
        .setCustomPostCommandInterceptorsTxRequiresNew(
            withWakeup(adapterId, configuration.getCustomPostCommandInterceptorsTxRequiresNew()));

    announce(adapterId, properties);

  }

  /**
   * The custom post-command interceptors of the engine plus the wake-up. Post-command
   * means inside the command context, which is where a transaction listener can be
   * registered; the list is <code>null</code> until somebody fills it.
   */
  private static List<CommandInterceptor> withWakeup(
      final String adapterId,
      final List<CommandInterceptor> configured) {

    final var interceptors = (configured == null)
        ? new ArrayList<CommandInterceptor>()
        : new ArrayList<>(configured);
    interceptors.add(new Camunda7WakeupAfterCommit(adapterId));
    return interceptors;

  }

  /**
   * What the application is told on the boot which switched the sleep on: what changed,
   * what the operator still has to do, and what keeps talking to the database anyway.
   */
  private static void announce(
      final String adapterId,
      final Camunda7EngineProperties properties) {

    log
        .info(
            """
                Camunda7[{}]: the job executor sleeps until the next job is due instead of polling every \
                5 to 60 seconds, and a transaction which writes a job wakes it. Jobs are acquired by due \
                date now, which is not the engine's default, so add a database index on the due date of \
                the ACT_RU_JOB table (see \
                https://docs.camunda.org/manual/7.24/user-guide/process-engine/the-job-executor/). The \
                setting 'jobExecutorPreferTimerJobs' is left as the engine has it, because a preference \
                between kinds of job is not what this feature needs.""",
            adapterId);
    if (properties.reportsMetricsToTheDatabase()) {
      log
          .warn(
              """
                  Camunda7[{}]: the engine's metrics reporter stays on \
                  ('vanillabp.adapters.{}.db-metrics-reporting: true') and writes to the database every \
                  900 seconds. The job executor's sleep then saves nothing beyond 15 minutes at a \
                  time.""",
              adapterId,
              adapterId);
    } else {
      log
          .info(
              """
                  Camunda7[{}]: the engine's metrics reporter is switched off. It would have written to \
                  the database every 900 seconds and woken this engine four times an hour. Set \
                  'vanillabp.adapters.{}.db-metrics-reporting: true' to keep it.""",
              adapterId,
              adapterId);
    }
    log
        .info(
            """
                Camunda7[{}]: what is still awake in this application: VanillaBP's phase-two outbox polls \
                its store every 10 seconds unless 'vanillabp.outbox.poll-interval' says otherwise, and the \
                retention cleanup of the task delivery log runs once an hour. Until those sleep too, a \
                quiet database still sees VanillaBP rather than this engine.""",
            adapterId);

  }

}
