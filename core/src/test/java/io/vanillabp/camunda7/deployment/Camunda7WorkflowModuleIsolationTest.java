package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.RecordingScoping;
import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter answers the core about its own isolation: would Camunda 7 deploy two
 * given workflow modules into two different TENANTS. The core asks it where two BPMN
 * processes of one application reach the engine under one process definition key and the
 * mode leaves the key plain, because then nothing but the engine could keep them apart.
 * <p>
 * Every case here is a configuration, never a mode on its own: the tenant is resolved per
 * workflow module the way the deployment resolves it, and a name may be written for one
 * module, for every module of the adapter, or nowhere.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7WorkflowModuleIsolationTest {

  private static final String ADAPTER_ID = "c7";

  private static final String LOANS = "loan-approval";

  private static final String PAYMENTS = "payment-handling";

  /**
   * The adapter with the given modes and the given tenant names - built the way the platform
   * builds it, so the answer comes out of the same resolution the deployment uses.
   *
   * @param scoping The modes of this application
   * @param tenantPerWorkflowModule What a module's own section names, or <code>null</code>
   * @param tenantOfTheAdapter What the adapter's section names, or <code>null</code>
   */
  private static Camunda7DeploymentService adapterWith(
      final RecordingScoping scoping,
      final java.util.Map<String, String> tenantPerWorkflowModule,
      final String tenantOfTheAdapter) {

    final var service = new Camunda7DeploymentService(
        ADAPTER_ID, null, null, TestCollaborators
            .builder()
            .scoping(scoping)
            .build(), null);
    service
        .setConfiguredTenants(
            workflowModuleId -> Camunda7ConfiguredTenant
                .firstConfigured(
                    ADAPTER_ID,
                    workflowModuleId,
                    tenantPerWorkflowModule.get(workflowModuleId),
                    tenantOfTheAdapter));
    return service;

  }

  private static Camunda7DeploymentService adapterIn(
      final NameClashAvoidance mode) {

    return adapterWith(new RecordingScoping(mode), java.util.Map.of(), null);

  }

  @Test
  @DisplayName("Without a configured name every workflow module gets a tenant of its own")
  public void theModuleIdIsTheTenantAndSeparates() {

    assertTrue(
        adapterIn(NameClashAvoidance.BY_ADAPTER)
            .ownIsolationSeparatesWorkflowModules(LOANS, PAYMENTS),
        "the tenant is named after the workflow module, so the two modules are in two tenants");

  }

  @Test
  @DisplayName("One tenant name for the whole adapter puts every workflow module into it")
  public void anAdapterWideTenantSeparatesNothing() {

    assertFalse(
        adapterWith(new RecordingScoping(NameClashAvoidance.BY_ADAPTER), java.util.Map.of(), "one-for-all")
            .ownIsolationSeparatesWorkflowModules(LOANS, PAYMENTS),
        "both modules are deployed into 'one-for-all', so the engine keeps nothing apart");

  }

  @Test
  @DisplayName("A tenant named for one workflow module only separates it from the rest")
  public void aTenantOfOneModuleSeparatesIt() {

    assertTrue(
        adapterWith(
            new RecordingScoping(NameClashAvoidance.BY_ADAPTER),
            java.util.Map.of(LOANS, "loans-tenant"),
            "one-for-all")
            .ownIsolationSeparatesWorkflowModules(LOANS, PAYMENTS),
        "the module's own name wins over the adapter's, so the two tenants differ");

    assertFalse(
        adapterWith(
            new RecordingScoping(NameClashAvoidance.BY_ADAPTER),
            java.util.Map.of(LOANS, "one-for-all"),
            "one-for-all")
            .ownIsolationSeparatesWorkflowModules(LOANS, PAYMENTS),
        "a module repeating the adapter's name lands in the very same tenant");

  }

  @Test
  @DisplayName("Two workflow modules which reach the engine without a tenant are not separated")
  public void noTenantIsAScopeTwoModulesShare() {

    assertFalse(
        adapterIn(NameClashAvoidance.USE_PREFIX)
            .ownIsolationSeparatesWorkflowModules(LOANS, PAYMENTS),
        "prefixing deploys without a tenant, so the engine itself separates nothing");

    assertFalse(
        adapterIn(NameClashAvoidance.NONE)
            .ownIsolationSeparatesWorkflowModules(LOANS, PAYMENTS),
        "'none' deploys without a tenant as well");

    assertFalse(
        adapterWith(new RecordingScoping(NameClashAvoidance.NONE), java.util.Map.of(LOANS, "loans-tenant"), null)
            .ownIsolationSeparatesWorkflowModules(LOANS, PAYMENTS),
        "a name the mode drops is not a scope: neither module reaches the engine in a tenant");

  }

  @Test
  @DisplayName("A tenant-free workflow module is separated from a tenanted one")
  public void aTenantFreeModuleIsSeparatedFromATenantedOne() {

    final var mixed = new RecordingScoping(NameClashAvoidance.BY_ADAPTER)
        .withWorkflowModuleIn(PAYMENTS, NameClashAvoidance.USE_PREFIX);

    assertTrue(
        adapterWith(mixed, java.util.Map.of(), null)
            .ownIsolationSeparatesWorkflowModules(LOANS, PAYMENTS),
        "one module is deployed into a tenant and the other without one, which are two scopes");

    assertTrue(
        adapterWith(mixed, java.util.Map.of(), "one-for-all")
            .ownIsolationSeparatesWorkflowModules(PAYMENTS, LOANS),
        "which of the two is asked about first changes nothing");

  }

  @Test
  @DisplayName("A workflow module is not separated from itself")
  public void oneModuleAgainstItselfIsNotSeparated() {

    assertFalse(
        adapterIn(NameClashAvoidance.BY_ADAPTER)
            .ownIsolationSeparatesWorkflowModules(LOANS, LOANS),
        "one workflow module is one tenant, whatever the question looks like");

  }

}
