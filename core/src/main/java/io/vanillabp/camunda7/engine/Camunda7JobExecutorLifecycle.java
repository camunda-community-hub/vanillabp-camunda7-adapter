package io.vanillabp.camunda7.engine;

import java.util.HashSet;
import java.util.Set;

import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.camunda7.deployment.Camunda7WorkflowProcessingLifecycle;

/**
 * Reference-counted lifecycle of an engine's {@link JobExecutor} - shared by the
 * platform modules (Spring Boot, Quarkus): the executor is ENGINE-global while the
 * deployment pipeline notifies start/stop PER workflow module, so the started
 * modules are counted - the executor starts with the first module, stops when the
 * LAST started module stops (stopping on the first module's stop would starve the
 * remaining modules) and unconditionally on {@link #shutdown()}, before the engine
 * closes.
 */
public class Camunda7JobExecutorLifecycle implements Camunda7WorkflowProcessingLifecycle {

  private static final Logger log = LoggerFactory.getLogger(Camunda7JobExecutorLifecycle.class);

  private final String adapterId;

  private final JobExecutor jobExecutor;

  /**
   * The workflow modules whose processing was started and not yet stopped - the
   * reference count deciding when the engine-global job executor stops.
   */
  private final Set<String> startedWorkflowModules = new HashSet<>();

  /**
   * Counts the workflow modules which started processing. The job executor belongs to the
   * engine and the modules come and go one by one, so it is started with the first and
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
   * Stops the executor unconditionally (graceful shutdown, before the engine
   * closes). Safe to call more than once.
   */
  public synchronized void shutdown() {

    startedWorkflowModules.clear();
    if (jobExecutor.isActive()) {
      jobExecutor.shutdown();
    }

  }

}
