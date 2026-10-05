package io.vanillabp.camunda7.quarkus.test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.agroal.api.AgroalPoolInterceptor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Counts every connection handed out by the application's pool, and writes down who took
 * it and when. A connection is what anything in the application needs before it can say a
 * word to the database, so a count which does not move is a database nobody spoke to,
 * which is what the Camunda 7 engine's due-date sleep is worth measuring.
 * <p>
 * An Agroal pool interceptor rather than the pool's metrics: those need a metrics extension
 * on the classpath, and they would give a number without a taker.
 * <p>
 * The taker matters because a count alone cannot tell the engine from everything else that
 * shares this pool. VanillaBP's outbox polls, Hibernate serves the test's own requests, and
 * neither of those says anything about a sleeping engine. So every take is recorded with
 * the thread which made it and the moment it happened, and a measurement asks for the takes
 * of one thread inside one window rather than for a total.
 */
@ApplicationScoped
public class C7ConnectionsTaken implements AgroalPoolInterceptor {

  /**
   * How many takes are kept. A quiet application takes a handful per minute, and a
   * measurement which finds this ring full has other problems than its oldest entry.
   */
  private static final int REMEMBERED = 2000;

  private static final AtomicLong TAKEN = new AtomicLong();

  /**
   * The most recent takes, oldest first. Guarded by its own monitor, because the engine,
   * the outbox and the HTTP threads all write here.
   */
  private static final List<Take> DIARY = new ArrayList<>();

  /**
   * One connection, taken by one thread at one moment.
   *
   * @param at When the connection was handed out
   * @param threadId The thread which took it
   * @param threadName What that thread is called, which is what a failure message shows
   */
  public record Take(
                     long at,
                     long threadId,
                     String threadName) {
  }

  @Override
  public void onConnectionAcquire(
      final Connection connection) {

    TAKEN.incrementAndGet();
    final var thread = Thread.currentThread();
    final var take = new Take(System.currentTimeMillis(), thread.threadId(), thread.getName());
    synchronized (DIARY) {
      if (DIARY.size() == REMEMBERED) {
        DIARY.remove(0);
      }
      DIARY.add(take);
    }

  }

  /**
   * @return How many connections were taken since the application started
   */
  public long count() {

    return TAKEN.get();

  }

  /**
   * @return The takes still remembered, oldest first
   */
  public List<Take> diary() {

    synchronized (DIARY) {
      return List.copyOf(DIARY);
    }

  }

}
