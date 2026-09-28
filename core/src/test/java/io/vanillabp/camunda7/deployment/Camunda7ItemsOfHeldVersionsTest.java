package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which rounds a task of a version the ENGINE STILL HOLDS iterates in without being handed
 * their value. A handler reads the item of an iteration out of the variable the model names
 * in <code>camunda:elementVariable</code>, and an element naming none hands nothing over, so
 * a method serving that version receives <code>null</code> and nothing says why.
 * <p>
 * Reading the model is this adapter's job and judging the methods is the core's, so what is
 * measured here is the shape the adapter hands over at the boundary to the migration SPI:
 * {@link BpmnTaskSpec#multiInstanceElementsWithoutAnItem()} of a task of a held version.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7ItemsOfHeldVersionsTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS_ID = "loan_approval";

  private static final String TASK = "Activity_check";

  private static String heldModel(
      final String multiInstanceShape) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_Old" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="loan_approval" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(multiInstanceShape);

  }

  private static final String AN_ELEMENT_NAMING_ITS_ITEM = """
          <bpmn:serviceTask id="Activity_check" camunda:expression="${checkCredit}">
            <bpmn:multiInstanceLoopCharacteristics camunda:collection="applications" camunda:elementVariable="application" />
          </bpmn:serviceTask>
      """;

  private static final String AN_ELEMENT_NAMING_NO_ITEM = """
          <bpmn:serviceTask id="Activity_check" camunda:expression="${checkCredit}">
            <bpmn:multiInstanceLoopCharacteristics camunda:collection="applications" />
          </bpmn:serviceTask>
      """;

  private static final String LISTENER_ELEMENT = "Event_RoundDone";

  private static final String A_LISTENER_IN_A_SUBPROCESS_NAMING_NO_ITEM = """
          <bpmn:subProcess id="SubProcess_Applications">
            <bpmn:multiInstanceLoopCharacteristics camunda:collection="applications" />
            <bpmn:endEvent id="Event_RoundDone">
              <bpmn:extensionElements>
                <camunda:executionListener event="end" expression="${auditTheRound}" />
              </bpmn:extensionElements>
            </bpmn:endEvent>
          </bpmn:subProcess>
      """;

  private static final String A_SUBPROCESS_NAMING_NO_ITEM = """
          <bpmn:subProcess id="SubProcess_Applications">
            <bpmn:multiInstanceLoopCharacteristics camunda:collection="applications" />
            <bpmn:serviceTask id="Activity_check" camunda:expression="${checkCredit}">
              <bpmn:multiInstanceLoopCharacteristics camunda:collection="documents" camunda:elementVariable="document" />
            </bpmn:serviceTask>
          </bpmn:subProcess>
      """;

  @Test
  @DisplayName("A held version whose element names its item answers an empty list")
  public void aVersionNamingItsItem() {

    assertEquals(List.of(), itemsNeverNamedBy(AN_ELEMENT_NAMING_ITS_ITEM));

  }

  @Test
  @DisplayName("A held version whose element names no item names that element")
  public void aVersionNamingNoItem() {

    assertEquals(List.of(TASK), itemsNeverNamedBy(AN_ELEMENT_NAMING_NO_ITEM));

  }

  @Test
  @DisplayName("Only the elements of the task's own chain are named, outermost first")
  public void onlyTheChainOfTheTask() {

    // the subprocess hands no item over and the task inside it does, so the one round
    // without a value is the outer one
    assertEquals(List.of("SubProcess_Applications"), itemsNeverNamedBy(A_SUBPROCESS_NAMING_NO_ITEM));

  }

  @Test
  @DisplayName("A modelled listener of a held version names the rounds around it too")
  public void aListenerOfAHeldVersion() {

    // a listener method reads its item out of the same iteration a task's method does, so
    // leaving the chain off a listener would hide the finding for that half of the model
    assertEquals(
        List.of("SubProcess_Applications"),
        itemsNeverNamedBy(A_LISTENER_IN_A_SUBPROCESS_NAMING_NO_ITEM, LISTENER_ELEMENT));

  }

  /**
   * What the catalog answers about the one task of version 3 of the given model.
   */
  private static List<String> itemsNeverNamedBy(
      final String multiInstanceShape) {

    return itemsNeverNamedBy(multiInstanceShape, TASK);

  }

  /**
   * What the catalog answers about one element of version 3 of the given model.
   */
  private static List<String> itemsNeverNamedBy(
      final String multiInstanceShape,
      final String elementId) {

    final var service = new Camunda7DeploymentService(
        "c7", AnEngineHolding.theseModels(PROCESS_ID, Map.of("3", heldModel(multiInstanceShape))), mock(
            Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
                .builder()
                .workflowTaskInvoker(aCoreServingEveryListener())
                .build(), new Camunda7TaskRegistry());
    // the key an application switched on before it deployed that version: without it a
    // listener of a held model is nothing this application ever had a method for
    service
        .setAllowListenersResolver((
            workflowModuleId,
            bpmnProcessId) -> new Camunda7AllowListenersResolver.Setting(
                true, "vanillabp.adapters.c7.allow-listeners"));
    return service
        .processVersionCatalogOf(MODULE, PROCESS_ID)
        .tasksOfVersion(MODULE, PROCESS_ID, "3")
        .stream()
        .filter(task -> elementId.equals(task.activityId()))
        .map(BpmnTaskSpec::multiInstanceElementsWithoutAnItem)
        .findFirst()
        .orElseThrow(() -> new AssertionError("the task of the held version was not read at all"));

  }

  /**
   * The core of an application which has a method for every task definition a model names.
   */
  private static WorkflowTaskInvoker aCoreServingEveryListener() {

    final var invoker = mock(WorkflowTaskInvoker.class);
    when(invoker.workflowTaskHandlerExists(anyString(), anyString(), anyString())).thenReturn(Boolean.TRUE);
    return invoker;

  }

}
