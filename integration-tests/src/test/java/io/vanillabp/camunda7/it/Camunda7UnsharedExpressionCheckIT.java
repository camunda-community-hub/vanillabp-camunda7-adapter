package io.vanillabp.camunda7.it;

import java.util.stream.Collectors;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the startup check says about the models of the expression suite, read from the
 * boot log of a real engine. This is the check at the level it runs at: the models are
 * the ones {@code AbstractNestedExpressionsIT} measures the RUNTIME of, so every
 * expression named here has a case in that suite saying what the engine does with it.
 * <p>
 * The cases which matter are the quiet ones. A conditional event reading an unshared
 * nested attribute waits for good without an incident and without a log line, so the boot
 * is the only place a developer can hear about it at all.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7UnsharedExpressionCheckIT {

  /**
   * The boot log of an application deploying the expression models, whose aggregates
   * share a nested {@code order} and keep back {@code order.internalCode} and the whole
   * of {@code hiddenOrder}.
   *
   * @param output What the test class captured so far
   * @return Everything logged while this application booted
   */
  private String bootLog(
      final CapturedOutput output) {

    final var alreadyLogged = output
        .getAll()
        .length();
    try (var application = new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        .run(
            "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/expressions",
            "--spring.datasource.url=jdbc:h2:mem:c7-unshared-expression-check;DB_CLOSE_DELAY=-1")) {
      Assertions.assertTrue(application.isActive(), "the check warns, it never fails a deployment");
    }
    return output
        .getAll()
        .substring(alreadyLogged);

  }

  @Test
  @DisplayName("The condition of a conditional event is named, with the segment and what the engine will do")
  public void theConditionOfAConditionalEventIsNamed(
      final CapturedOutput output) {

    final var log = bootLog(output);

    // the conditional start event of an event subprocess, whose condition reads an
    // attribute two levels down that the aggregate keeps back. Nothing about this
    // workflow ever looks wrong at runtime
    final var quietEventSubprocess = warningAbout(log, "EQ_SubStart");
    Assertions
        .assertTrue(
            quietEventSubprocess.contains("reads the path 'order.internalCode'"),
            () -> "expected the whole path but got: "
                + quietEventSubprocess);
    Assertions
        .assertTrue(
            quietEventSubprocess.contains("'internalCode' IS a readable attribute of 'ExprOrder'"),
            () -> "expected the segment and the type holding it but got: "
                + quietEventSubprocess);
    Assertions
        .assertTrue(
            quietEventSubprocess.contains("the event keeps waiting for good"),
            () -> "expected the sentence about a conditional event but got: "
                + quietEventSubprocess);
    Assertions
        .assertTrue(
            quietEventSubprocess.contains("reads the workflow aggregate for a top-level name only"),
            () -> "a reported path must not be promised a fallback which does not serve it: "
                + quietEventSubprocess);

    // the second silent mechanism: a property of a value which reaches the engine as
    // its text, which Camunda swallows in a conditional event and nowhere else
    final var swallowedProperty = warningAbout(log, "EX_P03");
    Assertions
        .assertTrue(
            swallowedProperty.contains("reads the path 'order.dueDate.year'"),
            () -> "expected the whole path but got: "
                + swallowedProperty);
    Assertions
        .assertTrue(
            swallowedProperty.contains("'LocalDate'"),
            () -> "expected the type which travels as one value but got: "
                + swallowedProperty);
    Assertions
        .assertTrue(
            swallowedProperty.contains("carries nothing below it"),
            () -> "expected the reason but got: "
                + swallowedProperty);

  }

  @Test
  @DisplayName("A top-level name still hears about the migration fallback, a path never does")
  public void aTopLevelNameStillHearsAboutTheFallback(
      final CapturedOutput output) {

    final var log = bootLog(output);

    final var topLevel = warningAbout(log, "EC_yes_C10");
    Assertions
        .assertTrue(
            topLevel.contains("reads 'hiddenOrder', which IS an attribute of the workflow aggregate"),
            () -> "expected the top-level wording but got: "
                + topLevel);
    Assertions
        .assertTrue(
            topLevel.contains("that fallback will be removed"),
            () -> "a top-level name IS answered by the fallback, so the sentence belongs here: "
                + topLevel);

  }

  @Test
  @DisplayName("A placement which refuses the null out loud says so, and a missing nested attribute is named")
  public void theLoudPlacementsSaySoAsWell(
      final CapturedOutput output) {

    final var log = bootLog(output);

    // a collection is one of the placements which refuses a null instead of coercing it
    final var collection = warningAbout(log, "EX_P15");
    Assertions
        .assertTrue(
            collection.contains("'ExprOrder' has no readable attribute 'hiddenItems'"),
            () -> "expected the missing attribute and its type but got: "
                + collection);
    Assertions
        .assertTrue(
            collection.contains("did not resolve to a collection"),
            () -> "expected the sentence about a multi-instance collection but got: "
                + collection);

    // and a whole nested object the aggregate has not got at all
    final var absentNestedObject = warningAbout(log, "EC_yes_C21");
    Assertions
        .assertTrue(
            absentNestedObject.contains("'ExprCustomer' has no readable attribute 'address'"),
            () -> "expected the segment which stops the path but got: "
                + absentNestedObject);

  }

  @Test
  @DisplayName("Nothing is said about a path which works, nor about a method call the check cannot judge")
  public void whatWorksAndWhatCannotBeJudgedStayQuiet(
      final CapturedOutput output) {

    // only the lines of THIS check. The same models are read by the check which says what
    // an expression costs the application, and that one quotes every expression it found,
    // method calls included, see Camunda7ModelExpressionsIT
    final var log = bootLog(output)
        .lines()
        .filter(line -> line.contains("of element '"))
        .collect(Collectors.joining("\n"));

    // a fully shared path
    Assertions
        .assertFalse(
            log.contains("'order.customer.vip'"),
            "a path whose every segment is shared is a model which works");
    // a method call resolves against the runtime class the engine's serialization
    // produced, which a declared type does not say, so the check judges the path up to
    // the call and nothing more. 'order' is shared, so this stays quiet - the engine
    // raises an incident naming the expression, which is the loud half of the story
    Assertions
        .assertFalse(
            log.contains("getTotal"),
            "a method call is the engine's business, not this check's");
    // and the collection itself is shared, whatever an index into it reads
    Assertions
        .assertFalse(
            log.contains("'order.items'"),
            "an indexed access ends the path at the collection, which is shared");

  }

  /**
   * The one warning of the boot log naming that BPMN element.
   *
   * @param log The boot log
   * @param elementId The element the expression sits on
   * @return The line, for the assertions to read
   */
  private static String warningAbout(
      final String log,
      final String elementId) {

    final var line = log
        .lines()
        .filter(candidate -> candidate.contains("of element '"
            + elementId
            + "'"))
        .findFirst();
    Assertions
        .assertTrue(
            line.isPresent(),
            () -> "no warning about element '%s' in:%n%s".formatted(elementId, log));
    return line.get();

  }

}
