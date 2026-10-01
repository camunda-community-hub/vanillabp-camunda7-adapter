package io.vanillabp.camunda7.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.camunda.bpm.engine.ProcessEngineServices;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * What the start of a CALLED process is, where the call activity named the process to call
 * in an expression.
 * <p>
 * Camunda 7 hands a called process no business key, and a model which names the process to
 * call in an expression cannot be given the propagation while it is deployed - nobody knows
 * then which process will be called. So the instance arrives here without a name, and the
 * listener has to tell the two cases apart: a called process continuing the business case
 * of its caller goes by the caller's name, and a process with a workflow aggregate of its
 * own is the start it looks like.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7CalledProcessStartTest {

  private static final String MODULE = "loan-approval";

  private static final String CALLED = "RiskAssessment";

  private static final String CALLING = "LoanApproval";

  private static final String CALLERS_NAME = "4711";

  /**
   * The engine's one repository service: both the called and the calling process are
   * looked up in it, the way a running engine answers for every definition it holds.
   */
  private final RepositoryService repositoryService = mock(RepositoryService.class);

  private final ProcessEngineServices engineServices = mock(ProcessEngineServices.class);

  private ExecutionEntity anInstanceOf(
      final String bpmnProcessId,
      final String businessKey) {

    final var definition = mock(ProcessDefinition.class);
    when(definition.getKey()).thenReturn(bpmnProcessId);
    when(repositoryService.getProcessDefinition(bpmnProcessId
        + "-definition")).thenReturn(definition);
    when(engineServices.getRepositoryService()).thenReturn(repositoryService);

    final var execution = mock(ExecutionEntity.class);
    when(execution.getProcessBusinessKey()).thenReturn(businessKey);
    when(execution.getProcessEngineServices()).thenReturn(engineServices);
    when(execution.getProcessDefinitionId()).thenReturn(bpmnProcessId
        + "-definition");
    when(execution.getTenantId()).thenReturn(MODULE);
    when(execution.getProcessInstanceId()).thenReturn(bpmnProcessId
        + "-instance");
    when(execution.getCurrentActivityId()).thenReturn("TheStartEvent");
    when(execution.getVariables()).thenReturn(new org.camunda.bpm.engine.variable.impl.VariableMapImpl());
    return execution;

  }

  /**
   * An instance of the called process, started by a call activity of an instance of the
   * calling process which goes by {@link #CALLERS_NAME}.
   */
  private ExecutionEntity aCalledInstance() {

    final var callingInstance = anInstanceOf(CALLING, CALLERS_NAME);
    final var calledInstance = anInstanceOf(CALLED, null);
    when(calledInstance.getSuperExecution()).thenReturn(callingInstance);
    return calledInstance;

  }

  private Camunda7TaskRegistry aRegistryWhere(
      final boolean theAggregateIsShared) {

    final var registry = new Camunda7TaskRegistry();
    registry.setAdapterId("c7");
    registry.registerTenant(MODULE, MODULE);
    registry.registerProcess(MODULE, CALLED, CALLED);
    registry.registerProcess(MODULE, CALLING, CALLING);
    registry
        .setWorkflowAggregateSharing((
            workflowModuleId,
            bpmnProcessId,
            otherBpmnProcessId) -> theAggregateIsShared);
    return registry;

  }

  /**
   * The listener under a core which answers every start with the workflow aggregate the
   * BPMS named.
   */
  private Camunda7BpmsInitiatedStartListener aListenerOn(
      final Camunda7TaskRegistry registry,
      final BpmsInitiatedStartInvoker invoker) {

    return new Camunda7BpmsInitiatedStartListener(
        invoker, registry, BpmsStartTrigger.Kind.NONE, (
            workflowModuleId,
            bpmnProcessId) -> true);

  }

  private BpmsInitiatedStartInvoker aCoreAnswering(
      final String workflowAggregateId) {

    final var invoker = mock(BpmsInitiatedStartInvoker.class);
    when(invoker.startWorkflowByBpms(eq(MODULE), any(), any()))
        .thenReturn(new BpmsInitiatedStartResult(workflowAggregateId, "id", Map.of(), true));
    return invoker;

  }

  /**
   * What the listener told the core this start is named, captured from the context it
   * built.
   */
  private String theNameReportedToTheCore(
      final BpmsInitiatedStartInvoker invoker) {

    final var context = org.mockito.ArgumentCaptor.forClass(BpmsInitiatedStartContext.class);
    verify(invoker).startWorkflowByBpms(eq(MODULE), eq(CALLED), context.capture());
    return context.getValue().getBusinessKey();

  }

  @Test
  @DisplayName("A called process working on the aggregate of its caller goes by the caller's name")
  public void aCalledProcessOnTheSameAggregateGoesByTheCallersName() {

    final var invoker = aCoreAnswering(CALLERS_NAME);
    final var calledInstance = aCalledInstance();

    aListenerOn(aRegistryWhere(true), invoker).notify(calledInstance);

    assertEquals(
        CALLERS_NAME,
        theNameReportedToTheCore(invoker),
        "the core is told the name the caller goes by, so it reads this start as a workflow of ours");
    verify(calledInstance).setProcessBusinessKey(CALLERS_NAME);

  }

  @Test
  @DisplayName("A called process with a workflow aggregate of its own is the start it looks like")
  public void aCalledProcessWithItsOwnAggregateIsReportedAsAStart() {

    final var invoker = aCoreAnswering("its-own-aggregate");
    final var calledInstance = aCalledInstance();

    aListenerOn(aRegistryWhere(false), invoker).notify(calledInstance);

    assertNull(
        theNameReportedToTheCore(invoker),
        "nothing is inherited, so the core builds the workflow aggregate of this process");
    verify(calledInstance).setProcessBusinessKey("its-own-aggregate");
    verify(calledInstance, never()).setProcessBusinessKey(CALLERS_NAME);

  }

  @Test
  @DisplayName("A called process whose caller goes by no name is the start it looks like")
  public void aCalledProcessOfANamelessCallerIsReportedAsAStart() {

    final var invoker = aCoreAnswering("its-own-aggregate");
    final var namelessCaller = anInstanceOf(CALLING, null);
    final var calledInstance = anInstanceOf(CALLED, null);
    when(calledInstance.getSuperExecution()).thenReturn(namelessCaller);

    aListenerOn(aRegistryWhere(true), invoker).notify(calledInstance);

    assertNull(
        theNameReportedToTheCore(invoker),
        "there is no name to inherit from a caller which goes by none itself");
    verify(calledInstance).setProcessBusinessKey("its-own-aggregate");

  }

  @Test
  @DisplayName("An instance nobody called keeps being a start past VanillaBP")
  public void anInstanceNobodyCalledKeepsBeingAForeignStart() {

    final var invoker = aCoreAnswering("built-by-the-application");
    final var instance = anInstanceOf(CALLED, null);

    aListenerOn(aRegistryWhere(true), invoker).notify(instance);

    assertNull(
        theNameReportedToTheCore(invoker),
        "no call activity started this instance, so there is nobody to inherit a name from");
    verify(instance).setProcessBusinessKey("built-by-the-application");

  }

}
