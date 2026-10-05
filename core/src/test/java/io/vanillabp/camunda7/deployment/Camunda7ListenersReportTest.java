package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver;
import io.vanillabp.camunda7.wiring.Camunda7TaskConnectable;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot says about the execution listeners somebody modelled: a refusal where nobody
 * asked for them, one framed report where somebody did, and a warning for the forms the
 * engine serves itself.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7ListenersReportTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  private static final String ADAPTER_ID = "c7";

  private ProcessEngine engine;

  @AfterEach
  public void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  private static BpmnModelInstance model(
      final String processContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:startEvent id="start"><bpmn:outgoing>toCheck</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="toCheck" sourceRef="start" targetRef="check" />
            <bpmn:serviceTask id="check" camunda:expression="${checkCredit}"><bpmn:incoming>toCheck</bpmn:incoming><bpmn:outgoing>toEnd</bpmn:outgoing></bpmn:serviceTask>
            <bpmn:sequenceFlow id="toEnd" sourceRef="check" targetRef="Event_Done" />
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, processContent);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  private static BpmnModelInstance modelWithAListener() {

    return model("""
            <bpmn:endEvent id="Event_Done">
              <bpmn:extensionElements>
                <camunda:executionListener event="end" delegateExpression="${archiveTheOrder}" />
              </bpmn:extensionElements>
              <bpmn:incoming>toEnd</bpmn:incoming>
            </bpmn:endEvent>
        """);

  }

  private static BpmnModelInstance modelWithoutAListener() {

    return model("""
            <bpmn:endEvent id="Event_Done"><bpmn:incoming>toEnd</bpmn:incoming></bpmn:endEvent>
        """);

  }

  private Camunda7DeploymentService adapter(
      final WorkflowTaskWiring wiring,
      final Camunda7AllowListenersResolver resolver) {

    return adapter(wiring, aCoreWithMethodsFor("checkCredit", "archiveTheOrder", "archive"), resolver);

  }

  private Camunda7DeploymentService adapter(
      final WorkflowTaskWiring wiring,
      final WorkflowTaskInvoker invoker,
      final Camunda7AllowListenersResolver resolver) {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:listeners-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    engine = configuration.buildProcessEngine();

    final var service = new Camunda7DeploymentService(
        ADAPTER_ID, engine.getRepositoryService(), mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .workflowTaskWiring(wiring)
            .workflowTaskInvoker(invoker)
            .build(), new Camunda7TaskRegistry());
    service.setRuntimeService(engine.getRuntimeService());
    service.setAllowListenersResolver(resolver);
    return service;

  }

  /**
   * The core of an application which has a <code>@WorkflowTask</code> method for every task
   * definition a model names, which is what decides whether a listener is served at all.
   */
  private static WorkflowTaskWiring aCoreServingEverything() {

    final var wiring = mock(WorkflowTaskWiring.class);
    when(wiring.resolveWorkflowAggregateIdName(anyString(), anyString())).thenReturn("id");
    return wiring;

  }

  /**
   * What the core answers about the methods this application has. A listener is served where a
   * method names its task definition and nowhere else, so every case below says which names exist.
   */
  private static WorkflowTaskInvoker aCoreWithMethodsFor(
      final String... taskDefinitions) {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.workflowTaskHandlerExists(anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> List.of(taskDefinitions).contains(invocation.getArgument(2)));
    return invoker;

  }

  private static Camunda7AllowListenersResolver allowedBy(
      final String propertyKey) {

    return (
        workflowModuleId,
        bpmnProcessId) -> new Camunda7AllowListenersResolver.Setting(true, propertyKey);

  }

  /**
   * Runs the stages which fill the module's report, and writes it.
   */
  private String deploy(
      final CapturedOutput output,
      final Camunda7DeploymentService service,
      final BpmnModelInstance model) {

    final var before = output.getAll().length();
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
    service.wireBpmn(MODULE, FILE, PROCESS, model, context);
    service.deployResources(MODULE, context);
    return output.getAll().substring(before);

  }

  @Test
  @DisplayName("A module whose listeners are served gets a framed report naming key, module and listeners")
  public void theReportNamesTheListeners(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(aCoreServingEverything(), allowedBy("vanillabp.adapters.c7.allow-listeners")),
        modelWithAListener());

    assertTrue(
        logged.contains("MODELLED LISTENERS ARE SERVED: WORKFLOW MODULE 'loan-approval'"),
        () -> "a heading a reader finds without reading a word: "
            + logged);
    assertTrue(logged.contains("===================="), () -> "and the frame around it: "
        + logged);
    assertTrue(
        logged.contains("vanillabp.adapters.c7.allow-listeners"),
        () -> "the key which switched it on: "
            + logged);
    assertTrue(
        logged.contains("element 'Event_Done'") && logged.contains("${archiveTheOrder}"),
        () -> "the element and the expression a modeller recognises it by: "
            + logged);
    assertTrue(
        logged.contains("execution listener on 'end'"),
        () -> "and the event, which is part of the listener's identity: "
            + logged);
    assertTrue(
        logged.contains("VanillaBP tells your method when the element the listener sits on is canceled"),
        () -> "how the cancellation of the element reaches the method: "
            + logged);
    assertTrue(logged.contains("stops being portable"), () -> "what it costs the model: "
        + logged);
    assertFalse(
        logged.contains("What a listener method may write into the process instance depends"),
        () -> "and NOT the Camunda 8 sentence: there it depends on the listener, while on this "
            + "engine every listener writes the shared values inside the engine's own "
            + "transaction, exactly as a task does: "
            + logged);

  }

  @Test
  @DisplayName("A switch nobody needs is one line, not a frame")
  public void aSwitchNobodyNeedsIsOneLine(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(
            aCoreServingEverything(),
            allowedBy("vanillabp.workflow-modules.loan-approval.adapters.c7.allow-listeners")),
        modelWithoutAListener());

    assertTrue(
        logged.contains("no model of it carries one"),
        () -> "the switch is on and nothing uses it, which is worth a sentence: "
            + logged);
    assertFalse(logged.contains("MODELLED LISTENERS ARE SERVED"), () -> "and no frame: "
        + logged);

  }

  @Test
  @DisplayName("A model with no listener and no property says nothing at all")
  public void aModelWithoutListenersSaysNothing(
      final CapturedOutput output) {

    final var logged = deploy(output, adapter(aCoreServingEverything(), null), modelWithoutAListener());

    assertFalse(
        logged.contains("MODELLED LISTENERS ARE SERVED") || logged.contains("allow-listeners"),
        () -> "which is every application that never modelled one: "
            + logged);

  }

  @Test
  @DisplayName("Without the property the boot ends, naming the listener, the levels and the cost")
  public void withoutThePropertyTheBootEnds() {

    final var service = adapter(aCoreServingEverything(), null);
    final var model = modelWithAListener();
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> service.wireBpmn(MODULE, FILE, PROCESS, model, context));

    final var message = refused.getMessage();
    assertTrue(
        message.contains("element 'Event_Done'"),
        () -> "the element a modeller has to find in their own model: "
            + message);
    assertTrue(
        message.contains("vanillabp.adapters.c7.allow-listeners") && message
            .contains("vanillabp.workflow-modules.loan-approval.adapters.c7.allow-listeners") && message.contains(
                "vanillabp.workflow-modules.loan-approval.workflows.LoanApproval.adapters.c7.allow-listeners"),
        () -> "all three levels the key is read at: "
            + message);
    assertTrue(
        message.contains("evaluate the expression itself"),
        () -> "and what happens without the key, which is the reason the boot ends here: "
            + message);

  }

  @Test
  @DisplayName("Two listeners of one element under one expression end the boot and both are named")
  public void twoListenersUnderOneExpressionEndTheBoot() {

    final var service = adapter(
        aCoreServingEverything(),
        allowedBy("vanillabp.adapters.c7.allow-listeners"));
    final var model = model("""
            <bpmn:endEvent id="Event_Done">
              <bpmn:extensionElements>
                <camunda:executionListener event="start" delegateExpression="${archive}" />
                <camunda:executionListener event="end" delegateExpression="${archive}" />
              </bpmn:extensionElements>
              <bpmn:incoming>toEnd</bpmn:incoming>
            </bpmn:endEvent>
        """);
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> service.wireBpmn(MODULE, FILE, PROCESS, model, context));

    assertTrue(
        refused.getMessage().contains("execution listener on 'start'") && refused
            .getMessage()
            .contains("execution listener on 'end'"),
        () -> "both events, because version 1 picked one of them and said nothing: "
            + refused.getMessage());
    assertTrue(
        refused.getMessage().contains("expression of its own"),
        () -> "and the way out: "
            + refused.getMessage());

  }

  @Test
  @DisplayName("A listener no method names is left alone, and the boot says nothing about it")
  public void aListenerNoMethodNamesIsLeftAlone(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(aCoreServingEverything(), aCoreWithMethodsFor("checkCredit"), null),
        modelWithAListener());

    assertFalse(
        logged.contains("allow-listeners"),
        () -> "a delegate expression naming a Spring bean is an ordinary Camunda 7 model, and this "
            + "adapter has no business taking it away or talking about it: "
            + logged);

  }

  @Test
  @DisplayName("A class listener is nobody's business here either")
  public void aClassListenerIsLeftAlone(
      final CapturedOutput output) {

    final var logged = deploy(
        output,
        adapter(aCoreServingEverything(), null),
        model("""
                <bpmn:endEvent id="Event_Done">
                  <bpmn:extensionElements>
                    <camunda:executionListener event="end" class="com.acme.Archive" />
                  </bpmn:extensionElements>
                  <bpmn:incoming>toEnd</bpmn:incoming>
                </bpmn:endEvent>
            """));

    assertFalse(
        logged.contains("allow-listeners"),
        () -> "there is no expression naming a task definition, so no method can be matched and the "
            + "engine runs the class itself: "
            + logged);

  }

  @Test
  @DisplayName("A served listener reaches the core's wiring validation as a task of its own")
  public void theListenerIsAmongTheSpecs(
      final CapturedOutput output) {

    final var wiring = aCoreServingEverything();

    deploy(
        output,
        adapter(wiring, allowedBy("vanillabp.adapters.c7.allow-listeners")),
        modelWithAListener());

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<java.util.Collection<BpmnTaskSpec>> specs = ArgumentCaptor
        .forClass(java.util.Collection.class);
    Mockito
        .verify(wiring)
        .validateTaskWiring(anyString(), anyString(), specs.capture());
    final var taskDefinitions = specs
        .getValue()
        .stream()
        .map(BpmnTaskSpec::taskDefinition)
        .toList();

    assertTrue(taskDefinitions.contains("checkCredit"), () -> "the ordinary task: "
        + taskDefinitions);
    assertTrue(
        taskDefinitions.contains("archiveTheOrder"),
        () -> "and the listener, which is what gets the core's two directions for free: a listener "
            + "nothing serves ends the boot, and a method serving no listener is reported: "
            + taskDefinitions);

  }

  @Test
  @DisplayName("A served listener is registered so the engine's EL resolver finds it")
  public void theListenerIsRegisteredForTheElResolver(
      final CapturedOutput output) {

    final var registry = new Camunda7TaskRegistry();
    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:listeners-el-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P30D");
    engine = configuration.buildProcessEngine();
    final var service = new Camunda7DeploymentService(
        ADAPTER_ID, engine.getRepositoryService(), mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .workflowTaskWiring(aCoreServingEverything())
            .workflowTaskInvoker(aCoreWithMethodsFor("checkCredit", "archiveTheOrder"))
            .build(), registry);
    service.setRuntimeService(engine.getRuntimeService());
    service.setAllowListenersResolver(allowedBy("vanillabp.adapters.c7.allow-listeners"));

    final var model = modelWithAListener();
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
    service.wireBpmn(MODULE, FILE, PROCESS, model, context);

    final var resolved = registry.resolve(MODULE, PROCESS, "Event_Done", "archiveTheOrder");
    assertTrue(resolved.isPresent(), "the engine resolves the listener's expression through this registry");
    assertTrue(
        resolved.get().isExecutionListener(),
        "and the type decides what the resolver hands the engine: a listener object, never an "
            + "activity behavior, because a listener may not leave its element");
    assertTrue(
        List
            .of(
                Camunda7TaskConnectable.Type.EXECUTION_LISTENER_DELEGATE_EXPRESSION,
                Camunda7TaskConnectable.Type.EXECUTION_LISTENER_EXPRESSION)
            .contains(resolved.get().type()));

  }

  @Test
  @DisplayName("A method declaring @TaskId cannot serve a listener")
  public void aMethodWithATaskIdCannotServeAListener() {

    final var wiring = aCoreServingEverything();
    when(wiring.workflowTaskCompletesAsynchronously(anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> "archiveTheOrder".equals(invocation.getArgument(2)));
    final var service = adapter(wiring, allowedBy("vanillabp.adapters.c7.allow-listeners"));
    final var model = modelWithAListener();
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> service.wireBpmn(MODULE, FILE, PROCESS, model, context));

    assertTrue(
        refused.getMessage().contains("@TaskId"),
        () -> "a listener is notified and done, so the id would complete nothing: "
            + refused.getMessage());

  }

}
