package io.vanillabp.camunda7.engine;

import org.camunda.bpm.engine.impl.jobexecutor.DefaultJobExecutor;

/**
 * The engine's own job executor, with the acquisition loop which sleeps until something is
 * due. The engine creates its loop in {@code ensureInitialization()} and keeps no factory
 * for it, so replacing the loop means subclassing the executor, and this is the whole
 * subclass.
 * <p>
 * This is the executor of the Quarkus module, where the engine brings its own thread pool.
 * The Spring Boot module has the same two lines over {@code SpringJobExecutor}, because
 * there the pool is the application's.
 */
public class Camunda7SleepingJobExecutor extends DefaultJobExecutor {

  private final String adapterId;

  /**
   * Builds the executor with the waiting acquisition in place of the polling one.
   *
   * @param adapterId The adapter id whose engine this executor belongs to, for the log
   */
  public Camunda7SleepingJobExecutor(
      final String adapterId) {

    this.adapterId = adapterId;

  }

  @Override
  protected void ensureInitialization() {

    super.ensureInitialization();
    acquireJobsRunnable = new Camunda7SleepingAcquisition(adapterId, this);

  }

}
