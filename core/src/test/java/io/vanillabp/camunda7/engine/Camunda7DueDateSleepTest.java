package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.camunda.bpm.engine.impl.jobexecutor.SequentialJobAcquisitionRunnable;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an idle engine costs its database, measured. Every connection the engine takes is
 * counted, so the claim this feature lives by can be stated as a number rather than as a
 * description of a sleep: while nothing is due, the count does not move.
 * <p>
 * The engine here is a plain standalone one on H2, built twice per comparison: once with
 * the acquisition which sleeps until something is due, once exactly as the engine ships.
 * The wait times are turned down to 100 ms so the difference between sleeping and polling
 * is visible in seconds rather than in minutes; the adapter leaves them at the engine's
 * defaults.
 * <p>
 * The platform halves prove the wiring instead, each on its own datasource counter
 * ({@code Camunda7SleepingEngineIT} on Spring Boot and {@code Camunda7SleepingEngineTest} on
 * Quarkus), because a correct strategy says nothing about a platform ever installing it.
 * <p>
 * Where a measurement begins is read from the engine and not from the clock: a cycle which
 * found nothing to do, with the acquisition thread parked afterwards. Before both of those
 * the engine is backing off after work rather than sleeping, and a measurement which starts
 * there measures the backoff. Waiting for a fixed number of polling intervals did that, and
 * it turned a busy build machine into a red test.
 * <p>
 * Where a measurement ends is read from the engine as well, and for a reason of the same
 * kind: a listener on the workflow says that the job ran. Asking the engine in a loop
 * instead would answer the question and spoil the answer, because every committed engine
 * command wakes the acquisition, and each of those wake-ups is a cycle in the count being
 * taken. Twenty questions a second turned eleven connections into sixty-four.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7DueDateSleepTest {

  /**
   * How long the engine waits between two cycles while it polls. Small enough that a
   * polling engine and a sleeping one are told apart within a second or two.
   */
  private static final int POLLING_INTERVAL = 100;

  /**
   * The window the measurements watch. Long enough for a polling engine to come back
   * several times, short enough to keep this test class quick.
   */
  private static final long QUIET_WINDOW = 2000;

  /**
   * How long the timer of the due date test runs. Further out than the wait time of the
   * executor, so the hint the engine gives its own executor does not cover it and the due
   * date has to.
   */
  private static final Duration TIMER = Duration.ofSeconds(2);

  /**
   * How long a test waits for something which should have happened before it says that it
   * never will. Three times the longest thing any test here waits for, which is the timer,
   * and never less than twenty seconds, because a machine carrying other builds leaves this
   * JVM without a turn for seconds at a time and such a pause does not get smaller when the
   * timer does. Nothing is measured against this number, so it is spent only when a test
   * fails; a test which is right stops waiting the moment the thing has happened.
   */
  private static final long PATIENCE = Math.max(20000, TIMER.multipliedBy(3).toMillis());

  /**
   * How many cycles the due date test allows the acquisition: the one woken by the commit
   * which wrote the timer, the one woken by the question this test asks while the timer
   * runs, the one at the due date, and one more for the job it found there.
   */
  private static final int CYCLES_A_DUE_DATE_COSTS = 4;

  /**
   * What one of those cycles may cost: the query for due jobs, the job it locks, the job it
   * runs, and a spare for a step this test does not know about.
   */
  private static final int CONNECTIONS_PER_CYCLE = 4;

  /**
   * When the acquisition counts as polling rather than as waiting for the due date. A
   * polling engine comes back every {@link #POLLING_INTERVAL} instead, which over this
   * timer is twenty cycles where four are allowed here.
   */
  private static final int TOO_MANY_CONNECTIONS = CYCLES_A_DUE_DATE_COSTS * CONNECTIONS_PER_CYCLE;

  private static final String SLEEPER = "ASleeper";

  private static final String WORKER = "AWorker";

  private ProcessEngine processEngine;

  private JobExecutor jobExecutor;

  private CountingConnections connections;

  /**
   * Counted down when the workflow which waits in a timer has reached its end. The engine
   * itself says so, from a listener on the end event, which is the last thing the timer job
   * does before it commits.
   */
  private final CountDownLatch theTimerHasFired = new CountDownLatch(1);

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
   * An H2 datasource which counts every connection the engine takes. A connection is what
   * an engine command needs before it can say a word to the database, so a count which
   * does not move is a database which was not spoken to.
   */
  private static final class CountingConnections implements DataSource {

    private final JdbcDataSource h2 = new JdbcDataSource();

    private final AtomicInteger taken = new AtomicInteger();

    void setURL(
        final String url) {

      h2.setURL(url);

    }

    int count() {

      return taken.get();

    }

    @Override
    public Connection getConnection() throws SQLException {

      taken.incrementAndGet();
      return h2.getConnection();

    }

    @Override
    public Connection getConnection(
        final String username,
        final String password) throws SQLException {

      taken.incrementAndGet();
      return h2.getConnection(username, password);

    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {

      return h2.getLogWriter();

    }

    @Override
    public void setLogWriter(
        final PrintWriter out) throws SQLException {

      h2.setLogWriter(out);

    }

    @Override
    public void setLoginTimeout(
        final int seconds) throws SQLException {

      h2.setLoginTimeout(seconds);

    }

    @Override
    public int getLoginTimeout() throws SQLException {

      return h2.getLoginTimeout();

    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {

      return h2.getParentLogger();

    }

    @Override
    public <T> T unwrap(
        final Class<T> iface) throws SQLException {

      return h2.unwrap(iface);

    }

    @Override
    public boolean isWrapperFor(
        final Class<?> iface) throws SQLException {

      return h2.isWrapperFor(iface);

    }

  }

  /**
   * A workflow which waits in a timer for as long as the variable <code>duration</code>
   * says, plus one which has a job to do right away.
   */
  private static BpmnModelInstance aWorkflowWaitingInATimer() {

    return Bpmn
        .createExecutableProcess(SLEEPER)
        .startEvent()
        .intermediateCatchEvent()
        .timerWithDuration("${duration}")
        .endEvent()
        .camundaExecutionListenerDelegateExpression("end", "${theTimerHasFired}")
        .done();

  }

  private static BpmnModelInstance aWorkflowWithSomethingToDoNow() {

    return Bpmn
        .createExecutableProcess(WORKER)
        .startEvent()
        .serviceTask()
        .camundaAsyncBefore()
        .camundaExpression("${true}")
        .endEvent()
        .done();

  }

  /**
   * Builds an engine on a counted H2 database and deploys both models.
   *
   * @param name The database and engine name, one per test
   * @param sleeps Whether the acquisition sleeps until something is due
   */
  private void anEngine(
      final String name,
      final boolean sleeps) {

    connections = new CountingConnections();
    connections.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name));

    final var properties = new Camunda7EngineProperties();
    properties.setSleepUntilSomethingIsDue(sleeps);

    final var configuration = new StandaloneProcessEngineConfiguration();
    configuration.setProcessEngineName(name);
    configuration
        .setBeans(
            Map
                .of(
                    "theTimerHasFired",
                    (ExecutionListener) execution -> theTimerHasFired.countDown()));
    configuration.setDataSource(connections);
    configuration.setDatabaseSchemaUpdate("create-drop");
    configuration.setHistoryTimeToLive("P180D");
    configuration.setJobExecutorActivate(false);
    if (sleeps) {
      configuration.setJobExecutor(new Camunda7SleepingJobExecutor(name));
    }
    Camunda7JobExecutorSleep.applyTo(name, configuration, properties);

    processEngine = configuration.buildProcessEngine();
    jobExecutor = ((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration())
        .getJobExecutor();
    jobExecutor.setWaitTimeInMillis(POLLING_INTERVAL);
    jobExecutor.setMaxWait(POLLING_INTERVAL);

    processEngine
        .getRepositoryService()
        .createDeployment()
        .addModelInstance("sleeper.bpmn", aWorkflowWaitingInATimer())
        .addModelInstance("worker.bpmn", aWorkflowWithSomethingToDoNow())
        .deploy();

  }

  private void startTheTimerOf(
      final Duration duration) {

    processEngine
        .getRuntimeService()
        .startProcessInstanceByKey(SLEEPER, Variables.putValue("duration", duration.toString()));

  }

  private long runningWorkflows() {

    return processEngine
        .getRuntimeService()
        .createProcessInstanceQuery()
        .count();

  }

  /**
   * Waits until no workflow is running any more, and says how long that took.
   */
  private long waitForEveryWorkflowToEnd() throws InterruptedException {

    final var started = System.currentTimeMillis();
    final var deadline = started + PATIENCE;
    while (runningWorkflows() > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "a workflow was still running after %d ms".formatted(Long.valueOf(PATIENCE)));
      Thread.sleep(50);
    }
    return System.currentTimeMillis() - started;

  }

  /**
   * When the acquisition last started a cycle. The loop writes it into the context it keeps,
   * so a cycle which ran while nobody was looking is still visible afterwards.
   */
  private long lastCycleAt() {

    return ((SequentialJobAcquisitionRunnable) jobExecutor.getAcquireJobsRunnable())
        .getAcquisitionContext()
        .getAcquisitionTime();

  }

  /**
   * Starts the executor and waits until the acquisition is asleep, which takes two things
   * being true at once: its last cycle found nothing to do, and its thread is parked.
   * <p>
   * A cycle which found nothing is the one case whose wait the due date decides; a cycle
   * which found work backs off instead. And the cycle which found nothing is still running
   * for a moment after it says so, so the parked thread is what says the cycle is over and
   * its connection given back. Starting a measurement before both were true measured the
   * backoff and called it a sleep. Waiting for a fixed number of polling intervals did
   * exactly that, and it made these measurements red on a machine busy with something else.
   * <p>
   * Nothing beyond that is waited for, and a wake-up is deliberately not waited for. The
   * test's own questions of the engine are engine commands, and a committed engine command
   * asks the acquisition to wake up, so an earlier version of this test sent such a wake-up
   * itself and waited for the cycle it should cause. That timed out in four runs out of five
   * on a loaded machine (Camunda 7.24.0, 2026-09-14), because a wake-up arriving while the
   * next wait was being decided was dropped. That window is closed
   * ({@code Camunda7WakeupInTheGapTest}), and waiting for the parked thread is still the
   * cheaper question: it is about this loop and not about who woke it.
   * <p>
   * The engine which does not sleep has no thread to ask, so there the cycle is all there
   * is, which is enough: what that measurement asserts is that the engine keeps coming back.
   *
   * @return When the cycle which found nothing started
   */
  private long waitUntilTheEngineIsAsleep() throws InterruptedException {

    jobExecutor.start();
    final var deadline = System.currentTimeMillis() + PATIENCE;
    for (;;) {
      final var context = ((SequentialJobAcquisitionRunnable) jobExecutor.getAcquireJobsRunnable())
          .getAcquisitionContext();
      final var foundNothing = context
          .areAllEnginesIdle() && !context.hasJobAcquisitionLockFailureOccurred() && (context
              .getAcquisitionException() == null) && (context.getAcquisitionTime() > 0);
      if (foundNothing && theAcquisitionThreadIsParked()) {
        return context.getAcquisitionTime();
      }
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the acquisition never ran a cycle which found nothing to do");
      Thread.sleep(20);
    }

  }

  /**
   * Whether the thread running the acquisition cycles is waiting rather than working. The
   * adapter answers which thread that is - {@code Camunda7WakeupAfterCommit} asks it about
   * every committing thread - so the threads of this JVM are held against that answer
   * instead of against a name somebody guessed.
   *
   * @return Whether it is parked, and <code>true</code> for an executor which does not
   *         sleep, because there is no sleep to wait for
   */
  private boolean theAcquisitionThreadIsParked() {

    if (!(jobExecutor.getAcquireJobsRunnable() instanceof Camunda7SleepingAcquisition acquisition)) {
      return true;
    }
    return Thread
        .getAllStackTraces()
        .keySet()
        .stream()
        .filter(acquisition::runsTheAcquisition)
        .anyMatch(
            thread -> (thread.getState() == Thread.State.WAITING) || (thread.getState() == Thread.State.TIMED_WAITING));

  }

  /**
   * Lets the acquisition fall asleep, then reports how many connections it takes while
   * nothing is due.
   */
  private int connectionsTakenWhileNothingIsDue() throws InterruptedException {

    waitUntilTheEngineIsAsleep();
    final var before = connections.count();
    Thread.sleep(QUIET_WINDOW);
    return connections.count() - before;

  }

  @Test
  @DisplayName("an engine with nothing due does not talk to its database at all")
  public void nothingIsAskedOfTheDatabaseWhileNothingIsDue() throws Exception {

    anEngine("c7-sleep-quiet", true);

    final var fellAsleepAt = waitUntilTheEngineIsAsleep();
    final var before = connections.count();
    Thread.sleep(QUIET_WINDOW);

    assertEquals(
        fellAsleepAt,
        lastCycleAt(),
        "the acquisition ran another cycle while nothing was due, so it did not sleep");
    assertEquals(
        0,
        connections.count() - before,
        "a sleeping acquisition must not take a single connection while nothing is due");

  }

  @Test
  @DisplayName("an engine which does not sleep keeps asking, which is today's behaviour")
  public void anEngineWhichDoesNotSleepKeepsAsking() throws Exception {

    anEngine("c7-sleep-off", false);

    assertTrue(
        connectionsTakenWhileNothingIsDue() > 1,
        "without the property the engine polls, which is what this feature is measured against");

  }

  @Test
  @DisplayName("a job due later runs at its due date, and nothing is asked until then")
  public void aJobDueLaterRunsAtItsDueDate() throws Exception {

    anEngine("c7-sleep-duedate", true);
    waitUntilTheEngineIsAsleep();

    // the engine hints its own executor only for a job due inside the executor's wait
    // time, so a timer further out is the case this feature has to cover itself
    final var before = connections.count();
    final var dueAt = System.currentTimeMillis() + TIMER.toMillis();
    startTheTimerOf(TIMER);

    // halfway through the timer the workflow still has to be waiting. Asking costs a
    // connection and wakes the acquisition, which is why it is asked once and not in a
    // loop, and a machine which needed the whole timer to get here is not asked at all
    Thread.sleep(TIMER.toMillis() / 2);
    if (System.currentTimeMillis() < dueAt) {
      assertEquals(
          1,
          runningWorkflows(),
          "the timer is not due yet, so the workflow has to be waiting");
    }

    // and here the test waits for the thing it is about: the job ran. The engine says so
    // itself, so the wait ends the moment it happens and asks the database nothing while
    // it lasts. Waiting a fixed span instead paid for the slowest machine on every run and
    // still left the job no more room than that span happened to hold
    final var itRan = theTimerHasFired.await(PATIENCE, TimeUnit.MILLISECONDS);
    final var spent = connections.count() - before;

    assertTrue(
        itRan,
        "the timer was due %d ms ago and its job has not run"
            .formatted(Long.valueOf(System.currentTimeMillis() - dueAt)));
    assertTrue(
        spent < TOO_MANY_CONNECTIONS,
        "the acquisition took %d connections where it should have waited for the due date"
            .formatted(Integer.valueOf(spent)));

    // the listener runs inside the transaction of the job, so this is what says that the
    // transaction was committed as well
    waitForEveryWorkflowToEnd();

  }

  @Test
  @DisplayName("a commit shortens a sleep which was already running")
  public void aCommitShortensTheSleep() throws Exception {

    anEngine("c7-sleep-shortened", true);
    jobExecutor.start();

    // the acquisition is now asleep for half a minute
    startTheTimerOf(Duration.ofSeconds(30));
    waitUntilTheEngineIsAsleep();

    // and this commit has to pull the wake-up forward to the nearer due date
    startTheTimerOf(Duration.ofSeconds(1));
    final var deadline = System.currentTimeMillis() + PATIENCE;
    while (runningWorkflows() > 1) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the nearer timer did not shorten the sleep of the longer one");
      Thread.sleep(50);
    }

    assertEquals(
        1,
        runningWorkflows(),
        "the workflow whose timer is due in half a minute has to be still waiting");

  }

  @Test
  @DisplayName("an engine with no job at all waits until something happens")
  public void anEngineWithoutAnyJobWaitsUntilSomethingHappens() throws Exception {

    anEngine("c7-sleep-nojob", true);

    assertEquals(
        0,
        connectionsTakenWhileNothingIsDue(),
        "an engine holding no job at all has nothing to wake up for");

    processEngine
        .getRuntimeService()
        .startProcessInstanceByKey(WORKER);
    final var waited = waitForEveryWorkflowToEnd();

    assertTrue(
        waited < PATIENCE,
        "the waiting acquisition has to be woken by the transaction which wrote the job");

  }

}
