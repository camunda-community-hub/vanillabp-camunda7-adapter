package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.camunda.bpm.engine.ParseException;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What Camunda 7 itself does with a message start event whose name is an expression,
 * recorded here without VanillaBP in the way. The answer decides what the adapter's check
 * before a start by message has to expect.
 * <p>
 * The engine refuses to deploy such a model. The expression below needs no variables, so
 * the engine could resolve it, and still the deployment fails. So no correlation can ever
 * reach a process whose message start event is named by an expression, whether it names
 * the process definition or not. Measured on Camunda 7.24.0. The refusal is
 * {@code BpmnParse#ensureNoExpressionInMessageStartEvent}, which the engine runs for every
 * message start event of a process.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7MessageNamedByExpressionTest {

  private static final String REFUSED_PROCESS = "StartedByAnExpression";

  private static final String PLAIN_PROCESS = "StartedByAPlainName";

  private static ProcessEngine engine;

  @BeforeAll
  public static void bootAnEngineWithoutVanillaBp() {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setJdbcUrl("jdbc:h2:mem:message-named-by-expression;DB_CLOSE_DELAY=-1");
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P180D");
    configuration.setProcessEngineName("message-named-by-expression");
    engine = configuration.buildProcessEngine();

  }

  @AfterAll
  public static void closeTheEngine() {

    engine.close();

  }

  private static BpmnModelInstance startedByTheMessage(
      final String processId,
      final String messageName) {

    return Bpmn
        .createExecutableProcess(processId)
        .startEvent("Started")
        .message(messageName)
        .userTask("Waiting")
        .endEvent()
        .done();

  }

  private static void deploy(
      final BpmnModelInstance model) {

    engine
        .getRepositoryService()
        .createDeployment()
        .addModelInstance("process.bpmn", model)
        .deploy();

  }

  @Test
  @DisplayName("The engine refuses to deploy a message start event named by an expression")
  public void anExpressionAsTheNameIsRefusedAtDeployment() {

    final var refusal = assertThrows(
        ParseException.class,
        () -> deploy(startedByTheMessage(REFUSED_PROCESS, "${'Order'.concat('Placed')}")));

    assertTrue(
        refusal.getMessage().contains("expressions in the message start event name are not allowed"),
        refusal.getMessage());
    assertEquals(
        0,
        engine.getRepositoryService().createProcessDefinitionQuery().processDefinitionKey(REFUSED_PROCESS).count(),
        "the refused model must leave no process definition behind");

  }

  /**
   * The same model with a plain name deploys and starts, also by a correlation naming the
   * process definition. So the refusal above is about the expression and nothing else.
   */
  @Test
  @DisplayName("The same model with a plain name deploys and starts by a correlation naming the definition")
  public void aPlainNameDeploysAndStarts() {

    deploy(startedByTheMessage(PLAIN_PROCESS, "OrderPlaced"));
    final var definitionId = engine
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(PLAIN_PROCESS)
        .latestVersion()
        .singleResult()
        .getId();

    engine
        .getRuntimeService()
        .createMessageCorrelation("OrderPlaced")
        .processDefinitionId(definitionId)
        .correlateStartMessage();

    assertEquals(
        1,
        engine.getRuntimeService().createProcessInstanceQuery().processDefinitionId(definitionId).count());

  }

}
