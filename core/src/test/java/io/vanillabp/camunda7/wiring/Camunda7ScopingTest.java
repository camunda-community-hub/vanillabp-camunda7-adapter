package io.vanillabp.camunda7.wiring;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.CallActivity;
import org.camunda.bpm.model.bpmn.instance.Error;
import org.camunda.bpm.model.bpmn.instance.Escalation;
import org.camunda.bpm.model.bpmn.instance.Message;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.ServiceTask;
import org.camunda.bpm.model.bpmn.instance.Signal;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.RecordingScoping;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Unit tests of {@link Camunda7Scoping}: which identifiers of a Camunda 7
 * model are rewritten in mode {@link NameClashAvoidance#USE_PREFIX} - and that task
 * definitions deliberately are not, because they are process-local in Camunda 7.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7ScopingTest {

  private static final String ADAPTER_ID = "camunda7";

  private static final String MODULE = "loan-approval";

  private static final String BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
          id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:message id="msg1" name="LoanApproved"/>
        <bpmn:signal id="sig1" name="Halt"/>
        <bpmn:escalation id="esc1" escalationCode="TooLate"/>
        <bpmn:error id="err1" name="Denied" errorCode="Denied"/>
        <bpmn:process id="RiskAssessment" isExecutable="true">
          <bpmn:startEvent id="start"/>
          <bpmn:serviceTask id="score" camunda:expression="${riskAssessment.scoreApplicant(execution)}"/>
          <bpmn:userTask id="approve" camunda:formKey="approveLoan"/>
          <bpmn:callActivity id="collectDocuments" calledElement="DocumentCollection"/>
          <bpmn:businessRuleTask id="rate" camunda:decisionRef="creditRating" camunda:resultVariable="rating"/>
          <bpmn:endEvent id="end"/>
        </bpmn:process>
        <bpmn:process id="DocumentCollection" isExecutable="true">
          <bpmn:startEvent id="start2"/>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static final String DMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/"
          id="creditRatingDefinitions" name="creditRating" namespace="http://vanillabp.io/test">
        <decision id="creditRating" name="Credit rating">
          <decisionTable id="creditRatingTable" hitPolicy="UNIQUE"/>
        </decision>
      </definitions>
      """;

  private static BpmnModelInstance model() {

    return Bpmn.readModelFromStream(new ByteArrayInputStream(BPMN.getBytes(UTF_8)));

  }

  private static List<String> processIds(
      final BpmnModelInstance model) {

    return model
        .getModelElementsByType(Process.class)
        .stream()
        .map(Process::getId)
        .sorted()
        .toList();

  }

  private static <T extends org.camunda.bpm.model.bpmn.instance.BpmnModelElementInstance> T first(
      final BpmnModelInstance model,
      final Class<T> type) {

    return model
        .getModelElementsByType(type)
        .iterator()
        .next();

  }

  @Test
  @DisplayName("USE_PREFIX rewrites everything the engine resolves across definitions")
  public void usePrefixRewritesEngineWideIdentifiers() {

    final var model = model();

    Camunda7Scoping.apply(model, MODULE, ADAPTER_ID, new RecordingScoping(NameClashAvoidance.USE_PREFIX));

    assertEquals(
        List.of("loan-approval__DocumentCollection", "loan-approval__RiskAssessment"),
        processIds(model));
    assertEquals(
        "loan-approval__DocumentCollection",
        first(model, CallActivity.class).getCalledElement());
    assertEquals(
        "loan-approval__LoanApproved",
        first(model, Message.class).getName());
    assertEquals(
        "loan-approval__Halt",
        first(model, Signal.class).getName());
    assertEquals(
        "loan-approval__TooLate",
        first(model, Escalation.class).getEscalationCode());
    assertEquals(
        "loan-approval__Denied",
        first(model, Error.class).getErrorCode());

  }

  @Test
  @DisplayName("USE_PREFIX leaves task definitions untouched: they are process-local in Camunda 7")
  public void usePrefixKeepsTaskDefinitions() {

    final var model = model();

    Camunda7Scoping.apply(model, MODULE, ADAPTER_ID, new RecordingScoping(NameClashAvoidance.USE_PREFIX));

    assertEquals(
        "${riskAssessment.scoreApplicant(execution)}",
        first(model, ServiceTask.class).getCamundaExpression());
    assertEquals(
        "approveLoan",
        first(model, UserTask.class).getCamundaFormKey());

  }

  @Test
  @DisplayName("BY_ADAPTER and NONE leave the model unchanged - the tenant respectively nothing isolates")
  public void otherModesDoNotTouchTheModel() {

    for (final var mode : List.of(NameClashAvoidance.BY_ADAPTER, NameClashAvoidance.NONE)) {

      final var model = model();

      Camunda7Scoping.apply(model, MODULE, ADAPTER_ID, new RecordingScoping(mode));

      assertEquals(List.of("DocumentCollection", "RiskAssessment"), processIds(model), "mode "
          + mode);
      assertEquals(
          "LoanApproved",
          first(model, Message.class).getName(),
          "mode "
              + mode);
      assertFalse(Camunda7Scoping.prefixes(MODULE, ADAPTER_ID, new RecordingScoping(mode)));

    }

  }

  @Test
  @DisplayName("a missing scoping support means no prefixing at all")
  public void noScopingSupportMeansNoPrefixing() {

    final var model = model();

    assertFalse(Camunda7Scoping.prefixes(MODULE, ADAPTER_ID, null));
    Camunda7Scoping.apply(model, MODULE, ADAPTER_ID, null);

    assertEquals(List.of("DocumentCollection", "RiskAssessment"), processIds(model));

  }

  @Test
  @DisplayName("a business rule task and its decision are renamed to the same string")
  public void aBusinessRuleTaskFindsItsRenamedDecision() {

    final var model = model();
    final var scoping = new RecordingScoping(NameClashAvoidance.USE_PREFIX);

    Camunda7Scoping.apply(model, MODULE, ADAPTER_ID, scoping);

    // what the model points at has to be what the DMN file is deployed under, so both
    // sides go through the same function of the core
    final var decisionRef = first(model, org.camunda.bpm.model.bpmn.instance.BusinessRuleTask.class)
        .getCamundaDecisionRef();
    final var deployedDecisionId = io.vanillabp.integration.adapter.spi.DmnDecisionIds
        .of(
            io.vanillabp.integration.adapter.spi.DmnDecisionIds
                .rewrite(
                    DMN.getBytes(UTF_8),
                    id -> scoping.scopedIdentifier(MODULE, id, ADAPTER_ID)))
        .iterator()
        .next();
    assertEquals(deployedDecisionId, decisionRef);

  }

  @Test
  @DisplayName("a decision addressed by an expression keeps the expression, prefixed as literal text")
  public void aDecisionByExpressionStaysEvaluable() {

    final var model = model();
    first(model, org.camunda.bpm.model.bpmn.instance.BusinessRuleTask.class)
        .setCamundaDecisionRef("${whichDecision}");

    Camunda7Scoping.apply(model, MODULE, ADAPTER_ID, new RecordingScoping(NameClashAvoidance.USE_PREFIX));

    // the decision this module deploys carries the prefix, so the expression has to resolve
    // to the prefixed id as well. Camunda7DecisionByExpressionTest runs that against the
    // engine, which finds nothing without the prefix
    assertEquals(
        "loan-approval__${whichDecision}",
        first(model, org.camunda.bpm.model.bpmn.instance.BusinessRuleTask.class).getCamundaDecisionRef());

  }

  @Test
  @DisplayName("a call activity addressed by an expression keeps the expression, prefixed as literal text")
  public void callActivityByExpressionStaysEvaluable() {

    final var model = model();
    first(model, CallActivity.class).setCalledElement("${nextProcess}");

    Camunda7Scoping.apply(model, MODULE, ADAPTER_ID, new RecordingScoping(NameClashAvoidance.USE_PREFIX));

    // JUEL evaluates composite expressions (literal text + '${...}'), so the engine
    // resolves the prefixed process id of whatever the expression yields
    assertEquals(
        "loan-approval__${nextProcess}",
        first(model, CallActivity.class).getCalledElement());
    assertTrue(Camunda7Scoping.prefixes(MODULE, ADAPTER_ID, new RecordingScoping(NameClashAvoidance.USE_PREFIX)));

  }

}
