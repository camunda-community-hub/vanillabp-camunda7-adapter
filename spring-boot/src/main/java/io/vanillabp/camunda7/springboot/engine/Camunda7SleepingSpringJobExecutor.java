package io.vanillabp.camunda7.springboot.engine;

import org.camunda.bpm.engine.spring.components.jobexecutor.SpringJobExecutor;

import io.vanillabp.camunda7.engine.Camunda7SleepingAcquisition;

/**
 * The adapter's job executor on Spring Boot, with the acquisition loop which sleeps until
 * something is due. The engine creates its loop in {@code ensureInitialization()} and keeps
 * no factory for it, so replacing the loop means subclassing the executor, and this is the
 * whole subclass.
 * <p>
 * Everything else the sleep needs is platform-neutral and lives in the core's
 * {@code Camunda7JobExecutorSleep}. What keeps this class here is only the class it extends:
 * a {@code SpringJobExecutor} runs the jobs on the application's thread pool.
 */
public class Camunda7SleepingSpringJobExecutor extends SpringJobExecutor {

  private final String adapterId;

  /**
   * Builds the executor with the waiting acquisition in place of the polling one, on the
   * application's own thread pool.
   *
   * @param adapterId The adapter id whose engine this executor belongs to, for the log
   */
  public Camunda7SleepingSpringJobExecutor(
      final String adapterId) {

    this.adapterId = adapterId;

  }

  @Override
  protected void ensureInitialization() {

    super.ensureInitialization();
    acquireJobsRunnable = new Camunda7SleepingAcquisition(adapterId, this);

  }

}
