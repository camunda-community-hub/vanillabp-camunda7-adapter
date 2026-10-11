package io.vanillabp.camunda7.it;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.ProcessEngineImpl;

/**
 * Evaluates an expression exactly the way a sequence flow condition is evaluated. It uses
 * the own {@code ExpressionManager} of the engine, against a real execution of a running
 * workflow. On Spring Boot that manager is {@code Camunda7SpringExpressionManager}, so it
 * carries the EL resolver of VanillaBP.
 * <p>
 * Why is the value of a form read this way, instead of putting every form on a gateway? A
 * gateway adds what Camunda 7 does with the RESULT: the default flow, or an incident. That
 * is the same for every form of one outcome class. So {@code expr-conditions.bpmn} asserts
 * it once per outcome class. What differs per form is the value and the exception. This
 * class reads both without a model per case.
 */
final class ExprEvaluator {

  /**
   * What one evaluation produced.
   *
   * @param value The value, if it evaluated
   * @param failure The throwable, if it did not
   */
  record Outcome(Object value, Throwable failure) {

    String describe() {

      if (failure != null) {
        return "THREW "
            + chainOf(failure);
      }
      if (value == null) {
        return "null (no class, the expression resolved to nothing)";
      }
      return "%s of class %s".formatted(displayOf(value), value.getClass().getName());

    }

  }

  private ExprEvaluator() {
  }

  static Outcome evaluate(
      final ProcessEngine engine,
      final String executionId,
      final String expressionText) {

    final var configuration = ((ProcessEngineImpl) engine).getProcessEngineConfiguration();
    try {
      final var value = configuration
          .getCommandExecutorTxRequired()
          .execute(commandContext -> {
            final var execution = commandContext
                .getExecutionManager()
                .findExecutionById(executionId);
            return configuration
                .getExpressionManager()
                .createExpression(expressionText)
                .getValue(execution);
          });
      return new Outcome(value, null);
    } catch (final Throwable failure) {
      return new Outcome(null, failure);
    }

  }

  /**
   * The whole cause chain. A modeller would see the outermost message, but the reason is
   * the innermost one.
   */
  static String chainOf(
      final Throwable failure) {

    final var text = new StringBuilder();
    for (var cause = failure; cause != null; cause = cause.getCause() == cause
        ? null
        : cause.getCause()) {
      if (text.length() > 0) {
        text.append(" <- ");
      }
      text
          .append(cause.getClass().getSimpleName())
          .append(": ")
          .append(oneLine(cause.getMessage()));
    }
    return text.toString();

  }

  static String oneLine(
      final String text) {

    return text == null
        ? "(no message)"
        : text
            .replaceAll("\\s+", " ")
            .trim();

  }

  static String displayOf(
      final Object value) {

    final var text = String.valueOf(value);
    return text.length() > 160
        ? text.substring(0, 160)
            + "..."
        : text;

  }

}
