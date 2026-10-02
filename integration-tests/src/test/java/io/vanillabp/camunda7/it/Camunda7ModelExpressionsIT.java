package io.vanillabp.camunda7.it;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the core is told about the expressions of a model, read from the boot log of a real
 * engine. The adapter finds them, the core judges them, and this is the seam between the
 * two: every expression of the model arrives with the element it sits in, the place inside
 * that element and the text JUEL evaluates.
 * <p>
 * The models are the expression suite, whose expressions sit in every place Camunda 7 lets
 * one sit. {@code Camunda7ExpressionIdentifiersTest} reads the collection on its own and
 * {@code ExpressionsInTheModelTest} of the platform reads the wording of the two messages,
 * so what is left for this class is the question neither of them can answer: does a real
 * deployment of a real model reach the check at all?
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7ModelExpressionsIT {

  /**
   * The boot log of an application deploying the expression models.
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
            "--spring.datasource.url=jdbc:h2:mem:c7-model-expressions;DB_CLOSE_DELAY=-1")) {
      Assertions.assertTrue(application.isActive(), "the check names expressions, it never fails a deployment");
    }
    return output
        .getAll()
        .substring(alreadyLogged);

  }

  @Test
  @DisplayName("Every place an expression sits in is named with the element and the expression")
  public void everyPlaceIsNamed(
      final CapturedOutput output) {

    final var log = bootLog(output);

    // a condition of a conditional event, a timer, a cardinality and a collection: four
    // of the places, each read out of a different part of the BPMN
    Assertions
        .assertTrue(
            log.contains("'${order.getTotal() > 100}' at 'EX_P01' (the condition of a conditional event)"),
            () -> "expected the condition of the conditional event but got:\n"
                + log);
    Assertions
        .assertTrue(
            log.contains("'${order.internalCode}' at 'EX_P06' (a timer)"),
            () -> "expected the timer but got:\n"
                + log);
    Assertions
        .assertTrue(
            log.contains("'${order.items.size()}' at 'EX_P11' (the cardinality of a multi-instance element)"),
            () -> "expected the cardinality but got:\n"
                + log);
    Assertions
        .assertTrue(
            log.contains("'${order.items}' at 'EX_P14' (the collection of a multi-instance element)"),
            () -> "expected the collection but got:\n"
                + log);
    // and the condition of a sequence flow, which is the place most expressions sit in
    Assertions
        .assertTrue(
            log.contains("'${order.customer.vip}' at 'EC_yes_C01' (the condition of a sequence flow)"),
            () -> "expected the condition of the sequence flow but got:\n"
                + log);

  }

  @Test
  @DisplayName("A path and a call are warned about, a computation is noticed, and a plain name is only counted")
  public void whatEachFormCosts(
      final CapturedOutput output) {

    final var log = bootLog(output);

    Assertions
        .assertTrue(
            log.contains("read the application's data by more than the name of one variable"),
            () -> "expected the warning about the paths and the calls but got:\n"
                + log);
    Assertions
        .assertTrue(
            log.contains("compute instead of naming a variable"),
            () -> "expected the notice about the computations but got:\n"
                + log);
    // '${order.internalCode == 'IC-9'}' computes, so it is the smaller half of the story
    Assertions
        .assertTrue(
            log.contains("'${order.internalCode == 'IC-9'}' at 'EX_P02'"),
            () -> "expected the computation but got:\n"
                + log);
    // the count is what tells a developer how far their model is. One of the fourteen
    // expressions of 'ExprPlacements' is the name of a variable, the timer of 'EX_P07',
    // and that one is counted here and named nowhere
    Assertions
        .assertTrue(
            log.contains("1 of the 14 expressions of this process name a variable and nothing else."),
            () -> "expected the count of 'ExprPlacements' - did the model change? Log:\n"
                + log);
    Assertions
        .assertFalse(
            log.contains("'${dueAt}'"),
            () -> "an expression naming one variable is what VanillaBP recommends, so it is never named:\n"
                + log);
    // and the way out of the message, at the workflow, which is the most careful level
    Assertions
        .assertTrue(
            log
                .contains(
                    "vanillabp.workflow-modules.c7-it.workflows.ExprPlacements.accept-expressions-in-the-model"),
            () -> "expected the key which accepts the expressions of this process but got:\n"
                + log);

  }

}
