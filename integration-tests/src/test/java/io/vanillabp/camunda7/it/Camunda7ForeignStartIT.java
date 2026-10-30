package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow started past VanillaBP, against a real embedded Camunda 7 engine. Anybody with
 * access to the engine can start a process which VanillaBP knows, and give it a business
 * key of their own choosing. A timer start event does not stop the own start API of the
 * engine. And a conditional start can even be evaluated with a business key.
 * <p>
 * These tests hold the promise which VanillaBP makes about such a workflow: the application
 * names it, and nobody else. A start which brings no name reaches the
 * <code>@WorkflowStartedByBpms</code> method. The name which that method gives becomes the
 * business key of the instance. A start which brings a name that no workflow aggregate
 * carries is refused. A process without such a method refuses the start and says which
 * method to write. All of it is decision 28 in the repository's DECISIONS.md, which
 * supersedes decision 24.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database. Contexts are cached and live in parallel. A foreign engine and its job
    // executor on the same H2 database would compete for the jobs of this test.
    "spring.datasource.url=jdbc:h2:mem:c7-foreign-start-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done. This IT has a database of its own, and so a context of its
// own. An engine which outlives its test keeps its job executor running against a database
// which the next classes work on.
@DirtiesContext
public class Camunda7ForeignStartIT {

  /**
   * The Camunda tenant which the workflow module is deployed to under
   * {@code name-clash-avoidance: by-adapter}. Every start from outside has to name it.
   */
  private static final String TENANT = "c7-it";

  @Autowired
  private ForeignTimerRepository timerRepository;

  @Autowired
  private ForeignSignalRepository signalRepository;

  @Autowired
  private ForeignConditionRepository conditionRepository;

  @Autowired
  private ForeignNumericIdRepository numericIdRepository;

  @Autowired
  private ForeignPlainRepository plainRepository;

  @Autowired
  private ForeignTimerWorkflowService timerWorkflowService;

  @Autowired
  private ForeignPlainWorkflowService plainWorkflowService;

  @Autowired
  private RuntimeService runtimeService;

  @Autowired
  private ProcessEngine processEngine;

  @Autowired
  private org.springframework.transaction.support.TransactionTemplate transactionTemplate;

