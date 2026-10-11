package io.vanillabp.camunda7.wiring;

/**
 * The engine job which is delivering right now, asked on the thread executing it.
 * <p>
 * Camunda 7 hands a task to the application while it executes a job. That is the
 * <code>asyncBefore</code> continuation of a service-like task, or the continuation which
 * moved the token to a user task. That job names the delivery. It keeps its ID while the
 * engine lowers its retries and hands the same work out again. The next activation of the
 * element gets a job of its own. That is exactly the contract of
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getDeliveryId()}.
 * <p>
 * Where no job is running, nothing is named. An engine command which an application thread
 * drives creates and cancels tasks as well. A delivery there is covered by the caller's own
 * transaction.
 */
final class Camunda7DeliveringJob {

  private Camunda7DeliveringJob() {

  }

  /**
   * @return The ID of the job the engine is executing on the calling thread, or
   *         <code>null</code> where no job is executing
   */
  static String idOnThisThread() {

    final var jobExecution = org.camunda.bpm.engine.impl.context.Context.getJobExecutorContext();
    if (jobExecution == null) {
      return null;
    }
    final var job = jobExecution.getCurrentJob();
    return job == null
        ? null
        : job.getId();

  }

}
