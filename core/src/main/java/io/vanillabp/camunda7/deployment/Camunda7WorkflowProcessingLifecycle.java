package io.vanillabp.camunda7.deployment;

/**
 * Controls the engine's asynchronous job processing per workflow module - implemented
 * by the platform module owning the engine (Spring Boot:
 * <code>Camunda7EngineHolder</code> controlling the <code>SpringJobExecutor</code>).
 * <p>
 * The job executor is ENGINE-global while the deployment pipeline notifies start/stop
 * PER workflow module: implementations start the executor when the first module
 * starts and stop it only when the last started module stops (stopping on the first
 * module's stop would starve the remaining modules), plus unconditionally on shutdown
 * before the engine closes.
 */
public interface Camunda7WorkflowProcessingLifecycle {

  /**
   * A workflow module's processing starts (called once per module after its
   * resources were deployed).
   *
   * @param workflowModuleId The workflow module ID
   */
  void startWorkflowProcessing(
      String workflowModuleId);

  /**
   * A workflow module's processing stops (graceful shutdown, reverse start order).
   *
   * @param workflowModuleId The workflow module ID
   */
  void stopWorkflowProcessing(
      String workflowModuleId);

}
