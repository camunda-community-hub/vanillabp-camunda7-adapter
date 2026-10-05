package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What Camunda 7 itself does with <code>databaseTablePrefix</code> - recorded here,
 * without VanillaBP in the way, because the answer decides what
 * <code>vanillabp.adapters.&lt;id&gt;.table-prefix</code> can promise.
 * <p>
 * The engine states the answer in its own API:
 * {@code ProcessEngineConfigurationImpl#setDatabaseTablePrefix} says <i>"the prefix
 * is not respected by automatic database schema management. If you use
 * DB_SCHEMA_UPDATE_CREATE_DROP or DB_SCHEMA_UPDATE_TRUE, activiti will create the
 * database tables using the default names, regardless of the prefix configured
 * here."</i> The tests below are that sentence, executed:
 * <ul>
 *   <li>the shipped DDL carries fixed table names in EVERY dialect, so no database
 *       behaves differently;</li>
 *   <li><code>databaseSchemaUpdate=true</code> plus a prefix creates the
 *       <code>ACT_*</code> tables under their DEFAULT names and then fails on the
 *       engine's first prefixed query - a prefix naming a schema included;</li>
 *   <li>an engine whose prefixed tables EXIST starts and runs.</li>
 * </ul>
 * The last one is the mode the adapter supports:
 * {@link Camunda7TablePrefixSchema} makes the application learn about it while
 * booting instead of through a MyBatis stack trace.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7TablePrefixEngineBehaviourTest {

  /**
   * The engine components enabled by default, in the order Camunda creates them -
   * used to build a prefixed schema by hand (which is what an application has to do
   * for a prefixed adapter id).
   */
  private static final List<String> COMPONENTS = List.of(
      "engine", "history", "identity", "case.engine", "case.history", "decision.engine",
      "decision.history");

  private static DataSource h2(
      final String name) {

    final var dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name));
    return dataSource;

  }

  private static StandaloneProcessEngineConfiguration engineConfiguration(
      final DataSource dataSource,
      final String schemaUpdate,
      final String tablePrefix) {

    final var configuration = new StandaloneProcessEngineConfiguration();
    configuration.setDataSource(dataSource);
    configuration.setDatabaseSchemaUpdate(schemaUpdate);
    configuration.setJobExecutorActivate(false);
    configuration.setHistoryTimeToLive("P180D");
    configuration.setProcessEngineName("table-prefix-%s".formatted(System.nanoTime()));
    if (tablePrefix != null) {
      configuration.setDatabaseTablePrefix(tablePrefix);
    }
    return configuration;

  }

  private static boolean tableExists(
      final DataSource dataSource,
      final String tableName) {

    try (Connection connection = dataSource.getConnection()) {
      try (var tables = connection
          .getMetaData()
          .getTables(null, null, tableName, new String[]{
              "TABLE"
          })) {
        return tables.next();
      }
    } catch (final SQLException e) {
      throw new IllegalStateException(e);
    }

  }

  private static String ddlOf(
      final String database,
      final String component) {

    final var resource = "org/camunda/bpm/engine/db/create/activiti.%s.create.%s.sql"
        .formatted(database, component);
    try (InputStream in = ProcessEngine.class
        .getClassLoader()
        .getResourceAsStream(resource)) {
      assertNotNull(in, () -> "the engine has to ship "
          + resource);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }

  }

  /**
   * Which <code>ACT_</code> names are objects and which are columns: the tables live
   * under <code>ACT_GE_</code>, <code>ACT_HI_</code>, <code>ACT_RE_</code>,
   * <code>ACT_RU_</code> and <code>ACT_ID_</code>, the indexes and constraints under
   * <code>ACT_IDX_</code>, <code>ACT_FK_</code> and <code>ACT_UNIQ_</code>, while
   * <code>ACT_ID_</code>, <code>ACT_INST_ID_</code>, <code>ACT_INST_STATE_</code>,
   * <code>ACT_NAME_</code> and <code>ACT_TYPE_</code> are columns which keep their
   * names. Simpler rules break in ways nothing reports until a query runs - see
   * {@code PrefixedEngineSchema} of the integration tests.
   */
  private static final java.util.regex.Pattern OBJECT_NAME = java.util.regex.Pattern
      .compile("\\bACT_(?:(?:GE|HI|RE|RU|FK|IDX|UNIQ)_[A-Z0-9_]+|ID_[A-Z][A-Z0-9_]*)");

  /**
   * Applies Camunda's own DDL with every <code>ACT_</code> object name renamed - what
   * an application has to do to run an engine on a prefix, and deliberately NOT what
   * the adapter does: the rename covers tables, indexes and constraints of one
   * engine version, and the next version brings its own scripts along.
   */
  private static void createPrefixedSchema(
      final DataSource dataSource,
      final String prefix) {

    try (Connection connection = dataSource.getConnection()) {
      for (final var component : COMPONENTS) {
        final var ddl = OBJECT_NAME
            .matcher(ddlOf("h2", component))
            .replaceAll(match -> prefix + match.group());
        // the same reading Camunda's executeSchemaResource() does: comment lines are
        // dropped, a line ending with a semicolon ends a statement
        final var statement = new StringBuilder();
        for (final var rawLine : ddl.lines().toList()) {
          final var line = rawLine.trim();
          if (line.isEmpty() || line.startsWith("--") || line.startsWith("# ")) {
            continue;
          }
          statement
              .append(line)
              .append('\n');
          if (!line.endsWith(";")) {
            continue;
          }
          final var sql = statement.substring(0, statement.lastIndexOf(";"));
          statement.setLength(0);
          try (Statement jdbc = connection.createStatement()) {
            jdbc.execute(sql);
          }
        }
      }
    } catch (final SQLException e) {
      throw new IllegalStateException(e);
    }

  }

  /**
   * @param failure A caught exception
   * @return Its message and the messages of all of its causes - the table name of a
   *         failed engine build sits in the innermost one
   */
  private static String messages(
      final Throwable failure) {

    final var messages = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      messages
          .append(cause.getMessage())
          .append('\n');
    }
    return messages.toString();

  }

  @Test
  @DisplayName("Camunda's create scripts name the tables verbatim - in every dialect")
  public void createScriptsCarryFixedTableNames() {

    // the answer cannot differ by database: executeSchemaResource()
    // runs these files statement by statement, and none of them has a placeholder a
    // prefix could be substituted into
    for (final var database : List.of("h2", "postgres", "oracle", "mysql", "mssql", "db2")) {
      final var ddl = ddlOf(database, "engine");
      assertTrue(
          ddl.contains("create table ACT_GE_PROPERTY"),
          () -> "%s: the create script names the table verbatim".formatted(database));
      assertFalse(
          ddl.contains("${"),
          () -> "%s: no placeholder a table prefix could be substituted into".formatted(database));
    }

  }

  @Test
  @DisplayName("A prefixed engine with database-schema-update=true creates the tables UNPREFIXED and then fails")
  public void schemaUpdateIgnoresThePrefix() {

    final var dataSource = h2("table-prefix-schema-update");

    final var failure = assertThrows(
        Exception.class,
        () -> engineConfiguration(dataSource, "true", "NEW_").buildProcessEngine());

    // the failure comes from a QUERY against the prefixed table, not from a rejected
    // CREATE - exactly what Camunda does here
    assertTrue(
        messages(failure).contains("NEW_ACT_GE_PROPERTY"),
        () -> messages(failure));
    // and this is why: the schema was created under the DEFAULT names
    assertTrue(tableExists(dataSource, "ACT_GE_PROPERTY"), "unprefixed tables were created");
    assertFalse(tableExists(dataSource, "NEW_ACT_GE_PROPERTY"), "no prefixed table was created");

  }

  @Test
  @DisplayName("A prefix naming a schema behaves the same way")
  public void aPrefixNamingASchemaIsIgnoredAsWell() {

    // 'MY_SCHEMA.' is the multi-tenancy shape of Camunda's documentation; the engine
    // derives databaseSchema from it, and the DDL still creates ACT_* wherever the
    // connection points
    final var dataSource = h2("table-prefix-schema;INIT=create schema if not exists NEW_ENGINE");

    final var failure = assertThrows(
        Exception.class,
        () -> engineConfiguration(dataSource, "true", "NEW_ENGINE.").buildProcessEngine());

    assertTrue(messages(failure).contains("ACT_GE_PROPERTY"), () -> messages(failure));
    assertTrue(tableExists(dataSource, "ACT_GE_PROPERTY"), "unprefixed tables were created");

  }

  @Test
  @DisplayName("An engine whose prefixed tables exist starts and runs")
  public void aPrefixedEngineRunsOnTablesWhichExist() {

    final var dataSource = h2("table-prefix-prepared");
    createPrefixedSchema(dataSource, "NEW_");

    final var engine = assertDoesNotThrow(
        () -> engineConfiguration(dataSource, "false", "NEW_").buildProcessEngine());
    try {
      // the engine works on its prefixed tables - runtime operations honor the prefix,
      // only the schema management does not
      assertTrue(engine.getRepositoryService().createDeploymentQuery().count() == 0);
      assertTrue(tableExists(dataSource, "NEW_ACT_GE_PROPERTY"));
      assertFalse(tableExists(dataSource, "ACT_GE_PROPERTY"), "nothing was created unprefixed");
    } finally {
      engine.close();
    }

  }

}
