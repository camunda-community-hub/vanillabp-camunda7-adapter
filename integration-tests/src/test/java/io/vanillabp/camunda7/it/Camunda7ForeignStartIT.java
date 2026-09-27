package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A workflow started past VanillaBP, against a real embedded Camunda 7 engine. Anybody
 * with access to the engine can start a process which VanillaBP knows, and give it a
 * business key of their own choosing - a timer start event does not stop the engine's own
 * start API, and a conditional start can even be evaluated with a business key.
 * <p>
 * These tests hold the promise VanillaBP makes about such a workflow: the application
 * names it, and nobody else. A start which brings no name reaches the
 * <code>@WorkflowStartedByBpms</code> method, and the name that method gives becomes the
 * instance's business key. A start which brings a name no workflow aggregate carries is
 * refused. A process without such a method refuses the start and says which method to
 * write. All of it is decision 28 in the repository's DECISIONS.md, which supersedes
 * decision 24.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: contexts are cached and live in parallel - a foreign engine
    // (and job executor) on the same H2 database would compete for this test's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-foreign-start-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, and an engine outliving its test keeps its job executor running against a database
// the next classes work on
@DirtiesContext
public class Camunda7ForeignStartIT {

  /**
   * The Camunda tenant the workflow module is deployed to under
   * {@code name-clash-avoidance: by-adapter}, which every start from outside has to name.
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
   * The message of a start the engine refused, with what its cause said appended: the
   * engine wraps what a listener threw, and which of the two carries the text depends on
   * the command.
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
    // the name the application gave is what the engine holds now, so the task following
    // the start event finds the workflow and it runs on
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
    // the signal name reaches the application PLAIN, although the model was deployed
    // with scoped identifiers
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

    // the aggregate is written and the instance started in ONE transaction here, so this
    // is also the proof that the aggregate is readable while that transaction runs
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
    // this is the proof that the aggregate is readable in the transaction writing it
    assertEquals(
        "the application",
        timerRepository.findById("started-by-the-application").orElseThrow().getStartedBy(),
        "what the application wrote into the aggregate survived its own start");

  }

  @Test
  @DisplayName("A business key which cannot be an id at all is refused like every other foreign name")
  public void aBusinessKeyWhichCannotBeAnId() {

    final var before = numericIdRepository.count();

    // the id of this aggregate is a number the persistence layer assigns, so a text key
    // can never be one of them - which makes it a name no workflow aggregate carries
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
    // method. Starting it past VanillaBP is what the method would have been for
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

    // the same process the test above was refused on: the listener sits on its plain
    // start event as well, and the name the application gave is what tells the two apart
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
