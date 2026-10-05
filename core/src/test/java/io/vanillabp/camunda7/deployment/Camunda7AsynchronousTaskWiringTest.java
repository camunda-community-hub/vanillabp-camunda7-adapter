package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot says about a task which has to stay open although the model completes it when
 * the expression returns.
 * <p>
 * The question behind every case is which key the adapter asks the core with. A method names
 * its task by the task definition or by the element id, the wiring validation matches on
 * either, and so this check has to ask both. A listener is the exception and has a case of its
 * own: it is served by its task definition alone.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7AsynchronousTaskWiringTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  private static final String TASK_DEFINITION = "signTheContract";

  private static final String ELEMENT_ID = "Activity_sign";

  private static final String LISTENER_TASK_DEFINITION = "archiveTheOrder";

  private static BpmnModelInstance model(
      final String taskContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
            <bpmn:startEvent id="start"><bpmn:outgoing>toTask</bpmn:outgoing></bpmn:startEvent>
            <bpmn:sequenceFlow id="toTask" sourceRef="start" targetRef="%s" />
        %s
            <bpmn:sequenceFlow id="toEnd" sourceRef="%s" targetRef="Event_Done" />
            <bpmn:endEvent id="Event_Done"><bpmn:incoming>toEnd</bpmn:incoming></bpmn:endEvent>
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, ELEMENT_ID, taskContent, ELEMENT_ID);
    return Bpmn.readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

  }

  /**
   * The task as an *Expression*, which completes it the moment the expression returns.
   */
  private static BpmnModelInstance aTaskWiredByExpression() {

    return model(
        """
                <bpmn:serviceTask id="%s" camunda:expression="${%s}"><bpmn:incoming>toTask</bpmn:incoming><bpmn:outgoing>toEnd</bpmn:outgoing></bpmn:serviceTask>
            """
            .formatted(ELEMENT_ID, TASK_DEFINITION));

  }

  /**
   * The same task as a *Delegate expression*, which is the wiring a task staying open needs.
   */
  private static BpmnModelInstance aTaskWiredByDelegateExpression() {

    return model(
        """
                <bpmn:serviceTask id="%s" camunda:delegateExpression="${%s}"><bpmn:incoming>toTask</bpmn:incoming><bpmn:outgoing>toEnd</bpmn:outgoing></bpmn:serviceTask>
            """
            .formatted(ELEMENT_ID, TASK_DEFINITION));

  }

  /**
   * One element carrying both: a task which may stay open and a listener somebody modelled.
   */
  private static BpmnModelInstance aTaskCarryingAListener() {

    return model("""
            <bpmn:serviceTask id="%s" camunda:delegateExpression="${%s}">
              <bpmn:extensionElements>
                <camunda:executionListener event="end" delegateExpression="${%s}" />
              </bpmn:extensionElements>
              <bpmn:incoming>toTask</bpmn:incoming><bpmn:outgoing>toEnd</bpmn:outgoing>
            </bpmn:serviceTask>
        """
        .formatted(ELEMENT_ID, TASK_DEFINITION, LISTENER_TASK_DEFINITION));

  }

  /**
   * The core of an application whose <code>@TaskId</code> method is wired by the given names -
   * one of them where the method names its task definition, the other where it names the
   * element. Everything else answers no, which is what a method without the parameter looks
   * like.
   */
  private static WorkflowTaskWiring aCoreKeepingTasksOpenFor(
      final String... wiringNames) {

    final var wiring = mock(WorkflowTaskWiring.class);
    when(wiring.workflowTaskCompletesAsynchronously(anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> List.of(wiringNames).contains(invocation.<String>getArgument(2)));
    return wiring;

  }

  /**
   * The core of an application which has a method for the listener's task definition, which is
   * what makes a modelled listener this adapter's business at all.
   */
  private static WorkflowTaskInvoker aCoreServingTheListener() {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.workflowTaskHandlerExists(anyString(), anyString(), anyString()))
        .thenAnswer(invocation -> LISTENER_TASK_DEFINITION.equals(invocation.getArgument(2)));
    return invoker;

  }

  private static void deploy(
      final WorkflowTaskWiring wiring,
      final BpmnModelInstance model) {

    deploy(wiring, mock(WorkflowTaskInvoker.class), null, model);

  }

  private static void deploy(
      final WorkflowTaskWiring wiring,
      final WorkflowTaskInvoker invoker,
      final Camunda7AllowListenersResolver allowListeners,
      final BpmnModelInstance model) {

    final var service = new Camunda7DeploymentService(
        "c7", null, mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .workflowTaskWiring(wiring)
            .workflowTaskInvoker(invoker)
            .build(), new Camunda7TaskRegistry());
    service.setAllowListenersResolver(allowListeners);
    final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
    service.wireBpmn(MODULE, FILE, PROCESS, model, context);

  }

  @Test
  @DisplayName("A method wired by the task definition ends the boot of a task wired by an expression")
  public void aMethodWiredByTheTaskDefinitionEndsTheBoot() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> deploy(aCoreKeepingTasksOpenFor(TASK_DEFINITION), aTaskWiredByExpression()));

    final var message = refused.getMessage();
    assertTrue(message.contains("'"
        + TASK_DEFINITION
        + "'"),
        () -> "the task a modeller has to find: "
            + message);
    assertTrue(message.contains("@TaskId"), () -> "what the method declares: "
        + message);
    assertTrue(
        message.contains("'camunda:delegateExpression'"),
        () -> "and the fix: "
            + message);

  }

  @Test
  @DisplayName("A method wired by the element id ends it just the same")
  public void aMethodWiredByTheElementIdEndsTheBoot() {

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> deploy(aCoreKeepingTasksOpenFor(ELEMENT_ID), aTaskWiredByExpression()),
        "a method naming the element is wired to the task like one naming the task definition, "
            + "so it meets the same check");

    assertTrue(
        refused.getMessage().contains("@TaskId"),
        () -> "and it reads the same, because the defect is the same: "
            + refused.getMessage());

  }

  @Test
  @DisplayName("A delegate expression keeps the task open, whichever key the method was wired by")
  public void aDelegateExpressionDeploys() {

    assertDoesNotThrow(
        () -> deploy(aCoreKeepingTasksOpenFor(TASK_DEFINITION), aTaskWiredByDelegateExpression()));
    assertDoesNotThrow(() -> deploy(aCoreKeepingTasksOpenFor(ELEMENT_ID), aTaskWiredByDelegateExpression()));

  }

  @Test
  @DisplayName("A method without @TaskId deploys on either wiring")
  public void aSynchronousMethodDeploys() {

    assertDoesNotThrow(() -> deploy(aCoreKeepingTasksOpenFor(), aTaskWiredByExpression()));
    assertDoesNotThrow(() -> deploy(aCoreKeepingTasksOpenFor(), aTaskWiredByDelegateExpression()));

  }

  @Test
  @DisplayName("The listener of an element whose task stays open is none of that task's business")
  public void theListenerIsAskedByItsTaskDefinitionAlone() {

    assertDoesNotThrow(
        () -> deploy(
            aCoreKeepingTasksOpenFor(ELEMENT_ID),
            aCoreServingTheListener(),
            (
                workflowModuleId,
                bpmnProcessId) -> new Camunda7AllowListenersResolver.Setting(
                    true, "vanillabp.adapters.c7.allow-listeners"),
            aTaskCarryingAListener()),
        "the @TaskId method named the element, and the element carries a task and a listener at "
            + "once - the task may stay open, so refusing the listener would refuse a right model");

  }

}
