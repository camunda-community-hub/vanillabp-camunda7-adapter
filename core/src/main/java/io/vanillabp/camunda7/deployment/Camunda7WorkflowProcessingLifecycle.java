package io.vanillabp.camunda7.deployment;

/**
 * Controls the engine's asynchronous job processing per workflow module. The platform
 * module which owns the engine implements it (Spring Boot:
 * <code>Camunda7EngineHolder</code> controlling the <code>SpringJobExecutor</code>).
 * <p>
 * There is one job executor for the whole ENGINE. But the deployment pipeline reports
 * start and stop PER workflow module. So implementations start the executor when the
 * first module starts. They stop it only when the last started module stops. Stopping it
 * on the first module's stop would starve the other modules. On shutdown they always stop
 * it, before the engine closes.
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
