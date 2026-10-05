package io.vanillabp.camunda7.processservice;

/**
 * A start the engine refused because it holds no such process definition.
 * <p>
 * The classification the outbox asks for sees the failure and nothing else
 * ({@code MigratableProcessService#isPhaseTwoFailureRepeatable}), while the engine says
 * "there is nothing under that name" with an exception it also throws for arguments a
 * caller left out. So the operation travels INSIDE the failure: the start wraps what the
 * engine answered into this exception, and the classification calls what carries it
 * permanent without saying anything about the same exception elsewhere.
 * <p>
 * What the engine really threw stays the cause.
 */
public class Camunda7RefusedStart extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Wraps what the engine refused a create command with, so the outbox blocks the entry
   * instead of repeating a command which will be refused the same way every time.
   *
   * @param message What an operator reads next to the blocked outbox entry
   * @param refusal What the engine answered the create command with
   */
  public Camunda7RefusedStart(
      final String message,
      final Throwable refusal) {

    super(message, refusal);

  }

}
