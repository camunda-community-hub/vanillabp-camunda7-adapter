package io.vanillabp.camunda7.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.RecordingScoping;
import io.vanillabp.integration.adapter.spi.DmnDecisionIds;
import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a running engine does with a business rule task which names its decision in an
 * expression, under {@code use-prefix} and without it.
 * <p>
 * The decision this module deploys carries the prefix, and only the model says which
 * decision a run picks. Both sides go through the very functions the deployment uses, so
 * what the engine resolves here is what an application gets.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7DecisionByExpressionTest {

  private static final String MODULE = "loan-approval";

  private static final String ADAPTER_ID = "c7";

  private static final String PROCESS_ID = "LoanApproval";

  private static final String DECISION_ID = "creditRating";

  private static final String A_MODEL_NAMING_ITS_DECISION_IN_AN_EXPRESSION = """
      <?xml version="1.0" encoding="UTF-8"?>
      <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs_LoanApproval" targetNamespace="http://bpmn.io/schema/bpmn">
        <bpmn:process id="LoanApproval" isExecutable="true">
          <bpmn:startEvent id="start"><bpmn:outgoing>toRating</bpmn:outgoing></bpmn:startEvent>
          <bpmn:sequenceFlow id="toRating" sourceRef="start" targetRef="rating" />
          <bpmn:businessRuleTask id="rating" camunda:decisionRef="${whichDecision}" camunda:resultVariable="rating" camunda:mapDecisionResult="singleEntry">
            <bpmn:incoming>toRating</bpmn:incoming><bpmn:outgoing>toEnd</bpmn:outgoing>
          </bpmn:businessRuleTask>
          <bpmn:sequenceFlow id="toEnd" sourceRef="rating" targetRef="end" />
          <bpmn:endEvent id="end"><bpmn:incoming>toEnd</bpmn:incoming></bpmn:endEvent>
        </bpmn:process>
      </bpmn:definitions>
      """;

  private static final String A_DECISION = """
      <?xml version="1.0" encoding="UTF-8"?>
      <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="creditRatingDefinitions" name="Credit rating" namespace="http://vanillabp.io/c7-test">
        <decision id="creditRating" name="Credit rating">
          <decisionTable id="creditRatingTable" hitPolicy="UNIQUE">
            <input id="creditRatingInput" label="Approved">
              <inputExpression id="creditRatingInputExpression" typeRef="boolean">
                <text>approved</text>
              </inputExpression>
            </input>
            <output id="creditRatingOutput" label="Rating" name="rating" typeRef="string" />
            <rule id="approvedRule">
              <inputEntry id="approvedInput"><text>true</text></inputEntry>
              <outputEntry id="approvedOutput"><text>"A"</text></outputEntry>
            </rule>
          </decisionTable>
        </decision>
      </definitions>
      """;

  private ProcessEngine engine;

  @BeforeEach
  public void bootTheEngine() {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration
        .setJdbcUrl("jdbc:h2:mem:decision-by-expression-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    // the engine refuses to parse a model without one, and a test needs no cleanup policy
    configuration.setHistoryTimeToLive("P30D");
    // the parse listener of this adapter, because it is what makes a business rule task run
    // in a job of its own: the decision is looked up there and not while the workflow starts.
    // No task of this model is served by the application, so the registry stays empty
    final var taskRegistry = new Camunda7TaskRegistry();
    final var invoker = mock(io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker.class);
    configuration
        .setCustomPostBPMNParseListeners(
            // a list the engine appends its own listeners to, so an immutable one breaks it
            new java.util.ArrayList<>(
                java.util.List
                    .of(
                        new Camunda7AsyncBpmnParseListener(
                            new Camunda7TaskCancellationListener(invoker, taskRegistry), new Camunda7UserTaskEventListener(invoker, taskRegistry)))));
    engine = configuration.buildProcessEngine();

  }

  @AfterEach
  public void closeTheEngine() {

    if (engine != null) {
      engine.close();
    }

  }

  /**
   * Deploys the model and the decision the way the adapter does, scoping both through the
   * core, and runs the process up to its end.
   *
   * @param mode The name-clash avoidance the workflow module runs in
   * @return The value the business rule task wrote, read from the history
   */
  private Object whatTheDecisionYielded(
      final NameClashAvoidance mode) {

    final var scoping = new RecordingScoping(mode);
    final var model = Bpmn
        .readModelFromStream(
            new java.io.ByteArrayInputStream(
                A_MODEL_NAMING_ITS_DECISION_IN_AN_EXPRESSION.getBytes(StandardCharsets.UTF_8)));
    Camunda7Scoping.apply(model, MODULE, ADAPTER_ID, scoping);
    final var decision = DmnDecisionIds
        .rewrite(
            A_DECISION.getBytes(StandardCharsets.UTF_8),
            id -> scoping.scopedIdentifier(MODULE, id, ADAPTER_ID));
    engine
        .getRepositoryService()
        .createDeployment()
        .name(MODULE)
        .addString("loan-approval.bpmn", Bpmn.convertToString(model))
        .addString("credit-rating.dmn", new String(decision, StandardCharsets.UTF_8))
        .deploy();

    // the model is deployed under the scoped process id, while the decision the run picks is
    // named the way the application knows it: the variable carries the plain id
    final var instance = engine
        .getRuntimeService()
        .startProcessInstanceByKey(
            scoping.scopedProcessId(MODULE, PROCESS_ID, ADAPTER_ID),
            java.util.Map.of("approved", true, "whichDecision", DECISION_ID));
    runEveryJobUntilTheWorkflowEnds();

    return engine
        .getHistoryService()
        .createHistoricVariableInstanceQuery()
        .processInstanceId(instance.getId())
        .variableName("rating")
        .singleResult()
        .getValue();

  }

  /**
   * Does what the job executor would do, one job at a time, so a job which fails says so
   * here instead of being retried in a thread of its own.
   */
  private void runEveryJobUntilTheWorkflowEnds() {

    for (var job = engine.getManagementService().createJobQuery().singleResult(); job != null; job = engine
        .getManagementService()
        .createJobQuery()
        .singleResult()) {
      engine.getManagementService().executeJob(job.getId());
    }

  }

  @Test
  @DisplayName("Without a prefix the engine finds the decision the expression names")
  public void withoutAPrefixTheDecisionIsFound() {

    assertEquals(
        "A",
        whatTheDecisionYielded(NameClashAvoidance.NONE),
        "nothing is rewritten in this mode, so the expression names the decision as deployed");

  }

  @Test
  @DisplayName("Under use-prefix the engine finds it too, because the prefix is in front of the expression")
  public void underUsePrefixTheDecisionIsFoundAsWell() {

    // the attribute reaches the engine as 'loan-approval__${whichDecision}', and Camunda 7
    // evaluates it as one expression: the text in front stays text and the prefixed id of
    // whatever the expression yields is looked up. Leaving the expression alone made the job
    // of this task fail with "no decision definition deployed with key 'creditRating' and
    // tenant-id 'null'", which the engine turns into an incident once the retries are used up
    assertEquals(
        "A",
        whatTheDecisionYielded(NameClashAvoidance.USE_PREFIX),
        "the decision of this module is deployed under the prefixed id, so that is the id the "
            + "business rule task has to resolve to");

  }

}
