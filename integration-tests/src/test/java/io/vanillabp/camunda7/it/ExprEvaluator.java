package io.vanillabp.camunda7.it;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.ProcessEngineImpl;

/**
 * Evaluates an expression exactly the way a sequence flow condition is evaluated: through
 * the engine's own {@code ExpressionManager}, which on Spring Boot is
 * {@code Camunda7SpringExpressionManager} and therefore carries VanillaBP's EL resolver,
 * against a real execution of a running workflow.
 * <p>
 * Why the value of a form is read this way instead of putting every form on a gateway: a
 * gateway adds what Camunda 7 does with the RESULT (the default flow, an incident), and
 * that is the same for every form of one outcome class, so it is asserted once per outcome
 * class in {@code expr-conditions.bpmn}. What differs per form is the value and the
 * exception, and this reads both without a model per case.
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
   * The whole cause chain, because the message a modeller would see is the outermost one
   * and the reason is the innermost.
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
