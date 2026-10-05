package io.vanillabp.camunda7.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.WorkflowElementType;

/**
 * Unit tests of the Camunda 7 viewer/history API's degraded paths - the ones a
 * real engine does not reach in the integration tests: an engine running with
 * history level {@code none}, a workflow unknown to this engine, a history
 * context of a FOREIGN workflow and the activity-type mapping.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7WorkflowViewerTest {

  private final RepositoryService repositoryService = mock(RepositoryService.class, RETURNS_DEEP_STUBS);

  private final HistoryService historyService = mock(HistoryService.class, RETURNS_DEEP_STUBS);

  private final RuntimeService runtimeService = mock(RuntimeService.class, RETURNS_DEEP_STUBS);

  /**
   * The engine's query builders are fluent AND generic - {@code RETURNS_SELF}
   * (not deep stubs, whose type inference fails on the generic {@code Query}
   * methods) makes every filter call return the mock itself, so only the
   * terminal {@code list()}/{@code singleResult()} needs stubbing.
   */
  private final org.camunda.bpm.engine.history.HistoricProcessInstanceQuery historicInstanceQuery = mock(
      org.camunda.bpm.engine.history.HistoricProcessInstanceQuery.class, RETURNS_SELF);

  private final org.camunda.bpm.engine.runtime.ProcessInstanceQuery instanceQuery = mock(
      org.camunda.bpm.engine.runtime.ProcessInstanceQuery.class, RETURNS_SELF);

  private final org.camunda.bpm.engine.repository.ProcessDefinitionQuery definitionQuery = mock(
      org.camunda.bpm.engine.repository.ProcessDefinitionQuery.class, RETURNS_SELF);

  private final Camunda7WorkflowViewer viewer = new Camunda7WorkflowViewer(
      "c7", repositoryService, historyService, runtimeService);

  @org.junit.jupiter.api.BeforeEach
  public void wireQueries() {

    when(historyService.createHistoricProcessInstanceQuery()).thenReturn(historicInstanceQuery);
    when(runtimeService.createProcessInstanceQuery()).thenReturn(instanceQuery);
    when(repositoryService.createProcessDefinitionQuery()).thenReturn(definitionQuery);

  }

  /**
   * No historic instance for the aggregate - the engine either runs with history
   * level {@code none} or the history was cleaned up.
   */
  private void withoutHistory() {

    when(historicInstanceQuery.list()).thenReturn(List.of());

  }

  @Test
  @DisplayName("A workflow module without a tenant asks without one - Camunda rejects a null tenant id")
  public void aModuleWithoutTenantIsQueriedWithoutTenant() {

    // a workflow module reached with 'name-clash-avoidance: none' (or 'use-prefix') has no
    // tenant at all. Passing null to tenantIdIn does not mean "any tenant" - the engine
    // throws NullValueException('tenantIds contains null value'), which used to make every
    // viewer call of a default-configured application fail.
    withoutHistory();
    when(instanceQuery.singleResult()).thenReturn(null);

    assertEquals(List.of(), viewer.getProcessDefinitions("module", "Process", null, "42", null));
    assertNull(viewer.getWorkflowHistory("module", "Process", null, "42", null));

    org.mockito.Mockito.verify(instanceQuery, org.mockito.Mockito.atLeastOnce()).withoutTenantId();
    org.mockito.Mockito.verify(historicInstanceQuery, org.mockito.Mockito.atLeastOnce()).withoutTenantId();
    org.mockito.Mockito
        .verify(instanceQuery, org.mockito.Mockito.never())
        .tenantIdIn(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito
        .verify(historicInstanceQuery, org.mockito.Mockito.never())
        .tenantIdIn(org.mockito.ArgumentMatchers.any());

  }

  @Test
  @DisplayName("A workflow unknown to this engine is reported as unknown (empty list / null history)")
  public void unknownWorkflowIsReportedAsUnknown() {

    withoutHistory();
    when(instanceQuery.singleResult()).thenReturn(null);

    assertEquals(List.of(), viewer.getProcessDefinitions("module", "Process", "module", "42", null));
    assertNull(viewer.getWorkflowHistory("module", "Process", "module", "42", null));

  }

  @Test
  @DisplayName("Without history the running instance still answers the definition - with a null element history")
  public void withoutHistoryTheRunningInstanceAnswers() {

    withoutHistory();
    final var instance = mock(ProcessInstance.class);
    when(instance.getProcessDefinitionId()).thenReturn("Process:1:aaa");
    when(instanceQuery.singleResult()).thenReturn(instance);
    when(repositoryService
        .getProcessDefinition("Process:1:aaa")
        .getId())
        .thenReturn("Process:1:aaa");
    when(repositoryService
        .getProcessDefinition("Process:1:aaa")
        .getKey())
        .thenReturn("Process");
    when(repositoryService
        .getProcessDefinition("Process:1:aaa")
        .getVersion())
        .thenReturn(1);
    when(repositoryService
        .getBpmnModelInstance("Process:1:aaa")
        .getModelElementsByType(org.camunda.bpm.model.bpmn.instance.CallActivity.class))
        .thenReturn(List.of());

    final var definitions = viewer.getProcessDefinitions("module", "Process", "module", "42", null);
    assertEquals(1, definitions.size());
    assertEquals("Process:1:aaa", definitions
        .getFirst()
        .id());

    final var history = viewer.getWorkflowHistory("module", "Process", "module", "42", null);
    assertEquals("Process:1:aaa", history.processDefinitionId());
    assertNull(history.startTime());
    assertNull(
        history.elementsHistory(),
        "history level 'none' means no element history - the SPI expresses that as null");

  }

  @Test
  @DisplayName("A history context of a foreign workflow is rejected")
  public void foreignHistoryContextIsRejected() {

    final var primaryInstance = mock(org.camunda.bpm.engine.history.HistoricProcessInstance.class);
    when(primaryInstance.getId()).thenReturn("primary-instance");
    when(historicInstanceQuery.list()).thenReturn(List.of(primaryInstance));

    final var foreignInstance = mock(org.camunda.bpm.engine.history.HistoricProcessInstance.class);
    when(foreignInstance.getRootProcessInstanceId()).thenReturn("another-workflows-instance");
    when(historicInstanceQuery.singleResult()).thenReturn(foreignInstance);
    when(instanceQuery.singleResult()).thenReturn(null);

    assertEquals(List.of(), viewer.getProcessDefinitions("module", "Process", "module", "42", "foreign-context"));
    assertNull(viewer.getWorkflowHistory("module", "Process", "module", "42", "foreign-context"));

  }

  @Test
  @DisplayName("An unknown process definition has no BPMN XML")
  public void unknownProcessDefinitionHasNoXml() {

    when(repositoryService.getProcessModel("does-not-exist"))
        .thenThrow(new ProcessEngineException("no such definition"));

    assertNull(viewer.getBpmnXml("does-not-exist"));

  }

  @Test
  @DisplayName("Camunda 7 activity types map onto the SPI's element types")
  public void activityTypesAreMapped() {

    assertEquals(WorkflowElementType.UNKNOWN, Camunda7WorkflowViewer.elementTypeOf(null));
    assertEquals(WorkflowElementType.UNKNOWN, Camunda7WorkflowViewer.elementTypeOf("somethingNew"));
    // events - Camunda names them fine-grained, the SPI groups them
    assertEquals(WorkflowElementType.START_EVENT, Camunda7WorkflowViewer.elementTypeOf("startEvent"));
    assertEquals(WorkflowElementType.START_EVENT, Camunda7WorkflowViewer.elementTypeOf("messageStartEvent"));
    assertEquals(WorkflowElementType.END_EVENT, Camunda7WorkflowViewer.elementTypeOf("noneEndEvent"));
    assertEquals(WorkflowElementType.END_EVENT, Camunda7WorkflowViewer.elementTypeOf("endEvent"));
    assertEquals(WorkflowElementType.BOUNDARY_EVENT, Camunda7WorkflowViewer.elementTypeOf("boundaryTimer"));
    assertEquals(
        WorkflowElementType.INTERMEDIATE_CATCH_EVENT,
        Camunda7WorkflowViewer.elementTypeOf("intermediateMessageCatch"));
    assertEquals(
        WorkflowElementType.INTERMEDIATE_THROW_EVENT,
        Camunda7WorkflowViewer.elementTypeOf("intermediateSignalThrow"));
    // activities and gateways
    assertEquals(WorkflowElementType.SERVICE_TASK, Camunda7WorkflowViewer.elementTypeOf("serviceTask"));
    assertEquals(WorkflowElementType.USER_TASK, Camunda7WorkflowViewer.elementTypeOf("userTask"));
    assertEquals(WorkflowElementType.SEND_TASK, Camunda7WorkflowViewer.elementTypeOf("sendTask"));
    assertEquals(WorkflowElementType.RECEIVE_TASK, Camunda7WorkflowViewer.elementTypeOf("receiveTask"));
    assertEquals(
        WorkflowElementType.BUSINESS_RULE_TASK,
        Camunda7WorkflowViewer.elementTypeOf("businessRuleTask"));
    assertEquals(WorkflowElementType.SCRIPT_TASK, Camunda7WorkflowViewer.elementTypeOf("scriptTask"));
    assertEquals(WorkflowElementType.MANUAL_TASK, Camunda7WorkflowViewer.elementTypeOf("manualTask"));
    assertEquals(WorkflowElementType.TASK, Camunda7WorkflowViewer.elementTypeOf("task"));
    assertEquals(WorkflowElementType.CALL_ACTIVITY, Camunda7WorkflowViewer.elementTypeOf("callActivity"));
    assertEquals(WorkflowElementType.MULTI_INSTANCE, Camunda7WorkflowViewer.elementTypeOf("multiInstanceBody"));
    assertEquals(WorkflowElementType.EXCLUSIVE_GATEWAY, Camunda7WorkflowViewer.elementTypeOf("exclusiveGateway"));
    assertEquals(WorkflowElementType.INCLUSIVE_GATEWAY, Camunda7WorkflowViewer.elementTypeOf("inclusiveGateway"));
    assertEquals(WorkflowElementType.PARALLEL_GATEWAY, Camunda7WorkflowViewer.elementTypeOf("parallelGateway"));
    assertEquals(
        WorkflowElementType.EVENT_BASED_GATEWAY,
        Camunda7WorkflowViewer.elementTypeOf("eventBasedGateway"));
    assertEquals(WorkflowElementType.SUB_PROCESS, Camunda7WorkflowViewer.elementTypeOf("subProcess"));
    assertEquals(WorkflowElementType.EVENT_SUB_PROCESS, Camunda7WorkflowViewer.elementTypeOf("eventSubProcess"));
    assertEquals(WorkflowElementType.AD_HOC_SUB_PROCESS, Camunda7WorkflowViewer.elementTypeOf("adHocSubProcess"));
    assertEquals(WorkflowElementType.TRANSACTION, Camunda7WorkflowViewer.elementTypeOf("transaction"));
    assertEquals(WorkflowElementType.PROCESS, Camunda7WorkflowViewer.elementTypeOf("processDefinition"));
    assertEquals(WorkflowElementType.SEQUENCE_FLOW, Camunda7WorkflowViewer.elementTypeOf("sequenceFlow"));

  }

  @Test
  @DisplayName("Call activities without a resolvable called process are skipped")
  public void unresolvableCallActivitiesAreSkipped() {

    final var historicInstance = mock(org.camunda.bpm.engine.history.HistoricProcessInstance.class);
    when(historicInstance.getProcessDefinitionId()).thenReturn("Process:1:aaa");
    when(historicInstanceQuery.list()).thenReturn(List.of(historicInstance));
    when(repositoryService
        .getProcessDefinition("Process:1:aaa")
        .getId())
        .thenReturn("Process:1:aaa");
    when(repositoryService
        .getProcessDefinition("Process:1:aaa")
        .getKey())
        .thenReturn("Process");
    when(repositoryService
        .getProcessDefinition("Process:1:aaa")
        .getVersion())
        .thenReturn(1);

    final var byExpression = mock(org.camunda.bpm.model.bpmn.instance.CallActivity.class);
    when(byExpression.getCalledElement()).thenReturn("${dynamicProcess}");
    final var blank = mock(org.camunda.bpm.model.bpmn.instance.CallActivity.class);
    when(blank.getCalledElement()).thenReturn(" ");
    final var notDeployed = mock(org.camunda.bpm.model.bpmn.instance.CallActivity.class);
    when(notDeployed.getCalledElement()).thenReturn("NotDeployedProcess");
    when(notDeployed.getId()).thenReturn("TheCallActivity");
    when(repositoryService
        .getBpmnModelInstance("Process:1:aaa")
        .getModelElementsByType(org.camunda.bpm.model.bpmn.instance.CallActivity.class))
        .thenReturn(List.of(byExpression, blank, notDeployed));
    when(definitionQuery.singleResult()).thenReturn(null);

    final var definitions = viewer.getProcessDefinitions("module", "Process", "module", "42", null);

    assertEquals(1, definitions.size(), () -> "only the workflow's own definition is resolvable but got: "
        + definitions);
    assertTrue(definitions
        .getFirst()
        .id()
        .contains("Process:1:aaa"));

  }

}
