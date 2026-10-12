package io.vanillabp.camunda7.engine;

import java.util.HashSet;
import java.util.Set;

import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.camunda7.deployment.Camunda7WorkflowProcessingLifecycle;

/**
 * The lifecycle of an engine's {@link JobExecutor}, with a reference count. Both
 * platform modules (Spring Boot, Quarkus) use it. The executor belongs to the whole
 * ENGINE. But the deployment pipeline reports start and stop PER workflow module. So
 * the started modules are counted. The executor starts with the first module. It stops
 * when the LAST started module stops, because a stop with the first module would
 * starve the other modules. It also stops on {@link #shutdown()}, always, before the
 * engine closes.
 */
public class Camunda7JobExecutorLifecycle implements Camunda7WorkflowProcessingLifecycle {

  private static final Logger log = LoggerFactory.getLogger(Camunda7JobExecutorLifecycle.class);

  private final String adapterId;

  private final JobExecutor jobExecutor;

  /**
   * The workflow modules whose processing was started and not yet stopped. This is the
   * reference count which decides when the engine-global job executor stops.
   */
  private final Set<String> startedWorkflowModules = new HashSet<>();

  /**
   * Counts the workflow modules which started processing. The job executor belongs to the
   * engine, and the modules come and go one by one. So it is started with the first and
   * stopped with the last.
   *
   * @param adapterId The adapter id whose engine this executor belongs to, for the log
   * @param jobExecutor The engine's job executor, which is engine-global while the
   *          workflow modules start and stop one by one
   */
  public Camunda7JobExecutorLifecycle(
      final String adapterId,
      final JobExecutor jobExecutor) {

    this.adapterId = adapterId;
    this.jobExecutor = jobExecutor;

  }

  /**
   * Whether the engine is working off jobs at the moment. A test asks it to know when a
   * timer or an asynchronous continuation has been picked up.
   *
   * @return Whether the job executor is running
   */
  public boolean isActive() {

    return jobExecutor.isActive();

  }

  @Override
  public synchronized void startWorkflowProcessing(
      final String workflowModuleId) {

    startedWorkflowModules.add(workflowModuleId);
    if (!jobExecutor.isActive()) {
      log.info("Camunda7[{}]: starting the job executor", adapterId);
      jobExecutor.start();
    }

  }

  @Override
  public synchronized void stopWorkflowProcessing(
      final String workflowModuleId) {

    startedWorkflowModules.remove(workflowModuleId);
    if (startedWorkflowModules.isEmpty() && jobExecutor.isActive()) {
      log.info("Camunda7[{}]: stopping the job executor (last workflow module stopped)", adapterId);
      jobExecutor.shutdown();
    }

  }

  /**
   * Stops the executor in any case (graceful shutdown, before the engine closes). It
   * is safe to call more than once.
   */
  public synchronized void shutdown() {

    startedWorkflowModules.clear();
    if (jobExecutor.isActive()) {
      jobExecutor.shutdown();
    }

  }

}
