package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.RecordingScoping;
import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter can tell an application about an identifier its engine ALREADY held
 * before this boot deployed anything - asked against a real engine, because the filters and
 * the deployment stamp are what the check consists of.
 * <p>
 * Every assertion reads what the adapter handed to the core, since the adapter asks and the
 * core words the warning.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7IdentifiersTheEngineHoldsTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS_ID = "LoanApproval";

  private static final String DECISION_ID = "creditRating";

  private static final String ADAPTER_ID = "c7";

  /**
   * The source this adapter writes on every deployment it makes. What tells a foreign
   * deployment from this application's own history is the deployment NAME, and the source says
   * how sure the adapter can be about a deployment carrying another name.
   */
  private static final String OUR_SOURCE = "camunda7:"
      + ADAPTER_ID;

  private ProcessEngine engine;

  @BeforeEach
  public void bootTheEngine() {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration
        .setJdbcUrl("jdbc:h2:mem:identifiers-held-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    // the engine refuses to parse a model without one, and a test needs no cleanup policy
    configuration.setHistoryTimeToLive("P30D");
    engine = configuration.buildProcessEngine();

  }

  @AfterEach
  public void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  private static String bpmnOf(
      final String bpmnProcessId) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs_%s" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:message id="paymentMessage" name="PaymentConfirmed" />
          <bpmn:signal id="closedSignal" name="BranchClosed" />
          <bpmn:error id="rejected" name="Rejected" errorCode="LoanRejected" />
          <bpmn:escalation id="escalated" name="Escalated" escalationCode="LoanEscalated" />
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:startEvent id="start"><bpmn:outgoing>toCheck</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="toCheck" sourceRef="start" targetRef="check" />
            <bpmn:serviceTask id="check" camunda:expression="${checkCredit}"><bpmn:incoming>toCheck</bpmn:incoming><bpmn:outgoing>toEnd</bpmn:outgoing></bpmn:serviceTask>
            <bpmn:sequenceFlow id="toEnd" sourceRef="check" targetRef="end" />
            <bpmn:endEvent id="end"><bpmn:incoming>toEnd</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(bpmnProcessId, bpmnProcessId);

  }

  private static final String A_DECISION = """
      <?xml version="1.0" encoding="UTF-8"?>
      <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="creditRatingDefinitions" name="Credit rating" namespace="http://vanillabp.io/c7-test">
        <decision id="creditRating" name="Credit rating">
          <decisionTable id="creditRatingTable" hitPolicy="UNIQUE">
            <input id="creditRatingInput" label="Approved">
              <inputExpression id="creditRatingInputExpression" typeRef="boolean">
                <text>approved</text>
              </inputExpression>
            </input>
            <output id="creditRatingOutput" label="Rating" name="rating" typeRef="string" />
          </decisionTable>
        </decision>
      </definitions>
      """;

  /**
   * Deploys somebody else's resources, stamped as that somebody.
   *
   * @param deploymentName The name their deployment carries
   * @param source The source their deployment carries, <code>null</code> for a deployment
   *          made by a tool which sets none
   * @param tenantId The tenant they deploy into, <code>null</code> for none
   * @param resourcesByName What they deploy
   * @return The id of their deployment
   */
  private String deployedBySomebodyElse(
      final String deploymentName,
      final String source,
      final String tenantId,
      final java.util.Map<String, String> resourcesByName) {

    var deployment = engine
        .getRepositoryService()
        .createDeployment()
        .name(deploymentName);
    if (source != null) {
      deployment = deployment.source(source);
    }
    if (tenantId != null) {
      deployment = deployment.tenantId(tenantId);
    }
    for (final var resource : resourcesByName.entrySet()) {
      deployment = deployment.addString(resource.getKey(), resource.getValue());
    }
    return deployment.deploy().getId();

  }

  /**
   * Runs the deployment pipeline of one workflow module the way the core does, up to and
   * including {@code deployResources}, which is where the engine is asked about the names.
   *
   * @param scoping What the adapter reports to
   * @param decisions Whether the module brings its decision table as well
   */
  private void deployOurModule(
      final RecordingScoping scoping,
      final boolean decisions) {

    final var service = new Camunda7DeploymentService(
        ADAPTER_ID, engine.getRepositoryService(), mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder().scoping(scoping).build(), new Camunda7TaskRegistry());
    service.setRuntimeService(engine.getRuntimeService());
    final var model = Bpmn
        .readModelFromStream(new ByteArrayInputStream(bpmnOf(PROCESS_ID).getBytes(StandardCharsets.UTF_8)));
    var context = service.prepareBpmn(MODULE, null, "loan-approval.bpmn", PROCESS_ID, model);
    if (decisions) {
      context = service
          .readDmn(
              MODULE,
              context,
              "credit-rating.dmn",
              new ByteArrayInputStream(A_DECISION.getBytes(StandardCharsets.UTF_8)));
    }
    service.deployResources(MODULE, context);

  }

  /**
   * The mode every test below runs in except the ones which say otherwise: no tenant, no
   * prefix, so the engine sees the identifiers as the application modelled them - the mode
   * this question matters most in.
   */
  private static RecordingScoping unscoped() {

    return new RecordingScoping(NameClashAvoidance.NONE);

  }

  @Test
  @DisplayName("A foreign deployment holding our BPMN process id is found and named")
  public void aForeignDeploymentHoldingOurProcessIdIsFound() {

    deployedBySomebodyElse("some-other-application", "a-modelling-tool", null, java.util.Map
        .of("their-loan-approval.bpmn", bpmnOf(PROCESS_ID)));
    final var scoping = unscoped();

    deployOurModule(scoping, false);

    assertEquals(
        java.util.List.of(PROCESS_ID),
        scoping.heldElsewhereOfKind(ScopedIdentifierKind.BPMN_PROCESS_ID),
        "the engine held that process definition key before this boot, and the core is handed the "
            + "PLAIN id so it can compose the scoped form itself");
    final var held = scoping.getHeldElsewhere().getFirst();
    assertTrue(
        held.certainlyForeign(),
        "a deployment whose source names no Camunda 7 adapter of VanillaBP cannot be an earlier "
            + "deployment of this application");
    assertTrue(
        held.heldBy().contains("some-other-application") && held.heldBy().contains("a-modelling-tool"),
        () -> "the deployment is named by what the engine knows about it: "
            + held.heldBy());
    assertNull(held.bpmnProcessId(), "only a task definition carries a process, and this is not one");

  }

  @Test
  @DisplayName("Our own earlier deployment is not reported, however many versions it left behind")
  public void ourOwnEarlierDeploymentsStaySilent() {

    // what this application deployed yesterday, carrying the stamp this adapter writes
    deployedBySomebodyElse(MODULE, OUR_SOURCE, null, java.util.Map.of("loan-approval.bpmn", bpmnOf(PROCESS_ID)));
    final var scoping = unscoped();

    deployOurModule(scoping, false);

    assertEquals(
        java.util.List.of(),
        scoping.getHeldElsewhere(),
        "a deployment named after this workflow module is its own history - reporting it would mean "
            + "a warning about every process on every boot");
    assertEquals(
        1,
        scoping.getAnswersAboutWhatIsHeld().size(),
        "the engine was asked all the same, and silence here means 'asked and found nothing'");

  }

  @Test
  @DisplayName("What version 1 of this application deployed is not reported either")
  public void whatVersionOneLeftBehindStaysSilent() {

    // version 1 deployed a workflow module under the module's id as well and wrote the
    // APPLICATION name into the source, which is why the name is what the check reads
    deployedBySomebodyElse(MODULE, "my-loan-application", null, java.util.Map
        .of("loan-approval.bpmn", bpmnOf(PROCESS_ID)));
    final var scoping = unscoped();

    deployOurModule(scoping, false);

    assertEquals(
        java.util.List.of(),
        scoping.getHeldElsewhere(),
        "an application upgrading from version 1 meets its own definitions here, and a warning "
            + "about them on every boot is exactly what this check must not be");

  }

  @Test
  @DisplayName("Another adapter id of a VanillaBP application is reported, but not as certainly foreign")
  public void anotherAdapterIdIsReportedWithoutCertainty() {

    deployedBySomebodyElse("another-module", "camunda7:the-other-engine", null, java.util.Map
        .of("loan-approval.bpmn", bpmnOf(PROCESS_ID)));
    final var scoping = unscoped();

    deployOurModule(scoping, false);

    assertEquals(
        java.util.List.of(PROCESS_ID),
        scoping.heldElsewhereOfKind(ScopedIdentifierKind.BPMN_PROCESS_ID),
        "a deployment this adapter id did not make is worth a line either way");
    assertFalse(
        scoping.getHeldElsewhere().getFirst().certainlyForeign(),
        "a second adapter id is what a migration between two engines looks like, and another "
            + "workflow module writes its own name too, so this may well be the same application");

  }

  @Test
  @DisplayName("A suspended definition still owns the id")
  public void aSuspendedDefinitionStillOwnsTheId() {

    // a deployment which carries no source at all, which is what the engine's REST API and
    // its webapps leave behind
    final var theirDeployment = deployedBySomebodyElse(
        "some-other-application",
        null,
        null,
        java.util.Map.of("their-loan-approval.bpmn", bpmnOf(PROCESS_ID)));
    engine
        .getRepositoryService()
        .suspendProcessDefinitionById(
            engine
                .getRepositoryService()
                .createProcessDefinitionQuery()
                .deploymentId(theirDeployment)
                .singleResult()
                .getId());
    final var scoping = unscoped();

    deployOurModule(scoping, false);

    assertEquals(
        java.util.List.of(PROCESS_ID),
        scoping.heldElsewhereOfKind(ScopedIdentifierKind.BPMN_PROCESS_ID),
        "somebody resuming that definition gets the id back, so it counts while it is suspended");

  }

  @Test
  @DisplayName("A foreign deployment holding our decision id is found")
  public void aForeignDeploymentHoldingOurDecisionIdIsFound() {

    deployedBySomebodyElse("some-other-application", "a-modelling-tool", null, java.util.Map
        .of("their-credit-rating.dmn", A_DECISION));
    final var scoping = unscoped();

    deployOurModule(scoping, true);

    assertEquals(
        java.util.List.of(DECISION_ID),
        scoping.heldElsewhereOfKind(ScopedIdentifierKind.DMN_DECISION_ID),
        "a decision definition key is the decision id of the DMN, and the engine answers for it");

  }

  @Test
  @DisplayName("An engine holding nothing of ours answers nothing")
  public void anEngineHoldingNothingOfOursAnswersNothing() {

    final var scoping = unscoped();

    deployOurModule(scoping, true);

    assertEquals(
        java.util.List.of(),
        scoping.getHeldElsewhere(),
        "the only definitions the engine holds are the ones this boot deployed");

  }

  @Test
  @DisplayName("Under 'by-adapter' the tenant is part of the question")
  public void theTenantIsPartOfTheQuestion() {

    // the same process id in no tenant at all, while this module is deployed into the tenant
    // named after it: a second tenant on one engine is a legitimate arrangement
    deployedBySomebodyElse("some-other-application", "a-modelling-tool", null, java.util.Map
        .of("their-loan-approval.bpmn", bpmnOf(PROCESS_ID)));
    final var outOfScope = new RecordingScoping(NameClashAvoidance.BY_ADAPTER);

    deployOurModule(outOfScope, false);

    assertEquals(
        java.util.List.of(),
        outOfScope.getHeldElsewhere(),
        "a definition outside the tenant this adapter deploys into is nobody's clash");

    // and the case which is one: somebody deploying into OUR tenant, which is what two
    // applications sharing a configured 'tenant-id' do
    deployedBySomebodyElse("some-other-application", "a-modelling-tool", MODULE, java.util.Map
        .of("their-loan-approval.bpmn", bpmnOf(PROCESS_ID)));
    final var inOurTenant = new RecordingScoping(NameClashAvoidance.BY_ADAPTER);

    deployOurModule(inOurTenant, false);

    assertEquals(
        java.util.List.of(PROCESS_ID),
        inOurTenant.heldElsewhereOfKind(ScopedIdentifierKind.BPMN_PROCESS_ID),
        "inside the tenant this adapter deploys into, the engine alone decides which side a start "
            + "reaches");

  }

  @Test
  @DisplayName("Under 'use-prefix' the engine is asked about the prefixed ids")
  public void underUsePrefixThePrefixedIdsAreTheQuestion() {

    final var scoping = new RecordingScoping(NameClashAvoidance.USE_PREFIX);
    deployedBySomebodyElse("some-other-application", "a-modelling-tool", null, java.util.Map
        .of(
            "their-loan-approval.bpmn",
            bpmnOf(MODULE
                + "__"
                + PROCESS_ID)));

    deployOurModule(scoping, false);

    assertEquals(
        java.util.List.of(PROCESS_ID),
        scoping.heldElsewhereOfKind(ScopedIdentifierKind.BPMN_PROCESS_ID),
        "the engine is asked about the prefixed id and the core is told the plain one, which is the "
            + "clash prefixing cannot protect against: somebody else using our workflow module id");

  }

  @Test
  @DisplayName("An engine which does not answer costs a debug line, never the boot")
  public void aFailingQueryDoesNotEndTheBoot() {

    final var refusingEngine = mock(RepositoryService.class, invocation -> {
      throw new org.camunda.bpm.engine.ProcessEngineException("no answer for you");
    });
    final var scoping = unscoped();

    assertDoesNotThrow(
        () -> Camunda7IdentifiersTheEngineHolds
            .reportWhatTheEngineAlreadyHolds(
                ADAPTER_ID,
                MODULE,
                java.util.Map.of(PROCESS_ID, PROCESS_ID),
                java.util.Map.of(DECISION_ID, DECISION_ID),
                null,
                refusingEngine,
                scoping),
        "a diagnostic must never fail a deployment");
    assertEquals(
        java.util.List.of(),
        scoping.getAnswersAboutWhatIsHeld(),
        "an adapter which could not ask says nothing, rather than reporting this application's own "
            + "deployments as somebody else's");

  }

}
