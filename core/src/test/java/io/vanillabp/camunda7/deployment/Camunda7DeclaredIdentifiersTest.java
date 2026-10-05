package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.RecordingScoping;
import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which identifiers this adapter reads out of a model, for the two questions about the
 * workflow modules of ONE application: what the models of this deployment declare, and what
 * a version the engine still holds declares.
 * <p>
 * Both are answered from a model the adapter walks anyway, so neither costs a query, and
 * both answer with the PLAIN names because the core composes the scoped forms.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7DeclaredIdentifiersTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS_ID = "LoanApproval";

  private static final String ADAPTER_ID = "c7";

  private ProcessEngine engine;

  @AfterEach
  public void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  /**
   * A model declaring one of every name the workflow module scopes, plus a task wired by
   * expression, which Camunda 7 keeps process-local.
   *
   * @param prefix What every name carries, as a model the engine handed back does
   */
  private static String bpmnCarrying(
      final String prefix) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:message id="paymentMessage" name="%1$sPaymentConfirmed" />
          <bpmn:signal id="closedSignal" name="%1$sBranchClosed" />
          <bpmn:error id="rejected" name="Rejected" errorCode="%1$sLoanRejected" />
          <bpmn:escalation id="escalated" name="Escalated" escalationCode="%1$sLoanEscalated" />
          <bpmn:process id="%2$s%3$s" isExecutable="true">
            <bpmn:startEvent id="start"><bpmn:outgoing>toCheck</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="toCheck" sourceRef="start" targetRef="check" />
            <bpmn:serviceTask id="check" camunda:expression="${checkCredit}"><bpmn:incoming>toCheck</bpmn:incoming><bpmn:outgoing>toEnd</bpmn:outgoing></bpmn:serviceTask>
            <bpmn:sequenceFlow id="toEnd" sourceRef="check" targetRef="end" />
            <bpmn:endEvent id="end"><bpmn:incoming>toEnd</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(prefix, prefix, PROCESS_ID);

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
   * Deploys one workflow module into an engine of its own, the way the core does.
   *
   * @param scoping What the adapter reports to
   */
  private void deployOurModule(
      final RecordingScoping scoping) {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration
        .setJdbcUrl("jdbc:h2:mem:declared-identifiers-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    // the engine refuses to parse a model without one, and a test needs no cleanup policy
    configuration.setHistoryTimeToLive("P30D");
    engine = configuration.buildProcessEngine();

    final var service = new Camunda7DeploymentService(
        ADAPTER_ID, engine.getRepositoryService(), mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder().scoping(scoping).build(), new Camunda7TaskRegistry());
    service.setRuntimeService(engine.getRuntimeService());
    final var model = Bpmn
        .readModelFromStream(new ByteArrayInputStream(bpmnCarrying("").getBytes(StandardCharsets.UTF_8)));
    var context = service.prepareBpmn(MODULE, null, "loan-approval.bpmn", PROCESS_ID, model);
    context = service
        .readDmn(
            MODULE,
            context,
            "credit-rating.dmn",
            new ByteArrayInputStream(A_DECISION.getBytes(StandardCharsets.UTF_8)));
    service.deployResources(MODULE, context);

  }

  @Test
  @DisplayName("The names a module's models declare are reported plain, and a task definition is not among them")
  public void theNamesTheModelsDeclareAreReported() {

    final var scoping = new RecordingScoping(NameClashAvoidance.NONE);

    deployOurModule(scoping);

    assertEquals(
        List.of("PaymentConfirmed"),
        scoping.declaredOfKind(ScopedIdentifierKind.MESSAGE_NAME),
        "a message name is resolved by the engine across process definitions, so it is one of the "
            + "names two workflow modules can end up sharing");
    assertEquals(List.of("BranchClosed"), scoping.declaredOfKind(ScopedIdentifierKind.SIGNAL_NAME));
    assertEquals(List.of("LoanRejected"), scoping.declaredOfKind(ScopedIdentifierKind.ERROR_CODE));
    assertEquals(List.of("LoanEscalated"), scoping.declaredOfKind(ScopedIdentifierKind.ESCALATION_CODE));
    assertEquals(
        List.of("creditRating"),
        scoping.declaredOfKind(ScopedIdentifierKind.DMN_DECISION_ID),
        "a decision id is scoped by the workflow module as well, and this adapter knows the ids "
            + "while it reads the file");
    assertEquals(
        List.of(),
        scoping.declaredOfKind(ScopedIdentifierKind.TASK_DEFINITION),
        "a task definition is process-local on Camunda 7, so there is nothing to clash with and "
            + "nothing to report");

  }

  @Test
  @DisplayName("Under 'use-prefix' the declared names are still the ones the application modelled")
  public void underUsePrefixTheDeclaredNamesStayPlain() {

    final var scoping = new RecordingScoping(NameClashAvoidance.USE_PREFIX);

    deployOurModule(scoping);

    assertEquals(
        List.of("PaymentConfirmed"),
        scoping.declaredOfKind(ScopedIdentifierKind.MESSAGE_NAME),
        "the names are read before the model is scoped, and the core composes the scoped form "
            + "itself");

  }

  /**
   * What one version the engine still holds declares, asked through the version catalog the
   * core uses.
   *
   * @param scoping The mode that version was deployed in
   * @param model The BPMN the engine hands back for it
   */
  private static java.util.Collection<ModelIdentifier> identifiersOfHeldVersion(
      final RecordingScoping scoping,
      final String model) {

    final var service = new Camunda7DeploymentService(
        ADAPTER_ID, AnEngineHolding.theseModels(PROCESS_ID, Map.of("3", model)), mock(
            Camunda7WorkflowProcessingLifecycle.class), TestCollaborators.builder().scoping(scoping)
                .build(), new Camunda7TaskRegistry());
    return service
        .processVersionCatalogOf(MODULE, PROCESS_ID)
        .identifiersOfVersion(MODULE, PROCESS_ID, "3");

  }

  @Test
  @DisplayName("A version the engine still holds answers with the names its model declares")
  public void aHeldVersionAnswersWithItsNames() {

    final var declared = identifiersOfHeldVersion(
        new RecordingScoping(NameClashAvoidance.NONE),
        bpmnCarrying(""));

    assertTrue(
        declared.contains(new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, "PaymentConfirmed", null)),
        () -> "a model deployed years ago is the only place such a name still lives: "
            + declared);
    assertTrue(
        declared.contains(new ModelIdentifier(ScopedIdentifierKind.SIGNAL_NAME, "BranchClosed", null)),
        () -> declared.toString());
    assertTrue(
        declared.stream().noneMatch(identifier -> identifier.kind() == ScopedIdentifierKind.TASK_DEFINITION),
        () -> "a task definition is not a question on this engine, in a held version either: "
            + declared);

  }

  @Test
  @DisplayName("A version deployed with prefixes answers with the plain names")
  public void aHeldVersionAnswersWithoutItsPrefix() {

    final var declared = identifiersOfHeldVersion(
        new RecordingScoping(NameClashAvoidance.USE_PREFIX),
        bpmnCarrying(MODULE
            + "__"));

    assertTrue(
        declared.contains(new ModelIdentifier(ScopedIdentifierKind.MESSAGE_NAME, "PaymentConfirmed", null)),
        () -> "the engine holds the model as it was deployed, so the prefix comes off here: "
            + declared);

  }

  @Test
  @DisplayName("A version the engine does not hold any more declares nothing")
  public void aVersionTheEngineDroppedDeclaresNothing() {

    final var service = new Camunda7DeploymentService(
        ADAPTER_ID, AnEngineHolding.theseModels(PROCESS_ID, Map.of()), mock(
            Camunda7WorkflowProcessingLifecycle.class), TestCollaborators.builder()
                .scoping(new RecordingScoping(NameClashAvoidance.NONE)).build(), new Camunda7TaskRegistry());

    assertEquals(
        List.of(),
        service.processVersionCatalogOf(MODULE, PROCESS_ID).identifiersOfVersion(MODULE, PROCESS_ID, "3"),
        "a deployment deleted between the version query and this call is nothing to report, and "
            + "nothing to warn about either");

  }

}
