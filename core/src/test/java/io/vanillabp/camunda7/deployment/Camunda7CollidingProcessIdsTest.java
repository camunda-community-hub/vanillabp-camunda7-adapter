package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.scoping.NameClashAvoidanceService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two workflow modules of one application which use the same BPMN process id, deployed into
 * one engine, with the core's own name-clash-avoidance support in between. The clash lives
 * between the two modules and a deployment is per module, so the refusal can only come while
 * the SECOND one deploys, with the first already in the engine.
 * <p>
 * Which of the two modules is deployed first is not the application's choice, so both orders
 * are driven. What decides whether the two ids are a clash at all is the tenant this adapter
 * would deploy them into: a name for the whole adapter puts both modules into one tenant and
 * the engine then keeps nothing apart, while the tenant named after each module is what lets
 * the same process id live twice.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7CollidingProcessIdsTest {

  private static final String ADAPTER_ID = "c7";

  private static final String LOANS = "loan-approval";

  private static final String PAYMENTS = "payment-handling";

  private static final String PROCESS_ID = "RiskAssessment";

  private ProcessEngine engine;

  @AfterEach
  public void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  private static final String BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="%s" isExecutable="true">
          <bpmn:startEvent id="start"><bpmn:outgoing>toAssess</bpmn:outgoing></bpmn:startEvent>
          <bpmn:sequenceFlow id="toAssess" sourceRef="start" targetRef="assess" />
          <bpmn:serviceTask id="assess" camunda:expression="${assessRisk}"><bpmn:incoming>toAssess</bpmn:incoming><bpmn:outgoing>toEnd</bpmn:outgoing></bpmn:serviceTask>
          <bpmn:sequenceFlow id="toEnd" sourceRef="assess" targetRef="end" />
          <bpmn:endEvent id="end"><bpmn:incoming>toEnd</bpmn:incoming></bpmn:endEvent>
        </bpmn:process>
      </bpmn:definitions>
      """
      .formatted(PROCESS_ID);

  /**
   * The configuration of an application with two workflow modules and one Camunda 7 adapter.
   * No mode is configured anywhere, so the adapter's own default applies, which is the tenant
   * per workflow module.
   */
  private static MigrationAdapterProperties twoWorkflowModules() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(java.util.Map.of(ADAPTER_ID, AdapterConfigProperties.ofType("camunda7")))
        .prioritizedAdapters(java.util.List.of(ADAPTER_ID))
        .workflowModules(
            java.util.Map
                .of(
                    LOANS,
                    new io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties(),
                    PAYMENTS,
                    new io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties()))
        .build();
    properties.validateAndLink();
    return properties;

  }

  /**
   * A boot of that application: one engine, the core's support and this adapter, wired the way
   * the platform wires them.
   *
   * @param tenantOfTheAdapter The name every workflow module is deployed under, or
   *          <code>null</code> to let each module's id name its own tenant
   * @return The adapter the core deploys through
   */
  private Camunda7DeploymentService bootWith(
      final String tenantOfTheAdapter) {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration
        .setJdbcUrl("jdbc:h2:mem:colliding-process-ids-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    // the engine refuses to parse a model without one, and a test needs no cleanup policy
    configuration.setHistoryTimeToLive("P30D");
    engine = configuration.buildProcessEngine();

    final var adapters = new java.util.ArrayList<AdapterDeploymentService<?, ?>>();
    final var scoping = new NameClashAvoidanceService(twoWorkflowModules(), () -> adapters);
    final var service = new Camunda7DeploymentService(
        ADAPTER_ID, engine.getRepositoryService(), mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .scoping(scoping)
            .build(), new Camunda7TaskRegistry());
    service.setRuntimeService(engine.getRuntimeService());
    service
        .setConfiguredTenants(
            workflowModuleId -> Camunda7ConfiguredTenant
                .firstConfigured(ADAPTER_ID, workflowModuleId, null, tenantOfTheAdapter));
    adapters.add(service);
    return service;

  }

  /**
   * Deploys the one process of a workflow module, the way the core does it.
   */
  private static void deploy(
      final Camunda7DeploymentService service,
      final String workflowModuleId) {

    final var model = Bpmn
        .readModelFromStream(new ByteArrayInputStream(BPMN.getBytes(StandardCharsets.UTF_8)));
    final var context = service
        .prepareBpmn(workflowModuleId, null, "%s.bpmn".formatted(workflowModuleId), PROCESS_ID, model);
    service.deployResources(workflowModuleId, context);

  }

  /**
   * What a developer has to be able to read out of the refusal without opening anything else.
   */
  private void assertBothModulesAreNamed(
      final String message) {

    assertTrue(message.contains("'%s'".formatted(LOANS)), () -> "the first module is missing: "
        + message);
    assertTrue(message.contains("'%s'".formatted(PAYMENTS)), () -> "the second module is missing: "
        + message);
    assertTrue(message.contains("'%s'".formatted(PROCESS_ID)), () -> "the BPMN process is missing: "
        + message);
    assertTrue(message.contains("by-adapter"), () -> "the mode which produced the id is missing: "
        + message);
    assertTrue(
        message.contains("vanillabp.adapters.%s.tenant-id".formatted(ADAPTER_ID)),
        () -> "the property which gives a module its own scope is missing: "
            + message);

  }

  @Test
  @DisplayName("One tenant for the whole adapter ends the boot of the second workflow module")
  public void anAdapterWideTenantTurnsTwoModulesIntoAClash() {

    final var service = bootWith("one-for-all");

    deploy(service, LOANS);

    final var refusal = assertThrows(IllegalStateException.class, () -> deploy(service, PAYMENTS));
    assertBothModulesAreNamed(refusal.getMessage());

    assertNotNull(
        engine
            .getRepositoryService()
            .createProcessDefinitionQuery()
            .processDefinitionKey(PROCESS_ID)
            .tenantIdIn("one-for-all")
            .singleResult(),
        "the first module is in the engine when the boot ends, which is what the refusal costs");

  }

  @Test
  @DisplayName("The refusal comes whichever of the two workflow modules deploys first")
  public void theOtherOrderIsRefusedAsWell() {

    final var service = bootWith("one-for-all");

    deploy(service, PAYMENTS);

    final var refusal = assertThrows(IllegalStateException.class, () -> deploy(service, LOANS));
    assertBothModulesAreNamed(refusal.getMessage());

  }

  @Test
  @DisplayName("A tenant per workflow module lets both deploy under the same process id")
  public void theTenantPerWorkflowModuleKeepsThemApart() {

    final var service = bootWith(null);

    deploy(service, LOANS);
    deploy(service, PAYMENTS);

    assertEquals(
        2,
        engine
            .getRepositoryService()
            .createProcessDefinitionQuery()
            .processDefinitionKey(PROCESS_ID)
            .count(),
        "each workflow module has a tenant of its own, so the engine holds the process twice");
    assertEquals(
        1,
        engine
            .getRepositoryService()
            .createProcessDefinitionQuery()
            .processDefinitionKey(PROCESS_ID)
            .tenantIdIn(LOANS)
            .count(),
        "and each of the two is where its workflow module deployed it");

  }

}
