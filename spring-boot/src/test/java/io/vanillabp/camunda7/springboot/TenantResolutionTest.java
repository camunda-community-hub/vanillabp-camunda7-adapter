package io.vanillabp.camunda7.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The Camunda tenant a workflow module is deployed into is resolved per workflow module,
 * with a fallback to the adapter. Two modules sharing a BPMN process id depend on it: one
 * name for the whole adapter puts both into one tenant, and a name for one of them is how
 * the application gives that module a scope of its own.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TenantResolutionTest {

  private static final String ADAPTER = "c7";

  private static final String MODULE = "loan-approval";

  private VanillaBpCamunda7Properties properties(
      final String adapterTenant,
      final String moduleTenant) {

    final var properties = new VanillaBpCamunda7Properties();
    final var engineProperties = new Camunda7EngineProperties();
    engineProperties.setTenantId(adapterTenant);
    properties.setAdapters(Map.of(ADAPTER, engineProperties));

    final var module = new VanillaBpCamunda7Properties.Camunda7WorkflowModuleProperties();
    if (moduleTenant != null) {
      final var scoped = new VanillaBpCamunda7Properties.Camunda7ModuleScopedProperties();
      scoped.setTenantId(moduleTenant);
      module.setAdapters(Map.of(ADAPTER, scoped));
    }
    properties.setWorkflowModules(Map.of(MODULE, module));
    return properties;

  }

  @Test
  @DisplayName("The workflow module's name wins over the adapter's")
  public void mostSpecificWins() {

    assertEquals(
        "loans-tenant",
        properties("one-for-all", "loans-tenant")
            .configuredTenantFor(ADAPTER, MODULE)
            .tenantId());

    assertEquals(
        "one-for-all",
        properties("one-for-all", null)
            .configuredTenantFor(ADAPTER, MODULE)
            .tenantId());

    // another workflow module of the same application falls back to the adapter's name
    assertEquals(
        "one-for-all",
        properties("one-for-all", "loans-tenant")
            .configuredTenantFor(ADAPTER, "payment-handling")
            .tenantId());

  }

  @Test
  @DisplayName("The key the name was read from travels with it")
  public void theKeyOfTheLevelIsReported() {

    assertEquals(
        "vanillabp.workflow-modules.loan-approval.adapters.c7.tenant-id",
        properties("one-for-all", "loans-tenant")
            .configuredTenantFor(ADAPTER, MODULE)
            .propertyKey(),
        "a message about this name has to send the developer to the line which holds it");

    assertEquals(
        "vanillabp.adapters.c7.tenant-id",
        properties("one-for-all", null)
            .configuredTenantFor(ADAPTER, MODULE)
            .propertyKey());

  }

  @Test
  @DisplayName("Nothing configured anywhere reads as no name at all")
  public void nothingConfiguredIsNoName() {

    assertNull(
        properties(null, null).configuredTenantFor(ADAPTER, MODULE),
        "the workflow module id names the tenant then, which the adapter decides");

    assertNull(
        properties("  ", null).configuredTenantFor(ADAPTER, MODULE),
        "a blank name is not a tenant named ' '");

    assertNull(
        properties(null, null).configuredTenantFor(ADAPTER, "a-module-nothing-configures"),
        "and a module the configuration does not mention at all is answered the same way");

  }

}
