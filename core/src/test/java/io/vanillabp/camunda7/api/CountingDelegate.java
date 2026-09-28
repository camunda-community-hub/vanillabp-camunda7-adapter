package io.vanillabp.camunda7.api;

import java.util.concurrent.atomic.AtomicInteger;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

/**
 * Counts how often the engine ran the activity it sits on. The engine instantiates it by
 * class name, so the count is static.
 */
public class CountingDelegate implements JavaDelegate {

  private static final AtomicInteger ROUNDS = new AtomicInteger();

  /**
   * Forgets what an earlier test counted.
   */
  public static void reset() {

    ROUNDS.set(0);

  }

  /**
   * @return How often the engine entered the activity since the last reset
   */
  public static int rounds() {

    return ROUNDS.get();

  }

  @Override
  public void execute(
      final DelegateExecution execution) {

    ROUNDS.incrementAndGet();

  }

}
