package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.fail;

import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

/**
 * An operation which progresses a workflow runs AFTER the caller's transaction
 * committed, dispatched by the phase-two outbox (see decision 2 in the repository's
 * DECISIONS.md). A test which called
 * VanillaBP therefore has to wait for the engine to catch up instead of reading its
 * state in the next line.
 * <p>
 * The waiting is deliberately kept here and not hidden in a base class: a test says
 * where it waits, so what is asynchronous stays visible.
 */
final class AwaitPhaseTwo {

  private static final long TIMEOUT_MS = 20_000;

  private static final long INTERVAL_MS = 50;

  private AwaitPhaseTwo() {
  }

  /**
   * Waits until a condition holds.
   *
   * @param condition What has to become true
   * @param description What is waited for (part of the failure message)
   */
  static void until(
      final BooleanSupplier condition,
      final String description) {

    final var deadline = System.currentTimeMillis() + TIMEOUT_MS;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() >= deadline) {
        fail("phase two did not happen within "
            + TIMEOUT_MS
            + "ms: "
            + description);
      }
      sleep();
    }

  }

  /**
   * Waits until a value is there, i.e. until the call neither returns
   * <code>null</code> nor throws.
   *
   * @param <T> The value's type
   * @param supplier What is asked repeatedly
   * @param description What is waited for (part of the failure message)
   * @return The value
   */
  static <T> T untilAvailable(
      final Callable<T> supplier,
      final String description) {

    final var deadline = System.currentTimeMillis() + TIMEOUT_MS;
    while (true) {
      try {
        final var value = supplier.call();
        if (value != null) {
          return value;
        }
      } catch (final Exception e) {
        if (System.currentTimeMillis() >= deadline) {
          throw new AssertionError(
              "phase two did not happen within "
                  + TIMEOUT_MS
                  + "ms: "
                  + description, e);
        }
      }
      if (System.currentTimeMillis() >= deadline) {
        return fail("phase two did not happen within "
            + TIMEOUT_MS
            + "ms: "
            + description);
      }
      sleep();
    }

  }

  private static void sleep() {

    try {
      Thread.sleep(INTERVAL_MS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while waiting for phase two", e);
    }

  }

}
