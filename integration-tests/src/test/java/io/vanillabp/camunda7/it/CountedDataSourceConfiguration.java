package io.vanillabp.camunda7.it;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The application's datasource, with a note of every connection taken from it: who took it
 * and when. A connection is what anything in the application needs before it can say a word
 * to the database, so a count which does not move is a database nobody spoke to.
 * <p>
 * The taker is written down because a count alone cannot tell the engine from everything
 * else sharing this datasource. VanillaBP's outbox polls, the test's own questions read,
 * and neither says anything about a sleeping engine. So {@link Camunda7SleepingEngineIT}
 * asks for the takes of one thread rather than for a total.
 * <p>
 * Declared as a bean of its own, which keeps Spring Boot's datasource auto-configuration
 * out of the way and leaves no pool running a keepalive of its own behind the measurement.
 * <p>
 * A {@code @TestConfiguration} rather than a {@code @Configuration}, because the test
 * application scans this package: a plain configuration class here would hand its datasource
 * to every other integration test of the module as well.
 */
@TestConfiguration
public class CountedDataSourceConfiguration {

  /**
   * How many takes are kept. A quiet application takes a handful per minute, and a
   * measurement which finds this ring full has other problems than its oldest entry.
   */
  private static final int REMEMBERED = 2000;

  /**
   * The count, static because the measuring test reads it while the application it belongs
   * to is running.
   */
  private static final AtomicInteger CONNECTIONS_TAKEN = new AtomicInteger();

  /**
   * The most recent takes, oldest first. Guarded by its own monitor, because the engine,
   * the outbox and the test's own thread all write here.
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

  public static int connectionsTaken() {

    return CONNECTIONS_TAKEN.get();

  }

  /**
   * @param threadId The thread asked about
   * @return What that thread took, oldest first
   */
  public static List<Take> takenBy(
      final long threadId) {

    synchronized (DIARY) {
      return DIARY
          .stream()
          .filter(take -> take.threadId() == threadId)
          .toList();
    }

  }

  private static void writeDown() {

    CONNECTIONS_TAKEN.incrementAndGet();
    final var thread = Thread.currentThread();
    final var take = new Take(System.currentTimeMillis(), thread.threadId(), thread.getName());
    synchronized (DIARY) {
      if (DIARY.size() == REMEMBERED) {
        DIARY.remove(0);
      }
      DIARY.add(take);
    }

  }

  @Bean
  public DataSource dataSource() {

    final var h2 = new JdbcDataSource();
    h2.setURL("jdbc:h2:mem:c7-sleeping-engine-it;DB_CLOSE_DELAY=-1");
    return new CountedDataSource(h2);

  }

  private static final class CountedDataSource implements DataSource {

    private final JdbcDataSource delegate;

    CountedDataSource(
        final JdbcDataSource delegate) {

      this.delegate = delegate;

    }

    @Override
    public Connection getConnection() throws SQLException {

      writeDown();
      return delegate.getConnection();

    }

    @Override
    public Connection getConnection(
        final String username,
        final String password) throws SQLException {

      writeDown();
      return delegate.getConnection(username, password);

    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {

      return delegate.getLogWriter();

    }

    @Override
    public void setLogWriter(
        final PrintWriter out) throws SQLException {

      delegate.setLogWriter(out);

    }

    @Override
    public void setLoginTimeout(
        final int seconds) throws SQLException {

      delegate.setLoginTimeout(seconds);

    }

    @Override
    public int getLoginTimeout() throws SQLException {

      return delegate.getLoginTimeout();

    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {

      return delegate.getParentLogger();

    }

    @Override
    public <T> T unwrap(
        final Class<T> iface) throws SQLException {

      return delegate.unwrap(iface);

    }

    @Override
    public boolean isWrapperFor(
        final Class<?> iface) throws SQLException {

      return delegate.isWrapperFor(iface);

    }

  }

}
