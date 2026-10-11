package io.vanillabp.camunda7.processservice;

/**
 * A start the engine refused because it holds no such process definition.
 * <p>
 * The outbox asks for a classification of the failure
 * ({@code MigratableProcessService#isPhaseTwoFailureRepeatable}). That classification
 * sees the failure and nothing else. But the engine says "there is nothing under that
 * name" with an exception which it also throws for arguments a caller left out. So the
 * operation travels INSIDE the failure. The start wraps what the engine answered into
 * this exception. The classification calls a failure which carries it permanent. It says
 * nothing about the same engine exception elsewhere.
 * <p>
 * What the engine really threw stays the cause.
 */
public class Camunda7RefusedStart extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Wraps what the engine refused a create command with. So the outbox blocks the entry,
   * and it does not repeat a command which will be refused the same way every time.
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
