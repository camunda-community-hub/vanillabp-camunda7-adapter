package io.vanillabp.camunda7.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.RecordingScoping;
import io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What {@link Camunda7EngineFacts} answers, and that it is the adapter's own answer: the
 * tenant comes out of the method the deployment resolves its own tenant with, and whether
 * the engine joins the caller's transaction is read from the engine the adapter built rather
 * than concluded from a property.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7EngineFactsTest {

  private static final String ADAPTER_ID = "camunda7";

  private static final String MODULE = "loan-approval";

  private static Camunda7EngineFacts facts(
      final NameClashAvoidance mode,
      final Map<String, String> configuredTenants,
      final Camunda7TaskRegistry taskRegistry) {

    return new Camunda7EngineFacts(
        ADAPTER_ID, new RecordingScoping(mode), workflowModuleId -> Camunda7ConfiguredTenant
            .firstConfigured(
                ADAPTER_ID, workflowModuleId, configuredTenants.get(workflowModuleId), configuredTenants
                    .get("*")), taskRegistry);

  }

  @Test
  @DisplayName("Without a configured name the tenant is the workflow module")
  public void withoutAConfiguredNameTheTenantIsTheWorkflowModule() {

    assertEquals(
        MODULE,
        facts(NameClashAvoidance.BY_ADAPTER, Map.of(), new Camunda7TaskRegistry()).tenantIdOf(MODULE));

  }

  @Test
  @DisplayName("A name configured for the workflow module wins over the adapter's")
  public void aNameConfiguredForTheWorkflowModuleWins() {

    final var engineFacts = facts(
        NameClashAvoidance.BY_ADAPTER, Map.of(MODULE, "loans", "*", "everything"), new Camunda7TaskRegistry());

    assertEquals("loans", engineFacts.tenantIdOf(MODULE));
    assertEquals("everything", engineFacts.tenantIdOf("another-module"), "the adapter's name serves the rest");

  }

  @Test
  @DisplayName("A workflow module whose identifiers are prefixed has no tenant")
  public void aPrefixedWorkflowModuleHasNoTenant() {

    assertNull(
        facts(NameClashAvoidance.USE_PREFIX, Map.of(MODULE, "loans"), new Camunda7TaskRegistry())
            .tenantIdOf(MODULE),
        "under use-prefix the prefixed identifiers are the isolation, so the engine stores no tenant");

  }

  @Test
  @DisplayName("The adapter id is what tells two engines apart")
  public void theAdapterIdIsWhatTellsTwoEnginesApart() {

    assertEquals(ADAPTER_ID, facts(NameClashAvoidance.BY_ADAPTER, Map.of(), new Camunda7TaskRegistry()).adapterId());

  }

  @Test
  @DisplayName("An engine on the application's data source joins its transaction")
  public void anEngineOnTheApplicationsDataSourceJoinsItsTransaction() {

    final var taskRegistry = new Camunda7TaskRegistry();
    taskRegistry.setEngineRunsOnItsOwnDataSource(false);

    assertTrue(facts(NameClashAvoidance.BY_ADAPTER, Map.of(), taskRegistry).joinsTheApplicationTransaction());

  }

  @Test
  @DisplayName("An engine on a data source of its own does not join the application transaction")
  public void anEngineOnItsOwnDataSourceDoesNotJoin() {

    final var taskRegistry = new Camunda7TaskRegistry();
    taskRegistry.setEngineRunsOnItsOwnDataSource(true);

    assertFalse(
        facts(NameClashAvoidance.BY_ADAPTER, Map.of(), taskRegistry).joinsTheApplicationTransaction(),
        "the engine writes to a resource the application's persistence does not take part in");

  }

  @Test
  @DisplayName("The registry handed out is the engine's own, and the version comes from its cache")
  public void theRegistryHandedOutIsTheEnginesOwn() {

    final var taskRegistry = new Camunda7TaskRegistry();
    final var engineFacts = facts(NameClashAvoidance.BY_ADAPTER, Map.of(), taskRegistry);

    assertSame(taskRegistry, engineFacts.taskRegistry());
    assertNull(
        engineFacts.definitionOf("a-definition"),
        "without a deployment service there is no cache to read, and that is null rather than a failure");

  }

}
