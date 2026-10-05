package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.jobexecutor.AcquiredJobs;
import org.camunda.bpm.engine.impl.jobexecutor.JobAcquisitionContext;
import org.camunda.bpm.engine.impl.jobexecutor.JobAcquisitionStrategy;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The window in which a wake-up used to disappear, and what it cost here.
 * <p>
 * The engine's acquisition loop copies its "a job was added" flag into the cycle it just
 * ran, asks the strategy how long to wait, and clears the flag afterwards. Camunda pays
 * one idle interval for a wake-up landing in that window. This adapter asks the database
 * for the next due date inside that same window, and the wait it comes back with reaches
 * to that due date - or to a year where no job is due at all. So the same lost wake-up
 * stops being a late cycle and becomes a job which never runs.
 * <p>
 * Both tests hang on state and not on a clock. The first arranges the job to be written
 * from inside the window itself, so there is nothing to time; the second asks the strategy
 * what it decides for a cycle somebody woke, which is a question with one answer.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7WakeupInTheGapTest {

  /**
   * How long a test waits for a workflow which should have run. Generous, because a busy
   * build machine is allowed to be slow; what fails the test is the year-long sleep, not a
   * second of delay.
   */
  private static final long PATIENCE = 20000;

  private static final String WORKER = "AWorker";

  private ProcessEngine processEngine;

  private JobExecutor jobExecutor;

  @AfterEach
  public void closeTheEngine() {

    if (jobExecutor != null) {
      jobExecutor.shutdown();
    }
    if (processEngine != null) {
      processEngine.close();
    }

  }

  /**
   * What the job of the workflow below does, and the only thing this test watches. It is
   * a bean of the engine rather than a question the test asks, because every question
   * asked of this engine is an engine command, and a committed engine command wakes the
   * acquisition - a test polling the engine would wake the sleep it is measuring.
   */
  public static final class TheJobRan {

    private final CountDownLatch ran = new CountDownLatch(1);

    public boolean report() {

      ran.countDown();
      return true;

    }

  }

  private final TheJobRan theJobRan = new TheJobRan();

  /**
   * A workflow with one job to do, due the moment it is started.
   */
  private static BpmnModelInstance aWorkflowWithSomethingToDoNow() {

    return Bpmn
        .createExecutableProcess(WORKER)
        .startEvent()
        .serviceTask()
        .camundaAsyncBefore()
        .camundaExpression("${theJobRan.report()}")
        .endEvent()
        .done();

  }

  /**
   * An acquisition which lets the test act exactly once, inside the window: after the
   * strategy asked the database for the next due date and before the loop clears the flag
   * a wake-up sets. Everything the loop does from there on is what production does.
   */
  private static final class AcquisitionOpeningTheWindow extends Camunda7SleepingAcquisition {

    private final AtomicBoolean windowStillToUse = new AtomicBoolean(true);

    private final Runnable whileTheWindowIsOpen;

    private AcquisitionOpeningTheWindow(
        final String adapterId,
        final JobExecutor jobExecutor,
        final Runnable whileTheWindowIsOpen) {

      super(adapterId, jobExecutor);
      this.whileTheWindowIsOpen = whileTheWindowIsOpen;

    }

    @Override
    protected void configureNextAcquisitionCycle(
        final JobAcquisitionContext acquisitionContext,
        final JobAcquisitionStrategy acquisitionStrategy) {

      super.configureNextAcquisitionCycle(acquisitionContext, acquisitionStrategy);
      if (windowStillToUse.compareAndSet(true, false)) {
        whileTheWindowIsOpen.run();
      }

    }

  }

  /**
   * The executor of such an acquisition. The engine creates its loop while it initializes
   * and keeps no factory for it, which is why the adapter's own executor subclasses it as
   * well.
   */
  private static final class ExecutorOpeningTheWindow extends Camunda7SleepingJobExecutor {

    private final Runnable whileTheWindowIsOpen;

    private ExecutorOpeningTheWindow(
        final String adapterId,
        final Runnable whileTheWindowIsOpen) {

      super(adapterId);
      this.whileTheWindowIsOpen = whileTheWindowIsOpen;

    }

    @Override
    protected void ensureInitialization() {

      super.ensureInitialization();
      acquireJobsRunnable = new AcquisitionOpeningTheWindow(
          "c7-gap", this, whileTheWindowIsOpen);

    }

  }

  /**
   * Starts the workflow from a thread of its own and waits for its commit, so that by the
   * time this returns the job is in the database and the wake-up has been sent. Called
   * from the acquisition thread, which must not start the workflow itself: a commit of the
   * acquisition thread is deliberately not a wake-up (see
   * {@link Camunda7WakeupAfterCommit}).
   */
  private void aJobIsWrittenByAnotherThread() {

    final var writer = new Thread(
        () -> processEngine
            .getRuntimeService()
            .startProcessInstanceByKey(WORKER), "writes-a-job-into-the-window");
    writer.start();
    try {
      writer.join();
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

  }

  @Test
  @DisplayName("A job written while the next wait is being decided still runs")
  public void aJobWrittenWhileTheWaitIsBeingDecidedStillRuns() throws Exception {

    final var properties = new Camunda7EngineProperties();
    properties.setSleepUntilSomethingIsDue(true);

    final var configuration = new StandaloneProcessEngineConfiguration();
    configuration.setProcessEngineName("c7-gap");
    configuration.setJdbcUrl("jdbc:h2:mem:c7-gap;DB_CLOSE_DELAY=-1");
    configuration.setDatabaseSchemaUpdate("create-drop");
    configuration.setHistoryTimeToLive("P180D");
    configuration.setJobExecutorActivate(false);
    configuration.setBeans(Map.of("theJobRan", theJobRan));
    configuration.setJobExecutor(new ExecutorOpeningTheWindow("c7-gap", this::aJobIsWrittenByAnotherThread));
    Camunda7JobExecutorSleep.applyTo("c7-gap", configuration, properties);

    processEngine = configuration.buildProcessEngine();
    jobExecutor = ((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration())
        .getJobExecutor();
    // every wait this loop could decide on is turned up beyond the patience below, the
    // engine's own backoff included, so the test says nothing about which of them was
    // chosen: any wait at all is a job which does not run
    jobExecutor.setWaitTimeInMillis((int) (PATIENCE * 10));
    jobExecutor.setMaxWait(PATIENCE * 10);
    processEngine
        .getRepositoryService()
        .createDeployment()
        .addModelInstance("worker.bpmn", aWorkflowWithSomethingToDoNow())
        .deploy();

    // the first cycle finds an engine without a single job, so the wait it decides on is
    // the one with no end in sight - and the workflow is started inside that decision
    jobExecutor.start();

    assertTrue(
        theJobRan.ran.await(PATIENCE, TimeUnit.MILLISECONDS),
        "the wake-up of the job written inside the window was lost, so the acquisition kept sleeping");

  }

  @Test
  @DisplayName("A cycle somebody woke keeps the engine's own timing, not a sleep")
  public void aCycleSomebodyWokeKeepsTheEnginesOwnTiming() {

    final var executor = new Camunda7SleepingJobExecutor("c7-gap-strategy");
    final var strategy = new Camunda7SleepUntilSomethingIsDue("c7-gap-strategy", executor);
    final var context = new JobAcquisitionContext();

    context.setAcquisitionTime(System.currentTimeMillis());
    // one engine which was asked for jobs and handed out none, which is what a cycle
    // finding nothing looks like; an untouched context reads as an executor whose queue
    // is full instead
    context.submitAcquiredJobs("an-engine", new AcquiredJobs(3));
    context.setJobAdded(false);
    strategy.reconfigure(context);

    assertEquals(
        TimeUnit.DAYS.toMillis(365),
        strategy.getWaitTime(),
        "an engine which found nothing and was not woken has nothing to come back for");

    context.setJobAdded(true);
    strategy.reconfigure(context);

    assertTrue(
        strategy.getWaitTime() < TimeUnit.MINUTES.toMillis(1),
        "a wake-up says a job was written after the acquisition read the database, so the due date "
            + "this strategy asked for is older than the job");

  }

}
