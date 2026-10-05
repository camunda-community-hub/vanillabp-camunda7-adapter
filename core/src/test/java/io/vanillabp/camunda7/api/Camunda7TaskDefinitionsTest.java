package io.vanillabp.camunda7.api;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.instance.UserTask;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What {@link Camunda7TaskDefinitions} promises: the form key wins, the element id is the
 * fallback, and a form key which is an expression is the text the modeller wrote - the same
 * text while the model is read and while a workflow runs on it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7TaskDefinitionsTest {

  private static final String BPMN = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
          xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
          id="Definitions_1" targetNamespace="http://vanillabp.io/test">
        <bpmn:process id="Forms" isExecutable="true">
          <bpmn:userTask id="withFormKey" camunda:formKey="approveLoan"/>
          <bpmn:userTask id="withoutFormKey"/>
        </bpmn:process>
      </bpmn:definitions>
      """;

  @Test
  @DisplayName("The form key wins and the element id is the fallback")
  public void theFormKeyWinsAndTheElementIdIsTheFallback() {

    assertEquals("approveLoan", Camunda7TaskDefinitions.of("approveLoan", "Approve"));
    assertEquals("Approve", Camunda7TaskDefinitions.of(null, "Approve"));
    assertEquals("Approve", Camunda7TaskDefinitions.of("   ", "Approve"), "a blank form key counts as none");

  }

  @Test
  @DisplayName("The form key is read from the model as the modeller wrote it")
  public void theFormKeyIsReadFromTheModel() {

    final var model = Bpmn.readModelFromStream(new ByteArrayInputStream(BPMN.getBytes(UTF_8)));

    assertEquals(
        "approveLoan",
        Camunda7TaskDefinitions.formKeyOf((UserTask) model.getModelElementById("withFormKey")));
    assertNull(
        Camunda7TaskDefinitions.formKeyOf((UserTask) model.getModelElementById("withoutFormKey")),
        "a user task without a form key has none");
    assertNull(Camunda7TaskDefinitions.formKeyOf((UserTask) null), "no user task, no form key");

  }

  @Test
  @DisplayName("An expression form key is the same string while parsing and while running")
  public void anExpressionFormKeyIsTheSameStringAtParseTimeAndAtRuntime() {

    try (var engine = AnEngineRunningTheFactsProcess.started("task-definitions")) {

      final var written = AnEngineRunningTheFactsProcess.FORM_KEY_AS_WRITTEN;

      // what a reader of the deployed definition gets, which is what a parse listener and a
      // built-in task listener see
      assertEquals(
          written,
          Camunda7TaskDefinitions
              .formKeyOf(
                  engine.processEngine(),
                  engine.processDefinitionId(),
                  AnEngineRunningTheFactsProcess.USER_TASK_ID),
          "the form key read from the deployed definition is the written one");

      // what asking the running task answers instead - the second identity this class exists
      // to prevent
      final var evaluated = engine
          .waitingUserTask()
          .getFormKey();
      assertEquals(
          AnEngineRunningTheFactsProcess.FORM_KEY_EVALUATED,
          evaluated,
          "the engine evaluates the expression when the task is asked");
      assertNotEquals(written, evaluated, "the two readings really do differ - that is the point");

      // and therefore the task definition is one string, not two
      assertEquals(
          Camunda7TaskDefinitions.of(written, AnEngineRunningTheFactsProcess.USER_TASK_ID),
          Camunda7TaskDefinitions
              .of(
                  Camunda7TaskDefinitions
                      .formKeyOf(
                          engine.processEngine(),
                          engine.processDefinitionId(),
                          AnEngineRunningTheFactsProcess.USER_TASK_ID),
                  AnEngineRunningTheFactsProcess.USER_TASK_ID));

    }

  }

  @Test
  @DisplayName("A definition the engine does not hold has no form key rather than a failure")
  public void aDefinitionTheEngineDoesNotHoldHasNoFormKey() {

    try (var engine = AnEngineRunningTheFactsProcess.started("task-definitions-unknown")) {

      assertNull(
          Camunda7TaskDefinitions
              .formKeyOf(engine.processEngine(), engine.processDefinitionId(), "a-task-which-is-not-there"));
      assertNull(Camunda7TaskDefinitions.formKeyOf(null, "definition", "task"), "no engine");
      assertNull(Camunda7TaskDefinitions.formKeyOf(engine.processEngine(), null, "task"), "no definition");
      assertNull(
          Camunda7TaskDefinitions.formKeyOf(engine.processEngine(), engine.processDefinitionId(), null),
          "no task");

    }

  }

}
