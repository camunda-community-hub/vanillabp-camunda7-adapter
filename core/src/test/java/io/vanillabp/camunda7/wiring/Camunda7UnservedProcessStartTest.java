package io.vanillabp.camunda7.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.camunda.bpm.engine.ProcessEngineServices;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Whose starts the listener reports, now that it sits on every start event of every model
 * the engine parses.
 * <p>
 * An embedded engine holds whatever was deployed against its database: the processes of
 * this application, the ones it deploys without serving them, and another application's on
 * a shared database. The core can only answer for the first kind, so the listener asks
 * before it reports - see decision 28 in the repository's DECISIONS.md.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7UnservedProcessStartTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "LoanApproval";

  private DelegateExecution anInstanceStarting(
      final String businessKey) {

    final var definition = mock(ProcessDefinition.class);
    when(definition.getKey()).thenReturn(PROCESS);
    final var repositoryService = mock(RepositoryService.class);
    when(repositoryService.getProcessDefinition("definition-1")).thenReturn(definition);
    final var services = mock(ProcessEngineServices.class);
    when(services.getRepositoryService()).thenReturn(repositoryService);

    final var execution = mock(DelegateExecution.class);
    when(execution.getProcessBusinessKey()).thenReturn(businessKey);
    when(execution.getProcessEngineServices()).thenReturn(services);
    when(execution.getProcessDefinitionId()).thenReturn("definition-1");
    when(execution.getTenantId()).thenReturn(MODULE);
    when(execution.getProcessInstanceId()).thenReturn("instance-1");
    when(execution.getCurrentActivityId()).thenReturn("StartEvent_1");
    when(execution.getVariables()).thenReturn(Map.of());
    return execution;

  }

  private Camunda7TaskRegistry aRegistryKnowing() {

    final var registry = new Camunda7TaskRegistry();
    registry.setAdapterId("c7");
    registry.registerTenant(MODULE, MODULE);
    registry.registerProcess(MODULE, PROCESS, PROCESS);
    return registry;

  }

  @Test
  @DisplayName("A start of a process no workflow service serves is not reported")
  public void aStartOfAnUnservedProcessIsNotReported() {

    final var invoker = mock(BpmsInitiatedStartInvoker.class);
    final var testee = new Camunda7BpmsInitiatedStartListener(
        invoker, aRegistryKnowing(), BpmsStartTrigger.Kind.NONE, (
            workflowModuleId,
            bpmnProcessId) -> false);

    testee.notify(anInstanceStarting(null));

    verifyNoInteractions(invoker);

  }

  @Test
  @DisplayName("A start of a process this application serves reaches the core")
  public void aStartOfAServedProcessIsReported() {

    final var invoker = mock(BpmsInitiatedStartInvoker.class);
    when(invoker.startsAWorkflowOfItsOwn(MODULE, PROCESS)).thenReturn(true);
    when(
        invoker
            .startWorkflowByBpms(
                org.mockito.ArgumentMatchers.eq(MODULE),
                org.mockito.ArgumentMatchers.eq(PROCESS),
                org.mockito.ArgumentMatchers.any()))
        .thenReturn(
            new io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult(
                "4711", "id", Map.of("id", "4711"), true));
    final var served = new java.util.concurrent.atomic.AtomicReference<String>();
    final var testee = new Camunda7BpmsInitiatedStartListener(
        invoker, aRegistryKnowing(), BpmsStartTrigger.Kind.NONE, (
            workflowModuleId,
            bpmnProcessId) -> {
          served.set(bpmnProcessId);
          return true;
        });

    // an instance which already carries its name: the listener then has nothing to write
    // back, so this test needs no engine execution to write it into
    testee.notify(anInstanceStarting("4711"));

    assertEquals(PROCESS, served.get(), "the question is asked about the PLAIN process id");
    org.mockito.Mockito
        .verify(invoker)
        .startWorkflowByBpms(
            org.mockito.ArgumentMatchers.eq(MODULE),
            org.mockito.ArgumentMatchers.eq(PROCESS),
            org.mockito.ArgumentMatchers.any());

  }

}
