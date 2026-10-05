package io.vanillabp.camunda7.quarkus;

/**
 * What a boot which refused said, as one string. Which layer wraps a refusal differs per
 * platform and per check - the engine producer wraps what the engine holder threw, the
 * deployment pipeline wraps what a model check threw - so a test reading one particular
 * cause reads the wrong one sooner or later.
 */
final class Camunda7BootFailure {

  private Camunda7BootFailure() {
  }

  /**
   * @param throwable What the boot threw
   * @return The messages of the whole chain, one per line
   */
  static String messagesOf(
      final Throwable throwable) {

    final var messages = new StringBuilder();
    var cause = throwable;
    while (cause != null) {
      messages
          .append(cause.getMessage())
          .append('\n');
      cause = cause.getCause() == cause
          ? null
          : cause.getCause();
    }
    return messages.toString();

  }

}