  private void awaitUntil(
      final Supplier<Boolean> condition,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (!Boolean.TRUE.equals(condition.get())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for: "
            + description);
      }
      Thread.sleep(100);
    }

  }

  /**
   * The timer start event of the model fires once, one second after the deployment. Its
   * workflow aggregate is one more in the repository the tests count. A test which runs in
   * that first second would see it come in between two of its counts. So every test starts
   * only once the timer has fired. The job is removed in the transaction which builds the
   * aggregate, so once the job is gone, the aggregate is there.
   */
  @BeforeEach
  public void waitForTheTimerOfTheModel() throws InterruptedException {

    awaitUntil(
        () -> processEngine
            .getManagementService()
            .createJobQuery()
            .processDefinitionKey("ForeignTimerProcess")
            .timers()
            .count() == 0,
        "the timer start event of the model to fire");

  }

  /**
   * The message of a start which the engine refused, with what its cause said appended. The
   * engine wraps what a listener threw, and the command decides which of the two carries
   * the text.
   */
  private String messageOf(
      final Throwable refused) {

    final var message = new StringBuilder();
    var cause = refused;
    while (cause != null) {
      message.append(cause.getMessage());
      cause = cause.getCause();
    }
    return message.toString();

  }

  @Test
  @DisplayName("A timer-started process somebody else starts without a name is named by the application")
  public void aTimerProcessStartedFromOutsideWithoutAName() throws Exception {

    final var before = timerRepository.count();

    runtimeService
        .createProcessInstanceByKey("ForeignTimerProcess")
        .processDefinitionTenantId(TENANT)
        .execute();

    awaitUntil(
        () -> timerRepository.count() > before,
        "the workflow aggregate of the foreign start to be built");

    final var named = timerRepository
        .findAll()
        .stream()
        .filter(aggregate -> aggregate.getId().startsWith("timer-"))
        .findFirst()
        .orElseThrow();
    // the engine now holds the name which the application gave. So the task after the start
    // event finds the workflow, and it runs on.
    awaitUntil(
        () -> "recordForeignTimerStart".equals(
            timerRepository
                .findById(named.getId())
                .map(ForeignTimerAggregate::getProcessedBy)
                .orElse(null)),
        "the task following the foreign start to be processed");

  }

  @Test
  @DisplayName("A start carrying a business key nothing carries is refused, and the message says why")
  public void aStartCarryingAForeignNameIsRefused() {

    final var before = timerRepository.count();

    final var refused = assertThrows(
        RuntimeException.class,
        () -> runtimeService
            .createProcessInstanceByKey("ForeignTimerProcess")
            .processDefinitionTenantId(TENANT)
            .businessKey("foreign-timer-key")
            .execute());

    final var message = messageOf(refused);
    assertTrue(message.contains("foreign-timer-key"), "the key is named: "
        + message);
    assertTrue(
        message.contains("VanillaBP names a workflow and nobody else"),
        "the rule is said out loud: "
            + message);
    assertTrue(message.contains("its business key"), "where the name is kept is named: "
        + message);
    assertTrue(message.contains("ProcessService"), "the way out is named: "
        + message);

    assertEquals(before, timerRepository.count(), "a refused start leaves no aggregate behind");
    assertEquals(
        0,
        runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey("foreign-timer-key")
            .count(),
        "a refused start leaves no process instance behind");

  }

  @Test
  @DisplayName("A signal the engine broadcasts is named by the application")
  public void aBroadcastIsNamedByTheApplication() throws Exception {

    final var before = signalRepository.count();

    runtimeService
        .createSignalEvent("ForeignStartSignal")
        .tenantId(TENANT)
        .send();

    awaitUntil(
        () -> signalRepository.count() > before,
        "the workflow aggregate of the broadcast to be built");

    final var started = signalRepository
        .findAll()
        .stream()
        .filter(aggregate -> aggregate.getId().startsWith("signal-ForeignStartSignal-"))
        .findFirst()
        .orElseThrow();
    // the signal name reaches the application PLAIN, although the model was deployed with
    // scoped identifiers
    awaitUntil(
        () -> "recordForeignSignalStart".equals(
            signalRepository
                .findById(started.getId())
                .map(ForeignSignalAggregate::getProcessedBy)
                .orElse(null)),
        "the task following the broadcast start to be processed");

  }

  @Test
  @DisplayName("A condition evaluated with a business key of its own is refused")
  public void aConditionEvaluatedWithAForeignNameIsRefused() {

    final var before = conditionRepository.count();

    final var refused = assertThrows(
        RuntimeException.class,
        () -> runtimeService
            .createConditionEvaluation()
            .setVariable("foreignStartReady", Boolean.TRUE)
            .processInstanceBusinessKey("foreign-condition-key")
            .tenantId(TENANT)
            .evaluateStartConditions());

    assertTrue(
        messageOf(refused).contains("foreign-condition-key"),
        "the key is named: "
            + messageOf(refused));
    assertEquals(before, conditionRepository.count(), "a refused start leaves no aggregate behind");

  }

  @Test
  @DisplayName("A condition evaluated without a business key is named by the application")
  public void aConditionEvaluatedWithoutANameIsNamedByTheApplication() throws Exception {

    final var before = conditionRepository.count();

    runtimeService
        .createConditionEvaluation()
        .setVariable("foreignStartReady", Boolean.TRUE)
        .tenantId(TENANT)
        .evaluateStartConditions();

    awaitUntil(
        () -> conditionRepository.count() > before,
        "the workflow aggregate of the evaluated condition to be built");

    final var started = conditionRepository
        .findAll()
        .stream()
        .filter(aggregate -> aggregate.getId().startsWith("condition-"))
        .findFirst()
        .orElseThrow();
    awaitUntil(
        () -> "recordForeignConditionStart".equals(
            conditionRepository
                .findById(started.getId())
                .map(ForeignConditionAggregate::getProcessedBy)
                .orElse(null)),
        "the task following the evaluated condition to be processed");

  }

  @Test
  @DisplayName("The application's own start of such a process is not mistaken for a foreign one")
  public void theApplicationsOwnStart() throws Exception {

    // the aggregate is written in the caller's transaction. The instance is started after
    // its commit, through the phase-two outbox. So the start listener reads a committed
    // aggregate
    timerWorkflowService.startTheWorkflow("started-by-the-application");

    awaitUntil(
        () -> "recordForeignTimerStart".equals(
            timerRepository
                .findById("started-by-the-application")
                .map(ForeignTimerAggregate::getProcessedBy)
                .orElse(null)),
        "the task of the workflow the application started to be processed");

    // the value the application wrote before starting is still there. A start VanillaBP
    // had taken for a foreign one would have built a second aggregate over this one, so
    // this is the proof that the start listener found the aggregate the application wrote
    assertEquals(
        "the application",
        timerRepository.findById("started-by-the-application").orElseThrow().getStartedBy(),
        "what the application wrote into the aggregate survived its own start");

  }

  @Test
  @DisplayName("A second delivery of the same start builds no second aggregate")
  public void aSecondDeliveryBuildsNoSecondAggregate() throws Exception {

    // the first delivery brings no name. So the application names the workflow, and this
    // adapter writes that name into the business key of the instance.
    final var untouched = timerRepository
        .findAll()
        .stream()
        .map(ForeignTimerAggregate::getId)
        .toList();
    runtimeService
        .createProcessInstanceByKey("ForeignTimerProcess")
        .processDefinitionTenantId(TENANT)
        .execute();
    awaitUntil(
        () -> timerRepository.count() > untouched.size(),
        "the workflow aggregate of the first delivery to be built");
    final var named = timerRepository
        .findAll()
        .stream()
        .map(ForeignTimerAggregate::getId)
        .filter(id -> id.startsWith("timer-") && !untouched.contains(id))
        .findFirst()
        .orElseThrow();
    // the first workflow runs to its end before anything is written. Its task writes the
    // aggregate as well, and a half-finished workflow would race this test.
    awaitUntil(
        () -> "recordForeignTimerStart".equals(
            timerRepository
                .findById(named)
                .map(ForeignTimerAggregate::getProcessedBy)
                .orElse(null)),
        "the task of the first delivery to be processed");
    final var afterTheFirstDelivery = timerRepository.count();

    // business data written between the two deliveries. A second aggregate built over this
    // one would take it with it, and nothing would say so. The mark of the task is cleared
    // at the same time, so the own workflow of the second delivery can be recognized.
    transactionTemplate.executeWithoutResult(status -> {
      final var aggregate = timerRepository.findById(named).orElseThrow();
      aggregate.setStartedBy("changed meanwhile");
      aggregate.setProcessedBy(null);
      timerRepository.save(aggregate);
    });

    // what a second delivery of that very listener hands the core: the instance already
    // carries the name of a workflow aggregate which exists. The engine hands a start job
    // out again whenever the transaction of the first one did not commit. The application
    // has to survive that.
    runtimeService
        .createProcessInstanceByKey("ForeignTimerProcess")
        .processDefinitionTenantId(TENANT)
        .businessKey(named)
        .execute();
    awaitUntil(
        () -> "recordForeignTimerStart".equals(
            timerRepository
                .findById(named)
                .map(ForeignTimerAggregate::getProcessedBy)
                .orElse(null)),
        "the task of the second delivery to be processed");

    assertEquals(
        afterTheFirstDelivery,
        timerRepository.count(),
        "the second delivery must build nothing");
    assertEquals(
        "changed meanwhile",
        timerRepository.findById(named).orElseThrow().getStartedBy(),
        "and what was written between the two deliveries has to survive it");

  }

  @Test
  @DisplayName("A business key which cannot be an id at all is refused like every other foreign name")
  public void aBusinessKeyWhichCannotBeAnId() {

    final var before = numericIdRepository.count();

    // the id of this aggregate is a number which the persistence layer assigns. So a text
    // key can never be one of them, and that makes it a name which no workflow aggregate
    // carries.
    final var refused = assertThrows(
        RuntimeException.class,
        () -> runtimeService
            .createProcessInstanceByKey("ForeignNumericIdProcess")
            .processDefinitionTenantId(TENANT)
            .businessKey("ORDER-4711")
            .execute());

    final var message = messageOf(refused);
    assertTrue(message.contains("ORDER-4711"), "the key is named: "
        + message);
    assertTrue(message.contains("ProcessService"), "the way out is named: "
        + message);
    assertTrue(message.contains("ForeignNumericIdProcess"), "the BPMN process is named: "
        + message);

    assertEquals(before, numericIdRepository.count(), "a refused start leaves no aggregate behind");
    assertEquals(
        0,
        runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey("ORDER-4711")
            .count(),
        "a refused start leaves no process instance behind");

  }

  @Test
  @DisplayName("A process without such a method refuses the start and hands out the method to write")
  public void aProcessWithoutSuchAMethodSaysWhatToWrite() {

    final var before = plainRepository.count();

    // a plain start event, so the application booted without a @WorkflowStartedByBpms
    // method. Starting it past VanillaBP is what the method would have been for.
    final var refused = assertThrows(
        RuntimeException.class,
        () -> runtimeService
            .createProcessInstanceByKey("ForeignPlainProcess")
            .processDefinitionTenantId(TENANT)
            .execute());

    final var message = messageOf(refused);
    assertTrue(
        message.contains("no @WorkflowStartedByBpms method builds a workflow aggregate for it"),
        "what is missing is named: "
            + message);
    assertTrue(
        message.contains("@WorkflowStartedByBpms(id = \"PlainStart\")"),
        "the method to write is handed out: "
            + message);
    assertTrue(
        message.contains("public ForeignPlainAggregate buildAggregate(final BpmsStartTrigger trigger)"),
        "the method to write names the aggregate: "
            + message);

    assertEquals(before, plainRepository.count(), "a refused start leaves no aggregate behind");

  }

  @Test
  @DisplayName("The application's own start through a plain start event needs no such method")
  public void theApplicationsOwnStartThroughAPlainStartEvent() throws Exception {

    plainRepository.deleteAll();

    // the same process which the test above was refused on. The listener sits on its plain
    // start event as well. The name which the application gave tells the two apart.
    plainWorkflowService.startTheWorkflow("started-plainly");

    awaitUntil(
        () -> "recordForeignPlainStart".equals(
            plainRepository
                .findById("started-plainly")
                .map(ForeignPlainAggregate::getProcessedBy)
                .orElse(null)),
        "the task of the plainly started workflow to be processed");

  }

}
