package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ManagementService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.runtime.ProcessInstance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;

/**
 * What a Camunda 7 model reads when the sync model has flattened the value it navigates
 * into a map of process variables. And what the engine does with the answer at every place
 * where a model can put an expression.
 * <p>
 * <b>This class pins what the code does today, not what it should do.</b> Several cases
 * assert a silence which nobody wants. The silence belongs to the engine, and no adapter
 * can talk it out of it. The suite is good for one thing. A change to the sync model, to
 * the variables which the adapter writes, or to the engine version shows up here as a
 * failing case which names the expression. It does not show up as a workflow which silently
 * takes the wrong branch in the application of somebody.
 * <p>
 * Where the silence belongs to the ENGINE and no adapter can change it, the finding moved
 * to the startup check, away from the runtime. Those cases still assert the silence here.
 * {@code Camunda7UnsharedExpressionCheckIT} asserts that the boot named the expression
 * which will be quiet.
 * <p>
 * There is one subclass per serialization world, because the format is engine configuration
 * and so needs a Spring context of its own. There is no third subclass for
 * {@code application/xstream}. The format needs the artifact
 * {@code org.camunda:camunda-xstream}, and no repository publishes it. So that world cannot
 * be booted at all.
 * <p>
 * The cases which read a value without a model of their own evaluate it through the own
 * {@code ExpressionManager} of the engine against a real execution (see
 * {@link ExprEvaluator}). The cases which are about what Camunda 7 does with the RESULT run
 * a BPMN process, with one parallel branch per case.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractNestedExpressionsIT {

  protected static final String MODULE_ID = "c7-it";

  /**
   * How long the engine is given to finish what a start set going. Every branch of these
   * models is asynchronous. A failing branch is retried three times before it becomes an
   * incident. And the models run more than a dozen branches at once.
   */
  private static final long ENGINE_TIMEOUT_MS = 60_000;

  private static final long POLL_INTERVAL_MS = 100;

  @Autowired
  protected ProcessEngine processEngine;

  @Autowired
  protected RuntimeService runtimeService;

  @Autowired
  protected TransactionTemplate transactionTemplate;

  @Autowired
  private DataSource dataSource;

  /**
   * What the cases about a start which never reached the engine ask of the phase-two
   * outbox. It comes from the test tools of the platform, so the name of the table and the
   * values of its state column stay out of this class.
   * <p>
   * This application runs the outbox table which VanillaBP writes itself. So the reader is
   * told which table to read, instead of looking for the one which is there.
   */
  private PhaseTwoOutboxReader outbox;

  @Autowired
  protected ExprConditionsWorkflowService conditionsService;

  @Autowired
  protected ExprPlacementsWorkflowService placementsService;

  @Autowired
  protected ExprEventSubWorkflowService eventSubService;

  @Autowired
  protected ExprEventSubQuietWorkflowService eventSubQuietService;

  /**
   * Read directly, because the case about a start which never reached the engine has to
   * show that the own data of the caller did.
   */
  @Autowired
  protected ExprEventSubRepository eventSubRepository;

  /**
   * The engine services which the adapter does not publish as beans are taken from the
   * engine itself.
   */
  protected HistoryService historyService;

  protected ManagementService managementService;

  /**
   * The workflow which every case that reads a value shares. Whichever test needs it first
   * starts it. It parks one branch at a timer for an hour, and an expression is evaluated
   * at that execution. Sharing it keeps one engine run instead of one per case. The lazy
   * start keeps the tests independent of the order in which they run.
   */
  private ProcessInstance conditionsWorkflow;

  private String parkedExecutionId;

  @BeforeEach
  void takeTheEngineServices() {

    historyService = processEngine.getHistoryService();
    managementService = processEngine.getManagementService();

  }

  @BeforeEach
  void takeTheOutbox() {

    outbox = PhaseTwoOutboxReader.ofTheVanillaBpOutbox(dataSource);

  }

  /**
   * @return What the nested {@link BigDecimal} of the workflow aggregate comes back as
   *         once this world's serializer wrote and read it
   */
  protected abstract Class<?> theClassOfTheNestedBigDecimal();

  /**
   * @return The text {@code ${order.total.toString()}} answers in this world
   */
  protected abstract String theTextOfTheNestedBigDecimal();

  /**
   * Asserts what {@code ${order.total.scale() > 0}} does in this world. A
   * {@link BigDecimal} has that method and a {@link Double} has not. This is the one
   * expression whose verdict the serialization format decides.
   */
  protected abstract void assertWhatTheNestedNumberAnswersToScale();

  /**
   * @return What the TOP-LEVEL {@link BigDecimal} of the workflow aggregate comes back as
   *         in this world
   */
  protected abstract Class<?> theClassOfTheTopLevelBigDecimal();

  /**
   * @return The text {@code ${total.toString()}} answers in this world
   */
  protected abstract String theTextOfTheTopLevelBigDecimal();

  /**
   * Asserts what {@code ${total.scale() > 0}} does in this world. It is the same question
   * one level up.
   */
  protected abstract void assertWhatTheTopLevelNumberAnswersToScale();

  // ------------------------------------------------ what the engine holds after a start

  @Test
  @DisplayName("The engine holds the aggregate as a map of flat variables, without what is not shared")
  void theEngineHoldsTheAggregateAsAMapOfFlatVariables() {

    final var workflow = theConditionsWorkflow();

    final var order = deserializedVariable(workflow, "order");
    assertTrue(order instanceof Map, () -> "'order' came back as "
        + order);
    final var members = (Map<?, ?>) order;
    assertTrue(
        members
            .keySet()
            .containsAll(
                List.of("customer", "dueAt", "dueDate", "express", "itemCount", "items", "reference", "status",
                    "total")),
        () -> "the flattened order lost a member: "
            + members.keySet());
    assertFalse(
        members.containsKey("internalCode"),
        "'internalCode' carries @NoSyncWithBPMS, so the map must not hold it");

    // an enum and a temporal reach the engine as their text. That makes a method call on
    // them fail further down.
    assertEquals("OPEN", members.get("status"));
    assertEquals("2027-03-04", members.get("dueDate"));
    assertEquals("2027-03-04T05:06:07", members.get("dueAt"));

    assertEquals(
        theClassOfTheNestedBigDecimal(),
        members
            .get("total")
            .getClass(),
        "the class of a nested BigDecimal is what this world's serializer made of it");

    // the aggregate has no variable for what it does not share. So only the migration
    // fallback of the EL resolver answers 'hiddenOrder'.
    assertNull(
        runtimeService.getVariable(workflow.getProcessInstanceId(), "hiddenOrder"),
        "an attribute carrying @NoSyncWithBPMS must not become a process variable");

  }

  // ------------------------------------------------------- what keeps working unchanged

  @Test
  @DisplayName("Navigating the flattened value, and calling methods on what kept its class, keeps working")
  void navigatingTheFlattenedValueKeepsWorking() {

    assertEquals(Boolean.TRUE, valueOf("${order.customer.vip}"), "plain two-level navigation");
    assertEquals(Boolean.TRUE, valueOf("${order.customer.name.length() > 0}"), "a method on a nested string");
    assertEquals("REF-1", valueOf("${order.reference.toUpperCase()}"), "a method on a nested string");
    assertEquals(Boolean.TRUE, valueOf("${order.total.doubleValue() > 100}"), "a method every number has");
    assertEquals(Boolean.TRUE, valueOf("${total.doubleValue() > 100}"), "the same on the top-level number");
    assertEquals(Boolean.TRUE, valueOf("${order.items.size() > 2}"), "a collection stays a collection");
    assertEquals(Boolean.TRUE, valueOf("${order.items[0].price > 10}"), "indexed access into that collection");
    assertEquals(Boolean.TRUE, valueOf("${order.itemCount > 2}"), "a computed value read as a key");
    assertEquals(Boolean.TRUE, valueOf("${order.status == 'OPEN'}"), "the enum's text against the literal");
    assertEquals(Boolean.TRUE, valueOf("${order.dueDate == '2027-03-04'}"), "the date's text against the literal");
    assertEquals("2020-01-02T03:04:05", valueOf("${order.customer.since}"), "a nested temporal, as its text");
    assertEquals("2027-03-04T05:06:07", valueOf("${dueAt}"), "a top-level temporal, as its text");

    // flattening also gives something away. An enum answers the String API now, which it
    // could not while it was an enum.
    assertEquals(Boolean.TRUE, valueOf("${order.status.length() > 0}"), "a String method on what used to be an enum");

  }

  // ----------------------------------------------------------- what flattening takes away

  @Test
  @DisplayName("A method the flattened value has not got throws, and the message names the class it looked at")
  void aMethodTheFlattenedValueHasNotGotThrows() {

    assertTrue(
        failureOf("${order.getTotal()}").contains("Method not found: class java.util.LinkedHashMap.getTotal()"),
        failureOf("${order.getTotal()}"));

    // the map DOES carry the value under the key 'itemCount'. Only the getter form fails,
    // and a version-1 model most likely has that shape.
    assertTrue(
        failureOf("${order.getItemCount() > 2}")
            .contains("Method not found: class java.util.LinkedHashMap.getItemCount()"),
        failureOf("${order.getItemCount() > 2}"));

    assertTrue(
        failureOf("${order.status.name()}").contains("Method not found: class java.lang.String.name()"),
        failureOf("${order.status.name()}"));
    assertTrue(
        failureOf("${order.dueDate.isAfter(order.dueDate)}").contains("Method not found: class java.lang.String"),
        failureOf("${order.dueDate.isAfter(order.dueDate)}"));

    // a PROPERTY of a flattened temporal fails in another way. The difference decides
    // whether a conditional event further down is loud or silent.
    assertTrue(
        failureOf("${order.dueDate.year}").contains("does not have the property 'year'"),
        failureOf("${order.dueDate.year}"));

  }

  @Test
  @DisplayName("A key the map has not got is null rather than an error")
  void aKeyTheMapHasNotGotIsNullRatherThanAnError() {

    assertNull(valueOf("${order.internalCode}"), "an unshared nested attribute is simply absent");
    assertNull(valueOf("${order.customer.address.city}"), "and so is everything below an absent object");

    // this is the whole danger of the flattening. The comparison is false, not broken. So a
    // gateway takes its default flow, which the C08 case below reads.
    assertEquals(Boolean.FALSE, valueOf("${order.internalCode == 'IC-9'}"));

    // that nothing is logged about such a name is the other half of the finding. It is NOT
    // read from the captured output, on purpose. The placements model raises incidents
    // which name the same attribute, and their retries run on threads of the job executor.
    // An assertion about an empty log would then measure which case happened to hold the
    // capture. What silence means in operation is asserted where it can be seen: on the
    // branch which takes its default flow without an incident.

  }

  // ------------------------------------------------------------- the top-level BigDecimal

  @Test
  @DisplayName("A top-level BigDecimal keeps its class, and what it renders as is the format's answer")
  void aTopLevelBigDecimalKeepsItsClass() {

    // Camunda 7 has no variable type for a BigDecimal. So the value keeps its class in an
    // object variable, and the configured format decides what it renders as. That is the
    // version-1 answer: the model reads the value which the application holds, not a double
    // somebody widened it to on the way in.
    assertEquals(theTextOfTheTopLevelBigDecimal(), valueOf("${total.toString()}"));
    assertEquals(theClassOfTheTopLevelBigDecimal(), valueOf("${total}").getClass());

    // and this is what the same expression answered under version 1. It is read here
    // through the migration fallback, which reaches the own BigDecimal of the aggregate.
    assertEquals("120.50", valueOf("${hiddenOrder.total.toString()}"));

    assertWhatTheTopLevelNumberAnswersToScale();

  }

  @Test
  @DisplayName("The nested BigDecimal is whatever the serialization format made of it")
  void theNestedBigDecimalIsWhateverTheSerializationFormatMadeOfIt() {

    // this is the answer which the startup check reports while the application boots. A
    // nested value travels inside the map which the sync model built. So a format which has
    // one number type hands back a Double where the aggregate holds a BigDecimal, and no
    // expression says where that came from. Camunda7LossyFormatCheckIT reads the side of
    // the boot.
    assertEquals(theTextOfTheNestedBigDecimal(), valueOf("${order.total.toString()}"));
    assertWhatTheNestedNumberAnswersToScale();

  }

  // ------------------------------------------------------------- the migration fallback

  @Test
  @DisplayName("The migration fallback keeps the whole version-1 grammar alive for an unshared top-level name")
  void theMigrationFallbackKeepsTheVersionOneGrammarAlive() {

    // 'hiddenOrder' is no process variable. So the EL resolver reads the live aggregate,
    // and the expression meets the object graph itself, not a map. Everything which a
    // version-1 model could do works here. All of it stops working once the fallback is
    // removed.
    assertEquals("120.50", valueOf("${hiddenOrder.getTotal()}").toString(), "the getter call of version 1");
    assertEquals(2027, valueOf("${hiddenOrder.dueDate.year}"), "a property of a live temporal");
    assertEquals("IC-9", valueOf("${hiddenOrder.internalCode}"), "an attribute unshared at both levels");
    assertEquals(Boolean.TRUE, valueOf("${hiddenOrder.status == 'OPEN'}"), "a live enum against a literal");

  }

  // ---------------------------------------------------------- the sequence flow condition

  @Test
  @DisplayName("What an exclusive gateway does with each answer")
  void whatAnExclusiveGatewayDoesWithEachAnswer() {

    final var workflow = theConditionsWorkflow();

    // a value is a value, so the conditional flow is taken
    awaitActivityReached(workflow, "EC_True_C01");
    // and so it is for a name the migration fallback answers
    awaitActivityReached(workflow, "EC_True_C10");

    // a method which the base has not got fails the job. After its retries, an incident
    // names the expression and the class it looked at.
    assertTrue(
        awaitIncidentAt(workflow, "EC_Gw_C02").contains("Unknown method used in expression"),
        awaitIncidentAt(workflow, "EC_Gw_C02"));
    // a property which a base that is not null has not got is just as loud HERE. That is
    // the difference to a conditional event further down.
    assertTrue(
        awaitIncidentAt(workflow, "EC_Gw_C07").contains("Unknown property used in expression"),
        awaitIncidentAt(workflow, "EC_Gw_C07"));

    // C08 reads an unshared nested attribute, and C21 navigates into an absent nested
    // object. Both are null, both comparisons are false, and the gateway takes its default
    // flow without a word. The RUNTIME is silent here and stays that way. That is why the
    // startup check now reads the whole path and names both of these while the application
    // boots (Camunda7UnsharedExpressionCheckIT).
    awaitActivityReached(workflow, "EC_False_C08");
    awaitActivityReached(workflow, "EC_False_C21");
    assertNoIncidentAt(workflow, "EC_Gw_C08");
    assertNoIncidentAt(workflow, "EC_Gw_C21");

    // without a default flow, the same false answer is loud, because the engine has nowhere
    // to send the token
    assertTrue(
        awaitIncidentAt(workflow, "EC_Gw_C19").contains("ENGINE-02004"),
        awaitIncidentAt(workflow, "EC_Gw_C19"));
    assertTrue(
        awaitIncidentAt(workflow, "EC_Gw_C20").contains("Unknown method used in expression"),
        awaitIncidentAt(workflow, "EC_Gw_C20"));

  }

  // ------------------------------------------------------------------ the other placements

  @Test
  @DisplayName("What a conditional event, a timer and a multi-workflow element do with the same answers")
  void whatTheOtherPlacementsDoWithTheSameAnswers(
      final CapturedOutput output) {

    // one workflow for all three placements, because they are one engine run. And the
    // report of a live read below belongs to the run which produced it. A case which
    // asserts a log line has to be the case which caused it.
    final var workflow = startAndAwait("ExprPlacements", () -> {
      final var aggregate = new ExprPlacementsAggregate();
      aggregate.fillWithTheSample();
      return placementsService
          .start(aggregate)
          .getId();
    });

    whatAConditionalEventDidWith(workflow);
    whatATimerDidWith(workflow);
    whatAMultiInstanceElementDidWith(workflow);

    // the fallback says once per name and process that it answered. The timer of P08 is the
    // only expression of this model which reads a name the aggregate does not share.
    assertTrue(
        output
            .getAll()
            .contains("MIGRATION FALLBACK"),
        "reading 'hiddenOrder' live has to be reported, because the fallback will be removed");
    assertTrue(
        output
            .getAll()
            .contains("ExprPlacements"),
        "the report of a live read names the BPMN process it happened in");

  }

  private void whatAConditionalEventDidWith(
      final ProcessInstance workflow) {

    // one which evaluates fires on entry
    awaitActivityReached(workflow, "EX_End_P04");
    // and one whose method call throws is as loud as a gateway
    assertTrue(
        awaitIncidentAt(workflow, "EX_P01").contains("Method not found"),
        awaitIncidentAt(workflow, "EX_P01"));
    // reading a null as the condition ITSELF is loud too, because a condition may not be
    // null, even where being false is fine
    assertTrue(
        awaitIncidentAt(workflow, "EX_P19").contains("condition expression returns null"),
        awaitIncidentAt(workflow, "EX_P19"));

    // Every conditional event behaviour of Camunda 7 evaluates through
    // UelExpressionCondition.tryEvaluate. That method answers false for a
    // property-not-found, instead of failing. So P03 is silent here, while the very same
    // expression is an incident on a gateway. P02, whose key is simply absent, is silent
    // for the older reason. Both events were entered and wait for a condition which can
    // never become true, and neither will ever say so. This is the behaviour of the engine,
    // and no adapter can change it. So the startup check names both instead. It reads the
    // condition of a conditional event and the whole path it navigates
    // (Camunda7UnsharedExpressionCheckIT).
    awaitWaitingAt(workflow, "EX_P03");
    awaitWaitingAt(workflow, "EX_P02");
    assertNoIncidentAt(workflow, "EX_P03");
    assertNoIncidentAt(workflow, "EX_P02");
    assertActivityNeverReached(workflow, "EX_End_P03");
    assertActivityNeverReached(workflow, "EX_End_P02");

  }

  private void whatATimerDidWith(
      final ProcessInstance workflow) {

    // a timer refuses what a gateway coerces. An absent key and a live temporal both say
    // what the placement wanted, instead of quietly becoming false.
    assertTrue(
        awaitIncidentAt(workflow, "EX_P06").contains("valid duration/time"),
        awaitIncidentAt(workflow, "EX_P06"));
    assertTrue(
        awaitIncidentAt(workflow, "EX_P08").contains("valid duration/time"),
        awaitIncidentAt(workflow, "EX_P08"));

    // and the flattening REPAIRS this one. The same temporal as text is a legal timeDate,
    // while the live object of P08 above is not. A version-1 model whose timer read a
    // temporal attribute was broken, and it starts working on this version.
    awaitWaitingAt(workflow, "EX_P07");
    assertNoIncidentAt(workflow, "EX_P07");

  }

  private void whatAMultiInstanceElementDidWith(
      final ProcessInstance workflow) {

    // navigation into the flattened collection works: three items, three instances
    awaitExecutionsWaitingAt(workflow, "EX_P11_w", 3);
    awaitExecutionsWaitingAt(workflow, "EX_P14_w", 3);

    // a cardinality and a collection refuse a null, and say what they wanted. The incidents
    // land on a generated id of the multi-instance body, so the message tells the two cases
    // apart.
    assertTrue(
        awaitIncidentSaying(workflow, "ENGINE-02027").contains("needs to be a number"),
        awaitIncidentSaying(workflow, "ENGINE-02027"));
    assertTrue(
        awaitIncidentSaying(workflow, "ENGINE-02024").contains("didn't resolve to type 'Collection'"),
        awaitIncidentSaying(workflow, "ENGINE-02024"));

    // A completion condition which is always false lets the multi-instance element run to
    // its natural end. So the branch looks exactly like a branch which did what it was
    // told. The startup check reads that path now. Where a conditional event of the model
    // also reads the same path, the path is reported there, because a developer has the
    // least chance of noticing that placement.
    awaitActivityReached(workflow, "EX_End_P16");
    assertNoIncidentAt(workflow, "EX_P16");

  }

  // ------------------------------ the conditional start event of an event subprocess

  @Test
  @DisplayName("A conditional start event of an event subprocess which throws blocks the start after one attempt")
  void aConditionalStartEventWhichThrowsBlocksTheStartAfterOneAttempt() {

    // The condition of a conditional start event is evaluated while the process instance is
    // created, and phase two creates the instance. So the failure lands in the outbox and
    // not in the engine, and the application never hears of it. startWorkflow returned, its
    // transaction committed, and the workflow does not exist.
    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new ExprEventSubAggregate();
      aggregate.fillWithTheSample();
      return eventSubService
          .start(aggregate)
          .getId();
    });

    assertNotNull(aggregateId, "startWorkflow returned the aggregate it persisted");
    final var aggregateIsCommitted = transactionTemplate
        .execute(status -> eventSubRepository.findById(aggregateId).isPresent());
    assertTrue(
        aggregateIsCommitted,
        "the caller's business data is committed, which is the whole point of starting in two phases");

    // An expression which the engine cannot evaluate answers the same way however often it
    // is asked, and the adapter says so
    // (Camunda7ProcessService#isPhaseTwoFailureRepeatable). So the entry is put aside where
    // an operator can find it, instead of walking up to
    // 'vanillabp.outbox.block-after-attempts' over the next hours.
    awaitBlockedOutboxEntry("ExprEventSub");
    assertEquals(
        1,
        outboxAttemptsOf("ExprEventSub"),
        "one attempt is what a verdict of 'repeating cannot fix this' is worth");

    assertNull(
        runningWorkflowOf("ExprEventSub", aggregateId),
        "the workflow was never created, so there is nothing an operator could look at in the engine");
    assertNull(
        historyService
            .createHistoricProcessInstanceQuery()
            .processDefinitionKey("ExprEventSub")
            .singleResult(),
        "and the history knows nothing about it either");

    // a blocked entry is never attempted again, so leaving it would cost nothing. It goes
    // anyway, so this case leaves the context the way it found it.
    outbox.removeEntriesOf("ExprEventSub");

  }

  @Test
  @DisplayName("A conditional start event of an event subprocess which is false never triggers")
  void aConditionalStartEventWhichIsFalseNeverTriggers() {

    final var workflow = startAndAwait("ExprEventSubQuiet", () -> {
      final var aggregate = new ExprEventSubQuietAggregate();
      aggregate.fillWithTheSample();
      return eventSubQuietService
          .start(aggregate)
          .getId();
    });

    // The condition reads an unshared nested attribute. It is false and stays false, so the
    // event subprocess never runs. The workflow itself looks healthy. That makes this the
    // hardest shape to find at runtime. It is the reason why the startup check names
    // 'EQ_SubStart' while the application boots (Camunda7UnsharedExpressionCheckIT).
    awaitWaitingAt(workflow, "EQ_Park");
    assertNoIncidentAt(workflow, "EQ_Park");
    assertActivityNeverReached(workflow, "EQ_SubStart");

  }

  // --------------------------------------------------------------------------- machinery

  /**
   * Starts the workflow which every reading case shares, on first use.
   *
   * @return The running workflow
   */
  protected synchronized ProcessInstance theConditionsWorkflow() {

    if (conditionsWorkflow == null) {
      conditionsWorkflow = startAndAwait("ExprConditions", () -> {
        final var aggregate = new ExprConditionsAggregate();
        aggregate.fillWithTheSample();
        return conditionsService
            .start(aggregate)
            .getId();
      });
      parkedExecutionId = AwaitPhaseTwo
          .untilAvailable(
              () -> runtimeService
                  .createExecutionQuery()
                  .processInstanceId(conditionsWorkflow.getProcessInstanceId())
                  .activityId("EC_Park")
                  .singleResult()
                  .getId(),
              "a branch of 'ExprConditions' has to park at 'EC_Park'");
    }
    return conditionsWorkflow;

  }

  /**
   * @param expression What to evaluate
   * @return What the expression answered, the test failing where it threw instead
   */
  protected Object valueOf(
      final String expression) {

    theConditionsWorkflow();
    final var outcome = ExprEvaluator.evaluate(processEngine, parkedExecutionId, expression);
    if (outcome.failure() != null) {
      fail("'%s' was expected to evaluate and threw %s"
          .formatted(expression, ExprEvaluator.chainOf(outcome.failure())));
    }
    return outcome.value();

  }

  /**
   * @param expression What to evaluate
   * @return The whole cause chain of what it threw, the test failing where it evaluated
   */
  protected String failureOf(
      final String expression) {

    theConditionsWorkflow();
    final var outcome = ExprEvaluator.evaluate(processEngine, parkedExecutionId, expression);
    if (outcome.failure() == null) {
      fail("'%s' was expected to throw and answered %s".formatted(expression, outcome.value()));
    }
    return ExprEvaluator.chainOf(outcome.failure());

  }

  private Object deserializedVariable(
      final ProcessInstance workflow,
      final String name) {

    return runtimeService.getVariable(workflow.getProcessInstanceId(), name);

  }

  private ProcessInstance startAndAwait(
      final String bpmnProcessId,
      final Supplier<Object> start) {

    final var aggregateId = transactionTemplate.execute(status -> start.get());
    return AwaitPhaseTwo
        .untilAvailable(
            () -> runningWorkflowOf(bpmnProcessId, aggregateId),
            "the workflow '%s' of aggregate %s has to reach the engine".formatted(bpmnProcessId, aggregateId));

  }

  protected ProcessInstance runningWorkflowOf(
      final String bpmnProcessId,
      final Object aggregateId) {

    return runtimeService
        .createProcessInstanceQuery()
        .processDefinitionKey(bpmnProcessId)
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .tenantIdIn(MODULE_ID)
        .singleResult();

  }

  /**
   * Waits until an activity was reached. That proves that a branch went the way the case
   * expects.
   */
  protected void awaitActivityReached(
      final ProcessInstance workflow,
      final String activityId) {

    awaitEngine(
        () -> !historyService
            .createHistoricActivityInstanceQuery()
            .processInstanceId(workflow.getProcessInstanceId())
            .activityId(activityId)
            .list()
            .isEmpty(),
        "'%s' has to be reached".formatted(activityId));

  }

  /**
   * The counterpart of {@link #awaitActivityReached}. It is read AFTER the branch it
   * belongs to settled, so that "not yet" and "never" cannot be confused.
   */
  protected void assertActivityNeverReached(
      final ProcessInstance workflow,
      final String activityId) {

    assertTrue(
        historyService
            .createHistoricActivityInstanceQuery()
            .processInstanceId(workflow.getProcessInstanceId())
            .activityId(activityId)
            .list()
            .isEmpty(),
        "'%s' was reached, although the case is about a branch which never gets there".formatted(activityId));

  }

  /**
   * Waits until an execution sits at an activity and stays there. That is, until the job
   * which carried it in was executed and left it waiting.
   */
  protected void awaitWaitingAt(
      final ProcessInstance workflow,
      final String activityId) {

    awaitEngine(
        () -> executionsAt(workflow, activityId) > 0,
        "an execution has to wait at '%s'".formatted(activityId));

  }

  protected void awaitExecutionsWaitingAt(
      final ProcessInstance workflow,
      final String activityId,
      final int expected) {

    awaitEngine(
        () -> executionsAt(workflow, activityId) == expected,
        "%d executions have to wait at '%s'".formatted(expected, activityId));

  }

  private long executionsAt(
      final ProcessInstance workflow,
      final String activityId) {

    return runtimeService
        .createExecutionQuery()
        .processInstanceId(workflow.getProcessInstanceId())
        .activityId(activityId)
        .count();

  }

  /**
   * Waits for the incident of one activity and answers its message. So a case can say what
   * the engine told the operator.
   */
  protected String awaitIncidentAt(
      final ProcessInstance workflow,
      final String activityId) {

    awaitEngine(
        () -> incidentAt(workflow, activityId).isPresent(),
        "'%s' has to raise an incident".formatted(activityId));
    return incidentAt(workflow, activityId).orElseThrow();

  }

  /**
   * The same for a case whose incident lands on a generated activity id, for example the
   * multi-instance body of a subprocess. The message identifies it.
   */
  protected String awaitIncidentSaying(
      final ProcessInstance workflow,
      final String fragment) {

    awaitEngine(
        () -> incidentSaying(workflow, fragment).isPresent(),
        "an incident saying '%s' has to be raised".formatted(fragment));
    return incidentSaying(workflow, fragment).orElseThrow();

  }

  /**
   * Read AFTER the branch settled, for the same reason as
   * {@link #assertActivityNeverReached}.
   */
  protected void assertNoIncidentAt(
      final ProcessInstance workflow,
      final String activityId) {

    final var incident = incidentAt(workflow, activityId);
    assertTrue(
        incident.isEmpty(),
        () -> "'%s' raised an incident, although the case is about a branch which fails silently: %s"
            .formatted(activityId, incident.orElse("")));

  }

  private Optional<String> incidentAt(
      final ProcessInstance workflow,
      final String activityId) {

    return runtimeService
        .createIncidentQuery()
        .processInstanceId(workflow.getProcessInstanceId())
        .activityId(activityId)
        .list()
        .stream()
        .map(incident -> ExprEvaluator.oneLine(incident.getIncidentMessage()))
        .findFirst();

  }

  private Optional<String> incidentSaying(
      final ProcessInstance workflow,
      final String fragment) {

    return runtimeService
        .createIncidentQuery()
        .processInstanceId(workflow.getProcessInstanceId())
        .list()
        .stream()
        .map(incident -> ExprEvaluator.oneLine(incident.getIncidentMessage()))
        .filter(message -> message.contains(fragment))
        .findFirst();

  }

  /**
   * Waits until the phase-two outbox put the start aside. A case about a workflow which
   * never comes into being needs this positive signal. Without it, "no workflow yet" and
   * "no workflow ever" look the same.
   *
   * @param bpmnProcessId The process whose start failed
   */
  protected void awaitBlockedOutboxEntry(
      final String bpmnProcessId) {

    awaitEngine(
        () -> outbox
            .entriesOf(bpmnProcessId)
            .stream()
            .anyMatch(PhaseTwoOutboxReader.Entry::isBlocked),
        "the start of '%s' has to end up blocked".formatted(bpmnProcessId));

  }

  /**
   * @param bpmnProcessId The process whose start failed
   * @return The most attempts any entry of that process carries, zero where it has none
   */
  private int outboxAttemptsOf(
      final String bpmnProcessId) {

    return outbox
        .entriesOf(bpmnProcessId)
        .stream()
        .mapToInt(PhaseTwoOutboxReader.Entry::attempts)
        .max()
        .orElse(0);

  }

  /**
   * Waits for the engine to finish what a start set going. On purpose, this is not a
   * general check whether the engine is quiet. A case waits for the fact it is about. So a
   * failure names what did not happen, instead of reporting a busy engine.
   */
  private void awaitEngine(
      final BooleanSupplier condition,
      final String description) {

    final var deadline = System.currentTimeMillis() + ENGINE_TIMEOUT_MS;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() >= deadline) {
        fail("the engine did not get there within %dms: %s".formatted(ENGINE_TIMEOUT_MS, description));
      }
      try {
        Thread.sleep(POLL_INTERVAL_MS);
      } catch (final InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        fail("interrupted while waiting for: "
            + description);
      }
    }

  }
}
