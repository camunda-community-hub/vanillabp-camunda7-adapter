package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.RepositoryServiceImpl;
import org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7AsyncBpmnParseListener;
import io.vanillabp.camunda7.wiring.Camunda7TaskCancellationListener;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.camunda7.wiring.Camunda7UserTaskEventListener;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Three processes meet in one engine. {@code Claimed} is a process a
 * <code>&#64;WorkflowService</code> of the application claims. {@code Marked} sits in the same
 * file and nobody claims it: the application said that something else serves it. {@code Foreign}
 * was deployed by somebody else into the same engine.
 * <p>
 * Only the claimed process gets what this adapter adds: the transaction boundaries, the
 * listeners, the tasks the core is asked about. The other two stay as they were modelled, the
 * flags a modeller wrote included. Version 1 changed the flags of every process the engine parsed.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7UnclaimedProcessesAreLeftAloneTest {

  private static final String MODULE = "loan-approval";

  private static final String FILE = "loan-approval.bpmn";

  private static final String ADAPTER_ID = "c7";

  /**
   * One process with a start event, a service task, a user task which asks for a transaction
   * boundary before itself, and an end. The adapter would change all of that.
   */
  private static String process(
      final String id) {

    return """
          <bpmn:process id="%1$s" isExecutable="true">
            <bpmn:startEvent id="Start_%1$s" />
            <bpmn:sequenceFlow id="Flow_1_%1$s" sourceRef="Start_%1$s" targetRef="Work_%1$s" />
            <bpmn:serviceTask id="Work_%1$s" camunda:expression="${work%1$s}" />
            <bpmn:sequenceFlow id="Flow_2_%1$s" sourceRef="Work_%1$s" targetRef="Approve_%1$s" />
            <bpmn:userTask id="Approve_%1$s" camunda:formKey="approve%1$s" camunda:asyncBefore="true" />
            <bpmn:sequenceFlow id="Flow_3_%1$s" sourceRef="Approve_%1$s" targetRef="End_%1$s" />
            <bpmn:endEvent id="End_%1$s" />
          </bpmn:process>
        """
        .formatted(id);

  }

  private static String file(
      final String... processIds) {

    final var processes = new StringBuilder();
    for (final var processId : processIds) {
      processes.append(process(processId));
    }
    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
        %s
        </bpmn:definitions>
        """
        .formatted(processes);

  }

  private static BpmnModelInstance model(
      final String xml) {

    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private ProcessEngine engine;

  private WorkflowTaskWiring core;

  private final ExecutionListener startListener = mock(ExecutionListener.class);

  private Camunda7TaskCancellationListener cancellationListener;

  private Camunda7UserTaskEventListener userTaskEventListener;

  @BeforeEach
  public void deployTheThreeProcesses() {

    final var registry = new Camunda7TaskRegistry();
    final var invoker = mock(WorkflowTaskInvoker.class);
    cancellationListener = new Camunda7TaskCancellationListener(invoker, registry);
    userTaskEventListener = new Camunda7UserTaskEventListener(invoker, registry);
    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:unclaimed-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    // a list the engine appends its own listeners to, so an immutable one breaks it
    configuration
        .setCustomPostBPMNParseListeners(
            new ArrayList<>(
                List
                    .of(
                        new Camunda7AsyncBpmnParseListener(
                            cancellationListener, userTaskEventListener, kind -> startListener))));
    engine = configuration.buildProcessEngine();

    core = TestCollaborators.aCoreClaimingEveryProcess();
    when(core.isClaimedByAWorkflowService(MODULE, "Marked")).thenReturn(false);
    final var service = new Camunda7DeploymentService(
        ADAPTER_ID, engine.getRepositoryService(), mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .workflowTaskWiring(core)
            .workflowTaskInvoker(invoker)
            .build(), registry);
    service.setRuntimeService(engine.getRuntimeService());

    // the adapter's own file, prepared and wired the way the core does it: once per process
    final var model = model(file("Claimed", "Marked"));
    var context = service.prepareBpmn(MODULE, null, FILE, "Claimed", model);
    context = service.prepareBpmn(MODULE, context, FILE, "Marked", model);
    service.wireBpmn(MODULE, FILE, "Claimed", model, context);
    service.wireBpmn(MODULE, FILE, "Marked", model, context);
    engine
        .getRepositoryService()
        .createDeployment()
        .name(MODULE)
        .addModelInstance(FILE, model)
        .deploy();

    // and the model somebody else deploys into the same engine
    engine
        .getRepositoryService()
        .createDeployment()
        .name("somebody-else")
        .addString("foreign.bpmn", file("Foreign"))
        .deploy();

  }

  @AfterEach
  public void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  private ActivityImpl activity(
      final String processId,
      final String elementId) {

    final var definitionId = engine
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(processId)
        .singleResult()
        .getId();
    final var definition = (ProcessDefinitionEntity) ((RepositoryServiceImpl) engine.getRepositoryService())
        .getDeployedProcessDefinition(definitionId);
    return definition.findActivity(elementId);

  }

  private boolean hasTheUserTaskListener(
      final ActivityImpl userTask) {

    final var taskDefinition = ((UserTaskActivityBehavior) userTask.getActivityBehavior()).getTaskDefinition();
    return taskDefinition
        .getBuiltinTaskListeners()
        .getOrDefault(TaskListener.EVENTNAME_CREATE, List.of())
        .contains(userTaskEventListener);

  }


  @Test
  @DisplayName("The claimed process gets its transaction boundaries and its listeners")
  public void theClaimedProcessGetsWhatTheAdapterAdds() {

    final var serviceTask = activity("Claimed", "Work_Claimed");
    assertTrue(serviceTask.isAsyncBefore(), "a service task begins a transaction");
    assertTrue(serviceTask.isAsyncAfter(), "and ends one");
    assertTrue(
        serviceTask.getListeners(ExecutionListener.EVENTNAME_END).contains(cancellationListener),
        "the cancellation listener sits on it");

    final var userTask = activity("Claimed", "Approve_Claimed");
    assertFalse(userTask.isAsyncBefore(), "a user task waits anyway, so the flag before it is taken away");
    assertTrue(userTask.isAsyncAfter());
    assertTrue(hasTheUserTaskListener(userTask));

    assertTrue(
        activity("Claimed", "Start_Claimed")
            .getListeners(ExecutionListener.EVENTNAME_START)
            .contains(startListener),
        "the start listener sits on the start event");

  }

  @Test
  @DisplayName("A process nobody claims stays as it was modelled, and the core is asked nothing about its tasks")
  public void aMarkedProcessStaysAsModelled() {

    assertProcessIsAsModelled("Marked");
    verify(core, never()).validateTaskWiring(any(), eq(MODULE), eq("Marked"), any());
    verify(core, never()).registerProcessVersions(any(), eq(MODULE), eq("Marked"), any());

  }

  @Test
  @DisplayName("A process somebody else deployed into the same engine stays as it was modelled")
  public void aForeignProcessStaysAsModelled() {

    assertProcessIsAsModelled("Foreign");

  }

  private void assertProcessIsAsModelled(
      final String processId) {

    final var serviceTask = activity(processId, "Work_"
        + processId);
    assertFalse(serviceTask.isAsyncBefore(), "no transaction boundary before the service task");
    assertFalse(serviceTask.isAsyncAfter(), "and none after it");
    assertFalse(
        serviceTask.getListeners(ExecutionListener.EVENTNAME_END).contains(cancellationListener),
        "no cancellation listener");

    final var userTask = activity(processId, "Approve_"
        + processId);
    assertTrue(userTask.isAsyncBefore(), "the flag the modeller wrote stays");
    assertFalse(userTask.isAsyncAfter(), "and no flag is added");
    assertFalse(hasTheUserTaskListener(userTask), "no user task listener");

    assertFalse(
        activity(processId, "Start_"
            + processId)
            .getListeners(ExecutionListener.EVENTNAME_START)
            .contains(startListener),
        "no start listener");

  }

}
