package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.impl.RepositoryServiceImpl;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7AsyncBpmnParseListener;
import io.vanillabp.camunda7.wiring.Camunda7BpmsInitiatedStartListener;
import io.vanillabp.camunda7.wiring.Camunda7TaskCancellationListener;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.camunda7.wiring.Camunda7UserTaskEventListener;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * The workflow module deploys its claimed process {@code CockpitProcess} into its own tenant.
 * Somebody else deploys a process with the same id into the same engine, without a tenant.
 * <p>
 * The tenant is what tells the two apart. Without a tenant, the only thing left to find a
 * workflow module by is the process definition key, and that key is the same for both. So the
 * key may lead to a workflow module only where the module itself deploys without a tenant.
 * Otherwise the foreign process would get the start listener, and the core would refuse its
 * start with a business key, because no workflow aggregate carries that name.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7ForeignProcessUnderAClaimedIdTest {

  private static final String MODULE = "cockpit";

  private static final String FILE = "cockpit.bpmn";

  private static final String ADAPTER_ID = "c7";

  private static final String PROCESS = "CockpitProcess";

  private static final String MODEL = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="CockpitProcess" isExecutable="true">
          <bpmn:startEvent id="Start" />
          <bpmn:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="Approve" />
          <bpmn:userTask id="Approve" />
          <bpmn:sequenceFlow id="Flow_2" sourceRef="Approve" targetRef="End" />
          <bpmn:endEvent id="End" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static BpmnModelInstance model() {

    return Bpmn.readModelFromStream(new ByteArrayInputStream(MODEL.getBytes(StandardCharsets.UTF_8)));

  }

  private ProcessEngine engine;

  private final BpmsInitiatedStartInvoker theCoresStartEntry = mock(BpmsInitiatedStartInvoker.class);

  @BeforeEach
  public void deployTheClaimedAndTheForeignProcess() {

    // the claimed process is no called process, so a start of it would be reported
    org.mockito.Mockito
        .when(theCoresStartEntry
            .startsAWorkflowOfItsOwn(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(true);

    final var registry = new Camunda7TaskRegistry();
    final var invoker = mock(WorkflowTaskInvoker.class);
    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:foreign-under-claimed-id-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    // the start listener the platform integrations build, so a start reaches the core's entry
    final java.util.function.Function<BpmsStartTrigger.Kind, ExecutionListener> startListeners = kind -> new Camunda7BpmsInitiatedStartListener(
        theCoresStartEntry, registry, kind, registry::isClaimedByAWorkflowService);
    final var cancellations = new Camunda7TaskCancellationListener(invoker, registry);
    final var userTaskEvents = new Camunda7UserTaskEventListener(invoker, registry);
    final var parseListener = new Camunda7AsyncBpmnParseListener(cancellations, userTaskEvents, startListeners);
    // a list the engine appends its own listeners to, so an immutable one breaks it
    configuration.setCustomPostBPMNParseListeners(new ArrayList<>(List.of(parseListener)));
    engine = configuration.buildProcessEngine();

    final var service = new Camunda7DeploymentService(
        ADAPTER_ID, engine.getRepositoryService(), mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .workflowTaskWiring(TestCollaborators.aCoreClaimingEveryProcess())
            .workflowTaskInvoker(invoker)
            .build(), registry);
    service.setRuntimeService(engine.getRuntimeService());

    // the workflow module's file, wired and deployed into the module's tenant the way the
    // deployment does it in the mode every module uses unless configured otherwise
    final var model = model();
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
    service.wireBpmn(MODULE, FILE, PROCESS, model, context);
    engine
        .getRepositoryService()
        .createDeployment()
        .name(MODULE)
        .tenantId(MODULE)
        .addModelInstance(FILE, model)
        .deploy();

    // the same id, deployed by somebody else without a tenant
    engine
        .getRepositoryService()
        .createDeployment()
        .name("somebody-else")
        .addString("foreign.bpmn", MODEL)
        .deploy();

  }

  @AfterEach
  public void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  private boolean theStartEventHasTheStartListener(
      final String tenantId) {

    final var query = engine
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(PROCESS);
    final var definitionId = (tenantId == null
        ? query.withoutTenantId()
        : query.tenantIdIn(tenantId))
        .singleResult()
        .getId();
    final var definition = (ProcessDefinitionEntity) ((RepositoryServiceImpl) engine.getRepositoryService())
        .getDeployedProcessDefinition(definitionId);
    return definition
        .findActivity("Start")
        .getListeners(ExecutionListener.EVENTNAME_START)
        .stream()
        .anyMatch(Camunda7BpmsInitiatedStartListener.class::isInstance);

  }

  @Test
  @DisplayName("The claimed process in the module's tenant gets the start listener")
  public void theClaimedProcessGetsTheStartListener() {

    assertTrue(theStartEventHasTheStartListener(MODULE));

  }

  @Test
  @DisplayName("The foreign process without a tenant gets no start listener, although its id is a claimed one")
  public void theForeignProcessGetsNoStartListener() {

    assertFalse(theStartEventHasTheStartListener(null));

  }

  @Test
  @DisplayName("The foreign process starts with a business key, and the core is not asked about it")
  public void theForeignProcessStartsWithABusinessKey() {

    final var instance = assertDoesNotThrow(() -> engine
        .getRuntimeService()
        .createProcessInstanceByKey(PROCESS)
        .processDefinitionWithoutTenantId()
        .businessKey("order-4711")
        .execute());

    assertNull(instance.getTenantId(), "it is the foreign definition which ran");
    assertEquals("order-4711", instance.getBusinessKey(), "the key somebody else chose stays");
    verify(theCoresStartEntry, never()).startWorkflowByBpms(any(), any(), any());

  }

}
