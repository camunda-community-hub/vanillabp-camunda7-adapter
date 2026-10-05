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
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaIn;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Camunda 7 does not pass its business key - which carries the workflow
 * aggregate's ID - to a called process. VanillaBP injects the propagation while
 * preparing the BPMN, but only where it is right.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7CallActivitiesTest {

  private static final String MODULE = "loan-approval";

  private static final String BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
          id="Definitions_1" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="LoanApproval" isExecutable="true">
          <bpmn:callActivity id="assessRisk" calledElement="RiskAssessment"/>
          <bpmn:callActivity id="chargeCard" calledElement="Payment"/>
          <bpmn:callActivity id="whateverTheApplicationDecides" calledElement="${processToCall}"/>
          <bpmn:subProcess id="documents">
            <bpmn:callActivity id="collectDocuments" calledElement="DocumentCollection"/>
          </bpmn:subProcess>
          <bpmn:callActivity id="assessRiskWithOwnKey" calledElement="RiskAssessment">
            <bpmn:extensionElements>
              <camunda:in businessKey="#{execution.getVariable('otherKey')}"/>
            </bpmn:extensionElements>
          </bpmn:callActivity>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * Call activities of one process with every shape a tenant can have on them: none, this
   * module's own, and one of another module.
   */
  private static final String TENANTS = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
          id="Definitions_2" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="LoanApproval" isExecutable="true">
          <bpmn:callActivity id="callWithoutATenant" calledElement="RiskAssessment"/>
          <bpmn:callActivity id="callOurOwnTenant" calledElement="RiskAssessment" camunda:calledElementTenantId="loan-approval"/>
          <bpmn:callActivity id="callAnotherModule" calledElement="Invoicing" camunda:calledElementTenantId="billing"/>
        </bpmn:process>
        <bpmn:process id="AnotherProcess" isExecutable="true">
          <bpmn:callActivity id="notOfThisProcess" calledElement="Invoicing" camunda:calledElementTenantId="billing"/>
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * The core's answer: everything of this module works on the aggregate of
   * 'LoanApproval' except 'Payment', which has one of its own.
   */
  private static final WorkflowTaskWiring CORE = new WorkflowTaskWiring() {

    @Override
    public boolean workflowsShareTheWorkflowAggregate(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String otherBpmnProcessId) {

      return MODULE.equals(workflowModuleId) && "LoanApproval"
          .equals(bpmnProcessId) && !"Payment".equals(otherBpmnProcessId);

    }

    @Override
    public void validateTaskWiring(
        final String workflowModuleId,
        final String bpmnProcessId,
        final java.util.Collection<BpmnTaskSpec> tasks) {
    }

    @Override
    public void validateNoUnwiredWorkflowTaskMethods(
        final String workflowModuleId) {
    }

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {

      throw new UnsupportedOperationException("not part of this test");

    }

  };

  private static BpmnModelInstance preparedModel() {

    final var model = Bpmn.readModelFromStream(new ByteArrayInputStream(BPMN.getBytes(UTF_8)));
    Camunda7CallActivities.prepareCallActivities(model, MODULE, CORE);
    return model;

  }

  private static CallActivity callActivity(
      final BpmnModelInstance model,
      final String callActivityId) {

    return (CallActivity) model.getModelElementById(callActivityId);

  }

  private static List<String> businessKeysOf(
      final BpmnModelInstance model,
      final String callActivityId) {

    final var callActivity = (CallActivity) model.getModelElementById(callActivityId);
    final var extensionElements = callActivity.getExtensionElements();
    if (extensionElements == null) {
      return List.of();
    }
    return extensionElements
        .getElementsQuery()
        .filterByType(CamundaIn.class)
        .list()
        .stream()
        .map(CamundaIn::getCamundaBusinessKey)
        .filter(java.util.Objects::nonNull)
        .toList();

  }

  @Test
  @DisplayName("A process called on the same workflow aggregate is handed the business key")
  public void sameAggregateGetsTheBusinessKey() {

    final var model = preparedModel();

    assertEquals(
        List.of(Camunda7CallActivities.PARENT_BUSINESS_KEY_EXPRESSION),
        businessKeysOf(model, "assessRisk"));
    // also inside a subprocess - the call activity's process is found by walking up
    assertEquals(
        List.of(Camunda7CallActivities.PARENT_BUSINESS_KEY_EXPRESSION),
        businessKeysOf(model, "collectDocuments"));

  }

  @Test
  @DisplayName("A process with an aggregate of its own is not handed the caller's identity")
  public void otherAggregateKeepsItsOwnIdentity() {

    assertEquals(List.of(), businessKeysOf(preparedModel(), "chargeCard"));

  }

  @Test
  @DisplayName("A called element which is an expression addresses a process VanillaBP does not know")
  public void expressionsAreLeftAlone() {

    assertEquals(List.of(), businessKeysOf(preparedModel(), "whateverTheApplicationDecides"));

  }

  @Test
  @DisplayName("The note about the workflow aggregate is written where the aggregate is the same")
  public void theSharedAggregateIsNoted() {

    final var model = preparedModel();

    assertTrue(
        Camunda7CallActivities.continuesTheCallersWorkflowAggregate(callActivity(model, "assessRisk")),
        "a process called on the same aggregate continues the caller's business case");
    assertTrue(
        Camunda7CallActivities.continuesTheCallersWorkflowAggregate(callActivity(model, "collectDocuments")),
        "also inside a subprocess");
    assertTrue(
        Camunda7CallActivities.continuesTheCallersWorkflowAggregate(callActivity(model, "assessRiskWithOwnKey")),
        "a business key the application modelled itself says nothing about the aggregate");

  }

  @Test
  @DisplayName("A call activity nobody could be asked about carries no note")
  public void whatCannotBeAnsweredIsNotNoted() {

    final var model = preparedModel();

    assertFalse(
        Camunda7CallActivities.continuesTheCallersWorkflowAggregate(callActivity(model, "chargeCard")),
        "a process with an aggregate of its own");
    assertFalse(
        Camunda7CallActivities
            .continuesTheCallersWorkflowAggregate(callActivity(model, "whateverTheApplicationDecides")),
        "a called element which is an expression names a process nobody knows before it runs");

  }

  @Test
  @DisplayName("A model prepared twice carries the note once")
  public void preparingTwiceNotesOnce() {

    final var model = preparedModel();
    Camunda7CallActivities.prepareCallActivities(model, MODULE, CORE);

    assertEquals(
        1,
        callActivity(model, "assessRisk")
            .getExtensionElements()
            .getElementsQuery()
            .filterByType(CamundaProperties.class)
            .list()
            .stream()
            .mapToLong(properties -> properties.getCamundaProperties().size())
            .sum(),
        "the note is written where it is missing, not once per preparation");

  }

  @Test
  @DisplayName("A call activity naming another tenant is the one which leaves the workflow module")
  public void anotherTenantLeavesTheWorkflowModule() {

    final var model = Bpmn
        .readModelFromStream(new ByteArrayInputStream(TENANTS.getBytes(UTF_8)));

    assertEquals(
        java.util.Map.of("callAnotherModule", "billing"),
        Camunda7CallActivities.callActivitiesLeavingTheWorkflowModule(model, "LoanApproval", MODULE),
        "only camunda:calledElementTenantId can address a process of another workflow module: "
            + "the engine resolves every other called element in the tenant of the calling "
            + "instance, which is this module");

  }

  @Test
  @DisplayName("A workflow module using no tenant hears about every tenant a call activity names")
  public void withoutATenantOfItsOwnEveryNamedTenantIsForeign() {

    final var model = Bpmn
        .readModelFromStream(new ByteArrayInputStream(TENANTS.getBytes(UTF_8)));

    assertEquals(
        java.util.Map.of("callAnotherModule", "billing", "callOurOwnTenant", MODULE),
        Camunda7CallActivities.callActivitiesLeavingTheWorkflowModule(model, "LoanApproval", null),
        "under 'use-prefix' this module is deployed to no tenant, so a call activity naming "
            + "one leaves it whichever one it names");

  }

  @Test
  @DisplayName("What the application modelled wins")
  public void modelledBusinessKeyIsKept() {

    final var businessKeys = businessKeysOf(preparedModel(), "assessRiskWithOwnKey");

    assertEquals(1, businessKeys.size(), businessKeys::toString);
    assertTrue(
        businessKeys
            .getFirst()
            .contains("otherKey"),
        businessKeys::toString);

  }

}
