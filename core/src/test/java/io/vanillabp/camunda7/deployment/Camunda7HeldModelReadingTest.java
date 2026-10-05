package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Reading a model the ENGINE holds must never refuse it: the refusals of the
 * deployment judge a model this boot brings, while the startup check about older
 * versions only READS models an earlier generation deployed - and a boot ended over a
 * model nobody can change any more would be the check costing more than it answers.
 * What such a model carries and VanillaBP cannot read is one warning, and the rest of
 * the model is still answered.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7HeldModelReadingTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS_ID = "loan_approval";

  /**
   * An older version wired by an external task next to an ordinary one - what a model
   * deployed by another tool, or by a version of this application which accepted
   * more, can look like.
   */
  private static final String AN_OLD_MODEL_WITH_AN_EXTERNAL_TASK = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_Old" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="loan_approval" isExecutable="true">
          <bpmn:serviceTask id="Activity_external" camunda:topic="someTopic" />
          <bpmn:serviceTask id="Activity_check" camunda:expression="${checkCredit}" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  /**
   * An older version carrying an expression VanillaBP cannot read next to one it can.
   */
  private static final String AN_OLD_MODEL_WITH_AN_UNREADABLE_EXPRESSION = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="Definitions_Old" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="loan_approval" isExecutable="true">
          <bpmn:serviceTask id="Activity_weird" camunda:expression="${approve()} and ${commit()}" />
          <bpmn:serviceTask id="Activity_check" camunda:expression="${checkCredit}" />
        </bpmn:process>
      </bpmn:definitions>
      """;

  @Test
  @DisplayName("An external task in a version the engine holds is a warning, and the rest of the model is answered")
  public void anExternalTaskOfAHeldVersionDoesNotEndTheBoot(
      final CapturedOutput output) {

    final var specs = new AtomicReference<Collection<BpmnTaskSpec>>();
    assertDoesNotThrow(
        () -> specs.set(tasksOfHeldVersion(AN_OLD_MODEL_WITH_AN_EXTERNAL_TASK)),
        "a model the engine already holds is only being read - refusing it would end a boot "
            + "over a model nobody can change any more");

    final var logged = output.getOut() + output.getErr();
    assertTrue(
        logged.contains("camunda:topic 'someTopic'"),
        () -> "the task VanillaBP does not serve has to be named: "
            + logged);
    assertTrue(
        logged.contains("version 3"),
        () -> "and the version it sits in, as a version rather than as a file: "
            + logged);
    assertTrue(
        specs.get().stream().anyMatch(spec -> "checkCredit".equals(spec.taskDefinition())),
        "the task VanillaBP can read is still part of the answer");
    assertTrue(
        specs.get().stream().noneMatch(spec -> "Activity_external".equals(spec.activityId())),
        "the external task is not reported as something the application could serve");

  }

  @Test
  @DisplayName("An expression VanillaBP cannot read costs the check one task, never the boot")
  public void anUnreadableExpressionOfAHeldVersionDoesNotEndTheBoot(
      final CapturedOutput output) {

    final var specs = new AtomicReference<Collection<BpmnTaskSpec>>();
    assertDoesNotThrow(
        () -> specs.set(tasksOfHeldVersion(AN_OLD_MODEL_WITH_AN_UNREADABLE_EXPRESSION)));

    final var logged = output.getOut() + output.getErr();
    assertTrue(
        logged.contains("Activity_weird"),
        () -> "the task whose expression cannot be read has to be named: "
            + logged);
    assertTrue(
        specs.get().stream().anyMatch(spec -> "checkCredit".equals(spec.taskDefinition())),
        "the task VanillaBP can read is still part of the answer");

  }

  /**
   * Reads the tasks of version 3 of a process the engine holds, through the version
   * catalog the startup check uses.
   */
  private static Collection<BpmnTaskSpec> tasksOfHeldVersion(
      final String model) {

    final var service = new Camunda7DeploymentService(
        "c7", AnEngineHolding.theseModels(PROCESS_ID, Map.of("3", model)), mock(
            Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
                .builder()
                .build(), new Camunda7TaskRegistry());
    return service
        .processVersionCatalogOf(MODULE, PROCESS_ID)
        .tasksOfVersion(MODULE, PROCESS_ID, "3");

  }

}
