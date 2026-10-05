package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.jobexecutor.JobAcquisitionContext;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the two keys of the due-date sleep decide, and what an application is told about
 * them while it boots.
 * <p>
 * The second key is the engine's metrics reporter. It writes its counters through a
 * database command every 900 seconds on a timer of its own, which would wake a sleeping
 * engine four times an hour and give the whole saving back, so it follows the sleep unless
 * the application says otherwise. The timing of an adapter id which configured neither key
 * does not change by a single statement, which is the other half of what is asserted here.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7JobExecutorSleepTest {

  private static final String ADAPTER_ID = "c7";

  private static StandaloneProcessEngineConfiguration aConfiguration() {

    return new StandaloneProcessEngineConfiguration();

  }

  @Test
  @DisplayName("the sleep is off until an adapter id asks for it")
  public void theSleepIsOffByDefault() {

    final var properties = new Camunda7EngineProperties();

    assertFalse(properties.sleepsUntilSomethingIsDue());
    assertTrue(
        properties.reportsMetricsToTheDatabase(),
        "an engine which polls anyway keeps Camunda's own metrics behaviour");

    final var configuration = aConfiguration();
    Camunda7JobExecutorSleep.applyTo(ADAPTER_ID, configuration, properties);

    assertTrue(configuration.isDbMetricsReporterActivate());
    assertFalse(
        configuration.isJobExecutorAcquireByDueDate(),
        "an adapter id which asked for nothing keeps the engine's acquisition order");
    assertNull(
        configuration.getCustomPostCommandInterceptorsTxRequired(),
        "nothing is hung off the transactions of an engine which was not asked to sleep");

  }

  @Test
  @DisplayName("the sleep brings the wake-up, the acquisition order and a quiet metrics reporter")
  public void theSleepBringsWhatItNeeds(
      final CapturedOutput output) {

    final var properties = new Camunda7EngineProperties();
    properties.setSleepUntilSomethingIsDue(true);

    assertFalse(
        properties.reportsMetricsToTheDatabase(),
        "an engine which is allowed to sleep must not be woken by its own metrics");

    final var configuration = aConfiguration();
    Camunda7JobExecutorSleep.applyTo(ADAPTER_ID, configuration, properties);

    assertFalse(configuration.isDbMetricsReporterActivate());
    assertTrue(configuration.isJobExecutorAcquireByDueDate());
    assertEquals(
        1,
        configuration
            .getCustomPostCommandInterceptorsTxRequired()
            .size(),
        "every engine command has to be able to wake the acquisition after its commit");
    assertEquals(
        1,
        configuration
            .getCustomPostCommandInterceptorsTxRequiresNew()
            .size(),
        "a retry is rescheduled in a transaction of its own, and its new due date counts");

    // the startup message has to carry what the operator still has to do and which key
    // brings the metrics back, so nobody needs the documentation for either
    assertTrue(output.getAll().contains("acquired by due date"), output.getAll());
    assertTrue(output.getAll().contains("ACT_RU_JOB"), output.getAll());
    assertTrue(output.getAll().contains("vanillabp.adapters.c7.db-metrics-reporting: true"), output.getAll());
    assertTrue(output.getAll().contains("vanillabp.outbox.poll-interval"), output.getAll());
    assertTrue(output.getAll().contains("runs once an hour"), output.getAll());

  }

  @Test
  @DisplayName("keeping the metrics reporter on a sleeping engine is said to cost the saving")
  public void keepingTheMetricsReporterIsWarnedAbout(
      final CapturedOutput output) {

    final var properties = new Camunda7EngineProperties();
    properties.setSleepUntilSomethingIsDue(true);
    properties.setDbMetricsReporting(Boolean.TRUE);

    final var configuration = aConfiguration();
    Camunda7JobExecutorSleep.applyTo(ADAPTER_ID, configuration, properties);

    assertTrue(configuration.isDbMetricsReporterActivate());
    assertTrue(output.getAll().contains("writes to the database every 900 seconds"), output.getAll());
    assertTrue(output.getAll().contains("saves nothing beyond 15 minutes"), output.getAll());

  }

  @Test
  @DisplayName("an engine which is busy keeps the waiting rule of the engine itself")
  public void aBusyCycleIsNotAskedAboutDueDates() {

    final var jobExecutor = Mockito.mock(JobExecutor.class);
    Mockito.when(jobExecutor.getWaitTimeInMillis()).thenReturn(Integer.valueOf(5000));
    Mockito.when(jobExecutor.getMaxWait()).thenReturn(Long.valueOf(60000));
    Mockito.when(jobExecutor.getWaitIncreaseFactor()).thenReturn(Float.valueOf(2));
    Mockito.when(jobExecutor.getBackoffTimeInMillis()).thenReturn(Integer.valueOf(1000));
    Mockito.when(jobExecutor.getMaxBackoff()).thenReturn(Long.valueOf(60000));
    Mockito.when(jobExecutor.getBackoffDecreaseThreshold()).thenReturn(Integer.valueOf(100));
    Mockito.when(jobExecutor.getMaxJobsPerAcquisition()).thenReturn(Integer.valueOf(3));

    final var strategy = new Camunda7SleepUntilSomethingIsDue(ADAPTER_ID, jobExecutor);

    // a cycle which lost a job to another node's lock is about load, and the superclass
    // backs off for exactly that
    final var context = Mockito.mock(JobAcquisitionContext.class);
    Mockito.when(context.hasJobAcquisitionLockFailureOccurred()).thenReturn(Boolean.TRUE);
    Mockito.when(context.getAcquiredJobsByEngine()).thenReturn(java.util.Map.of());
    Mockito.when(context.getRejectedJobsByEngine()).thenReturn(java.util.Map.of());
    Mockito.when(context.getAdditionalJobsByEngine()).thenReturn(java.util.Map.of());
    strategy.reconfigure(context);

    assertTrue(strategy.getWaitTime() > 0, "the superclass's backoff has to stand");
    Mockito
        .verify(jobExecutor, Mockito.never())
        .engineIterator();

  }

}
