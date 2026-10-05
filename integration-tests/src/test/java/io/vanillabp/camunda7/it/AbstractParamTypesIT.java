package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.function.BooleanSupplier;

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

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a <code>&#64;TaskParam</code> receives when the type it declares and the value the
 * engine reports do not agree. The conversion itself belongs to the platform, and this
 * suite is what proves the whole way through Camunda 7: the aggregate is shared, the
 * engine stores it, an input mapping writes a local variable of its own, the engine
 * deserializes that variable and only then is it bound to the parameter.
 * <p>
 * Every branch of the model asks one question. A value the declared type holds reaches the
 * handler; a value it does not hold ends the task, and after the job's retries the message
 * stands in the incident, which is where an operator reads it.
 * <p>
 * One subclass per serialization world, because the format is engine configuration and
 * needs a Spring context of its own. Most cells answer the same in both, which is the
 * point: the conversion reads the value in hand and knows nothing about the format it was
 * stored in. The one cell which does differ is the scale of a decimal, and each subclass
 * says what its world does with it.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractParamTypesIT {

  protected static final String MODULE_ID = "c7-it";

  /**
   * How long the engine is given. Every branch of the model is asynchronous, the four
   * branches which are refused are retried three times each before the incident is
   * written, and ten branches run at once.
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
  protected ParamTypesWorkflowService paramTypesService;

  private ProcessInstance workflow;

  /**
   * @return What <code>${total}</code> reads as once this world's serializer wrote the
   *         decimal and read it back: the value the aggregate holds, or the same number
   *         without the trailing zero
   */
  protected abstract BigDecimal theDecimalThisWorldGivesBack();

  @BeforeEach
  void startTheWorkflowOnce() {

    if (workflow != null) {
      return;
    }
    ParamTypesProbe.clear();
    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new ParamTypesAggregate();
      aggregate.fillWithTheSample();
      return paramTypesService
          .start(aggregate)
          .getId();
    });
    workflow = AwaitPhaseTwo
        .untilAvailable(
            () -> runtimeService
                .createProcessInstanceQuery()
                .processDefinitionKey("ParamTypes")
                .processInstanceBusinessKey(String.valueOf(aggregateId))
                .tenantIdIn(MODULE_ID)
                .singleResult(),
            "the workflow 'ParamTypes' of aggregate %s has to reach the engine".formatted(aggregateId));

  }

  @Test
  @DisplayName("A value the declared type holds reaches the handler")
  void aValueTheTypeHoldsReachesTheHandler() {

    assertArrived("PT_total_Double", Double.class, "120.5");
    assertArrived("PT_nested_Double", Double.class, "120.5");
    // a Long above Integer.MAX_VALUE into a long, and a whole number above 2^53 into one:
    // the two pairs version 1 let through by reflection, and they still go through
    assertArrived("PT_count_long", Long.class, "3000000000");
    assertArrived("PT_huge_long", Long.class, "9007199254740993");

  }

  @Test
  @DisplayName("A float reaches a Double as the number it prints, not as the double it widens to")
  void aFloatReachesADoubleAsTheNumberItPrints() {

    // 0.1f widened by floatValue() is 0.10000000149011612, which is what a handler used
    // to receive here while the same aggregate answered 0.1 on Camunda 8. Converted
    // through the text of the float, both engines say 0.1 and neither of them was asked
    // which engine it is.
    assertArrived("PT_rate_Double", Double.class, "0.1");

  }

  @Test
  @DisplayName("The scale of a decimal is the world's answer, and the number is the same in both")
  void theScaleIsTheWorldsAnswer() {

    final var arrival = awaitArrival("PT_total_BigDecimal");
    assertEquals(BigDecimal.class.getName(), arrival.className());
    assertEquals(theDecimalThisWorldGivesBack().toString(), arrival.text());
    assertEquals(
        0,
        theDecimalThisWorldGivesBack().compareTo(new BigDecimal("120.50")),
        "both worlds hold the same number, and only the scale differs");

  }

  @Test
  @DisplayName("A decimal bound to an int ends the task instead of dropping its fraction")
  void aDecimalIntoAnIntEndsTheTask() {

    final var incident = awaitIncidentAt("PT_total_int");
    assertTrue(incident.contains("does not fit the parameter's type 'int'"), incident);
    assertTrue(incident.contains("which would hold '120'"), incident);
    assertNull(ParamTypesProbe.arrivalAt("PT_total_int"), "the handler must not have run");

  }

  @Test
  @DisplayName("A long too large for an int ends the task instead of arriving as a negative number")
  void aLongTooLargeForAnIntEndsTheTask() {

    final var incident = awaitIncidentAt("PT_count_int");
    assertTrue(incident.contains("The value '3000000000'"), incident);
    assertTrue(incident.contains("which would hold '-1294967296'"), incident);

  }

  @Test
  @DisplayName("A whole number a double cannot hold ends the task instead of losing its last digit")
  void aWholeNumberADoubleCannotHoldEndsTheTask() {

    final var incident = awaitIncidentAt("PT_huge_Double");
    assertTrue(incident.contains("The value '9007199254740993'"), incident);
    assertTrue(incident.contains("which would hold '9.007199254740992E15'"), incident);

  }

  @Test
  @DisplayName("The text of a decimal ends the task with the same guiding message")
  void theTextOfADecimalEndsTheTaskGuiding() {

    // This cell used to leave an operator with 'For input string: "120.50"' and nothing
    // else: the NumberFormatException of Integer.valueOf escaped uncaught. It now reads
    // like every other refusal.
    final var incident = awaitIncidentAt("PT_text_int");
    assertTrue(incident.contains("@TaskParam(\"totalText\")"), incident);
    assertTrue(incident.contains("textIntoInt"), incident);
    assertTrue(incident.contains("does not fit the parameter's type 'int'"), incident);
    assertTrue(
        !incident.contains("For input string"),
        "the bare NumberFormatException must not reach the operator any more: "
            + incident);

  }

  private void assertArrived(
      final String cell,
      final Class<?> expectedClass,
      final String expectedText) {

    final var arrival = awaitArrival(cell);
    assertEquals(expectedClass.getName(), arrival.className(), cell);
    assertEquals(expectedText, arrival.text(), cell);

  }

  private ParamTypesProbe.Arrival awaitArrival(
      final String cell) {

    awaitEngine(
        () -> ParamTypesProbe.arrivalAt(cell) != null,
        "the handler of '%s' has to be entered".formatted(cell));
    final var arrival = ParamTypesProbe.arrivalAt(cell);
    assertNotNull(arrival, cell);
    return arrival;

  }

  private String awaitIncidentAt(
      final String activityId) {

    awaitEngine(
        () -> incidentAt(activityId).isPresent(),
        "'%s' has to raise an incident".formatted(activityId));
    return incidentAt(activityId).orElseThrow();

  }

  private Optional<String> incidentAt(
      final String activityId) {

    return runtimeService
        .createIncidentQuery()
        .processInstanceId(workflow.getProcessInstanceId())
        .activityId(activityId)
        .list()
        .stream()
        .map(incident -> incident.getIncidentMessage().replaceAll("\\s+", " "))
        .findFirst();

  }

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
