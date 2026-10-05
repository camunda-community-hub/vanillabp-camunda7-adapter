package io.vanillabp.camunda7.processservice;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.exception.NullValueException;
import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What Camunda 7 does with the two starts a Camunda 8 cluster refuses, and what the
 * adapter makes of the answers. Both are measured against a real engine, because both are
 * the engine's own behaviour and neither of them is written down anywhere else.
 * <p>
 * The first is a process nothing deployed. Camunda 7 answers it with a
 * {@link NullValueException}, which is a {@code ProcessEngineException} and therefore
 * everything the outbox repeats - the start of a committed aggregate was retried for as
 * long as its attempts lasted, and the workflow never came into being. The adapter calls
 * it permanent now, and only where a START met it.
 * <p>
 * The second is a model whose only start event is a timer or a message. Camunda 8 refuses
 * such a create with a conflict; Camunda 7 simply starts the instance at that event, so
 * there is no refusal to classify and nothing for the adapter to do. That difference is
 * the reason this class exists next to {@code Camunda8RefusedStartIT} rather than
 * mirroring it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7RefusedStartTest {

  /**
   * The workflow module of this test, which is also the tenant the adapter addresses:
   * the collaborators of these tests answer with the mode which isolates by tenant.
   */
  private static final String WORKFLOW_MODULE = "refused-start-module";

  private static ProcessEngine engine;

  private static final String TIMER_ONLY = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="Definitions_timer_only" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="TimerOnlyStartProcess" isExecutable="true">
          <bpmn:startEvent id="start">
            <bpmn:outgoing>f1</bpmn:outgoing>
            <bpmn:timerEventDefinition id="timer">
              <bpmn:timeCycle xsi:type="bpmn:tFormalExpression">R1/PT30M</bpmn:timeCycle>
            </bpmn:timerEventDefinition>
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="end" />
          <bpmn:endEvent id="end">
            <bpmn:incoming>f1</bpmn:incoming>
          </bpmn:endEvent>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static final String MESSAGE_ONLY = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" id="Definitions_message_only" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:message id="theMessage" name="TheMessage" />
        <bpmn:process id="MessageOnlyStartProcess" isExecutable="true">
          <bpmn:startEvent id="start">
            <bpmn:outgoing>f1</bpmn:outgoing>
            <bpmn:messageEventDefinition id="event" messageRef="theMessage" />
          </bpmn:startEvent>
          <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="end" />
          <bpmn:endEvent id="end">
            <bpmn:incoming>f1</bpmn:incoming>
          </bpmn:endEvent>
        </bpmn:process>
      </bpmn:definitions>
      """;

  @BeforeAll
  static void anEngineHoldingTwoModelsWithoutAPlainStartEvent() {

    final var dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:refused-start;DB_CLOSE_DELAY=-1");
    final var configuration = new StandaloneProcessEngineConfiguration();
    configuration.setDataSource(dataSource);
    configuration.setDatabaseSchemaUpdate("true");
    // nothing here waits for a job: the timer of the model below would be the only one,
    // and the test is about the create command rather than about what runs afterwards
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P180D");
    configuration.setProcessEngineName("refused-start");
    engine = configuration.buildProcessEngine();
    engine
        .getRepositoryService()
        .createDeployment()
        .tenantId(WORKFLOW_MODULE)
        .addString("timer-only.bpmn", TIMER_ONLY)
        .addString("message-only.bpmn", MESSAGE_ONLY)
        .deploy();

  }

  @AfterAll
  static void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  private static Camunda7ProcessService<String> adapter() {

    return new Camunda7ProcessService<>(
        "camunda7", engine.getRuntimeService(), engine.getTaskService(), null, engine
            .getHistoryService(), TestCollaborators.complete());

  }

  @Test
  @DisplayName("A start of a process the engine does not hold is not repeated")
  public void aStartOfAProcessTheEngineDoesNotHoldIsNotRepeated() {

    final var testee = adapter();

    final var refusal = assertThrows(
        Camunda7RefusedStart.class,
        () -> testee.startProcessInstance(WORKFLOW_MODULE, "NothingDeployedThis", "1"));

    // what the engine answered stays readable: it is a NullValueException naming the key
    // and the tenant it looked under
    final var answer = assertInstanceOf(NullValueException.class, refusal.getCause());
    assertTrue(
        answer.getMessage().contains("NothingDeployedThis"),
        "the engine names the process it did not find, but said: "
            + answer.getMessage());

    // and the aggregate whose transaction is already committed stops paying for it: the
    // entry is blocked after this attempt instead of being retried for hours
    assertFalse(testee.isPhaseTwoFailureRepeatable(refusal));
    assertFalse(
        testee.isPhaseTwoFailureRepeatable(new IllegalStateException("dispatching failed", refusal)));

  }

  @Test
  @DisplayName("The engine's own answer stays repeatable where no start produced it")
  public void theSameAnswerFromAnotherOperationStaysRepeatable() {

    // the engine uses this exception wherever something it was asked for is missing, and
    // for an operation on a workflow which exists that is the at-least-once residual
    // rather than a request nobody can carry out. So the verdict hangs on the start
    // having wrapped it, not on the exception
    assertTrue(adapter().isPhaseTwoFailureRepeatable(new NullValueException("task is null")));

  }

  @Test
  @DisplayName("A model whose only start event is a timer or a message is started anyway")
  public void aModelWithoutAPlainStartEventIsStartedAnyway() {

    final var testee = adapter();

    // Camunda 8 refuses both of these with a conflict and calls that refusal permanent.
    // Camunda 7 creates the instance at that start event, so an
    // application which calls startWorkflow on such a model gets a running workflow here
    // and none on a cluster
    assertNotNull(
        testee.startProcessInstance(WORKFLOW_MODULE, "TimerOnlyStartProcess", "1"),
        "Camunda 7 starts a process whose only start event is a timer");
    assertNotNull(
        testee.startProcessInstance(WORKFLOW_MODULE, "MessageOnlyStartProcess", "2"),
        "Camunda 7 starts a process whose only start event is a message");

  }

}
