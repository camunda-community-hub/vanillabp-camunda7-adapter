package io.vanillabp.camunda7.wiring;

/**
 * A Camunda TENANT name the application configured for one workflow module, together with
 * the property key it was read from. The key travels with the name for a reason. A message
 * about a tenant this adapter cannot honor has to quote the line the developer wrote. And
 * that line differs per level.
 * <p>
 * The platform modules build one of these per workflow module. They hand it to
 * {@link io.vanillabp.camunda7.deployment.Camunda7DeploymentService} and the process
 * service. What the mode then makes of the name is
 * {@link Camunda7Scoping#tenantIdFor(io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport, String, String, String)}.
 *
 * @param tenantId The configured tenant name, never blank
 * @param propertyKey The full property key the name was read from
 */
public record Camunda7ConfiguredTenant(
                                       String tenantId,
                                       String propertyKey) {

  /**
   * The tenant name configured for one workflow module of one adapter id. The most specific
   * level comes first: the workflow module, then the adapter. A blank value counts as nothing
   * configured. So an empty key does not deploy a workflow module into a tenant named "".
   *
   * <h4>Why there is no per-workflow level</h4>
   *
   * The mode is resolvable per workflow, a tenant is not. A tenant id is an attribute of the
   * DEPLOYMENT, and this adapter makes one deployment per workflow module. So two workflows of
   * one module cannot reach the engine in two tenants. A key which looks honored but is
   * ignored is worse than a key nobody may write. So the workflow level has none.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param perWorkflowModule What the workflow module's section says, or <code>null</code>
   * @param perAdapter What the adapter's section says, or <code>null</code>
   * @return The name and its key, or <code>null</code> where neither level configured one
   */
  public static Camunda7ConfiguredTenant firstConfigured(
      final String adapterId,
      final String workflowModuleId,
      final String perWorkflowModule,
      final String perAdapter) {

    if ((perWorkflowModule != null) && !perWorkflowModule.isBlank()) {
      return new Camunda7ConfiguredTenant(
          perWorkflowModule, "vanillabp.workflow-modules.%s.adapters.%s.tenant-id".formatted(workflowModuleId,
              adapterId));
    }
    if ((perAdapter != null) && !perAdapter.isBlank()) {
      return new Camunda7ConfiguredTenant(
          perAdapter, "vanillabp.adapters.%s.tenant-id".formatted(adapterId));
    }
    return null;

  }

}
