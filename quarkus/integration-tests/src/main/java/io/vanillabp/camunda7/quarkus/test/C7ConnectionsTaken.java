package io.vanillabp.camunda7.quarkus.test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import io.agroal.api.AgroalPoolInterceptor;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Counts every connection which the application's pool hands out, and writes down who took
 * it and when. Anything in the application needs a connection before it can say a word to
 * the database. So a count which does not move means that nobody spoke to the database.
 * That is what makes the Camunda 7 engine's due-date sleep worth measuring.
 * <p>
 * This is an Agroal pool interceptor, not the pool's metrics. The metrics need a metrics
 * extension on the classpath, and they would give a number without a taker.
 * <p>
 * The taker matters, because a count alone cannot tell the engine from everything else
 * which shares this pool. VanillaBP's outbox polls, and Hibernate serves the test's own
 * requests. Neither says anything about a sleeping engine. So every take is recorded with
 * the thread which made it and the moment it happened. A measurement asks for the takes of
 * one thread inside one window, not for a total.
 */
@ApplicationScoped
public class C7ConnectionsTaken implements AgroalPoolInterceptor {

  /**
   * How many takes are kept. A quiet application takes a handful per minute. A
   * measurement which finds this ring full has bigger problems than its oldest entry.
   */
  private static final int REMEMBERED = 2000;

  private static final AtomicLong TAKEN = new AtomicLong();

  /**
   * The most recent takes, oldest first. It is guarded by its own monitor, because the
   * engine, the outbox and the HTTP threads all write here.
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
