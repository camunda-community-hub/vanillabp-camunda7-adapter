package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskConnectable;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow is served from the model of the process definition it runs on. Two versions of
 * a process name different expressions at the same element, and the lookup of one version
 * must never answer with the task of the other one.
 * <p>
 * {@code Camunda7HandlersOfTheOwnVersionIT} asks the same of a running engine across an
 * upgrade of the application.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7TasksOfTheOwnVersionTest {

  private static final String MODULE = "own-version";

  private static final String PROCESS = "own_version";

  private static final String VERSION_ONE = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_One" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="own_version" isExecutable="true">
          <bpmn:serviceTask id="Activity_check" camunda:expression="${checkTheOldWay}" />
          <bpmn:userTask id="Activity_review" camunda:formKey="reviewTheOldWay" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static final String VERSION_TWO = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_Two" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="own_version" isExecutable="true">
          <bpmn:serviceTask id="Activity_check" camunda:delegateExpression="${checkTheNewWay}" />
          <bpmn:userTask id="Activity_review" camunda:formKey="reviewTheNewWay" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * A model which this adapter refuses, because nothing VanillaBP serves can be wired to an
   * external task.
   */
  private static final String A_MODEL_THIS_ADAPTER_REFUSES = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_Refused" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="own_version" isExecutable="true">
          <bpmn:serviceTask id="Activity_check" camunda:topic="someTopic" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  @Test
  @DisplayName("An expression evaluated at an element finds the task of its own version there")
  public void eachVersionIsAnsweredFromItsOwnModel() {

    final var taskRegistry = aRegistryOfAnAdapterWhichWiredVersionTwo();

    final var ofVersionOne = taskRegistry.tasksOf(MODULE, PROCESS, "definition-1", model(VERSION_ONE));
    final var ofVersionTwo = taskRegistry.tasksOf(MODULE, PROCESS, "definition-2", model(VERSION_TWO));

    // the name of version 1 is evaluated where version 2 wires another task
    assertEquals(
        "checkTheOldWay",
        ofVersionOne.resolve("Activity_check", "checkTheOldWay").orElseThrow().taskDefinition(),
        "a workflow of version 1 is served by the task version 1 wires");
    assertEquals(
        Camunda7TaskConnectable.Type.EXPRESSION,
        ofVersionOne.resolve("Activity_check", "checkTheOldWay").orElseThrow().type(),
        "and the way version 1 wires it, which decides whether the task stays open");
    assertEquals(
        "reviewTheOldWay",
        ofVersionOne.resolve("Activity_review", null).orElseThrow().taskDefinition(),
        "a user task of version 1 is announced under the form key of version 1");
    assertFalse(
        ofVersionOne.isTaskDefinitionName("checkTheNewWay"),
        "a name only version 2 knows is no task of version 1");
    assertEquals(
        "checkTheNewWay",
        ofVersionTwo.resolve("Activity_check", "checkTheNewWay").orElseThrow().taskDefinition(),
        "and a workflow of version 2 keeps being served by the task of version 2");

  }

  @Test
  @DisplayName("The model of a definition is read once, when a workflow of it first asks")
  public void theModelOfADefinitionIsReadOnce() {

    final var taskRegistry = aRegistryOfAnAdapterWhichWiredVersionTwo();
    final var reads = new AtomicInteger();
    final Supplier<BpmnModelInstance> countedModel = () -> {
      reads.incrementAndGet();
      return model(VERSION_ONE).get();
    };

    taskRegistry.tasksOf(MODULE, PROCESS, "definition-1", countedModel);
    taskRegistry.tasksOf(MODULE, PROCESS, "definition-1", countedModel);

    assertEquals(1, reads.get(), "a definition never changes, so its model is read for the first question only");

  }

  @Test
  @DisplayName("A model the extraction refuses serves nothing, and says what its workflows walk into")
  public void aModelWhichCannotBeReadServesNothing(
      final CapturedOutput output) {

    final var taskRegistry = aRegistryOfAnAdapterWhichWiredVersionTwo();

    final var tasks = taskRegistry.tasksOf(MODULE, PROCESS, "definition-0", model(A_MODEL_THIS_ADAPTER_REFUSES));

    assertTrue(
        tasks.resolve("Activity_check", "checkTheNewWay").isEmpty(),
        "the task version 2 wires at the same element is not handed to a workflow of another version");
    final var logged = output.getAllOfThisTest();
    assertTrue(
        logged.contains("process definition 'definition-0' of BPMN process 'own_version'"),
        () -> "the definition which could not be wired is named: "
            + logged);
    assertTrue(
        logged.contains("incident"),
        () -> "and what a workflow on it walks into: "
            + logged);

  }

  @Test
  @DisplayName("Without a definition the tasks of every wired model answer, as before")
  public void withoutADefinitionEveryWiredModelAnswers() {

    final var taskRegistry = aRegistryOfAnAdapterWhichWiredVersionTwo();

    assertEquals(
        "checkTheNewWay",
        taskRegistry
            .tasksOf(MODULE, PROCESS, null, model(VERSION_ONE))
            .resolve("Activity_check", "checkTheOldWay")
            .orElseThrow()
            .taskDefinition(),
        "an execution which names no definition only happens in tests, and keeps the old answer");

  }

  /**
   * The registry of an adapter which wired version 2, the version this boot deploys.
   */
  private static Camunda7TaskRegistry aRegistryOfAnAdapterWhichWiredVersionTwo() {

    final var taskRegistry = new Camunda7TaskRegistry();
    // the deployment service hands the registry its extraction while it is built
    new Camunda7DeploymentService(
        "c7", AnEngineHolding.theseModels(PROCESS, Map.of()), mock(
            Camunda7WorkflowProcessingLifecycle.class), TestCollaborators.complete(), taskRegistry);
    taskRegistry.registerProcess(MODULE, PROCESS, PROCESS);
    taskRegistry
        .register(new Camunda7TaskConnectable(
            MODULE, PROCESS, "Activity_check", "checkTheNewWay", Camunda7TaskConnectable.Type.DELEGATE_EXPRESSION));
    taskRegistry
        .register(new Camunda7TaskConnectable(
            MODULE, PROCESS, "Activity_review", "reviewTheNewWay", Camunda7TaskConnectable.Type.USER_TASK));
    return taskRegistry;

  }

  private static Supplier<BpmnModelInstance> model(
      final String bpmn) {

    return () -> Bpmn.readModelFromStream(new ByteArrayInputStream(bpmn.getBytes(StandardCharsets.UTF_8)));

  }

}
