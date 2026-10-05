package io.vanillabp.camunda7.it;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;

/**
 * Creates the engine tables of a table prefix, the way an application running a
 * prefixed adapter id has to: Camunda's own statements with the prefix
 * applied to them. It sits in the test sources on purpose - the adapter does not do
 * this, because the rename covers the tables, indexes and constraints of ONE engine
 * version, and the engine's version bookkeeping stays the engine's business.
 */
final class PrefixedEngineSchema {

  /**
   * The engine components enabled by default, in the order Camunda creates them.
   */
  private static final List<String> COMPONENTS = List.of(
      "engine", "history", "identity", "case.engine", "case.history", "decision.engine",
      "decision.history");

  private PrefixedEngineSchema() {
  }

  static void create(
      final String jdbcUrl,
      final String prefix) {

    // the user Spring Boot connects an embedded database with - H2 binds an in-memory
    // database to whoever opens it first, so a connection under another name would
    // leave the application locked out of its own datasource
    try (var connection = DriverManager.getConnection(jdbcUrl, "sa", "")) {
      for (final var component : COMPONENTS) {
        final var ddl = prefixed(ddlOf(component), prefix);
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
      throw new IllegalStateException(
          "cannot create the tables of the prefix '%s'".formatted(prefix), e);
    }

  }


  /**
   * Which <code>ACT_</code> names are objects and which are columns. Camunda's tables
   * live under <code>ACT_GE_</code>, <code>ACT_HI_</code>, <code>ACT_RE_</code>,
   * <code>ACT_RU_</code> and <code>ACT_ID_</code>, its indexes and constraints under
   * <code>ACT_IDX_</code>, <code>ACT_FK_</code> and <code>ACT_UNIQ_</code> - while
   * <code>ACT_ID_</code>, <code>ACT_INST_ID_</code>, <code>ACT_INST_STATE_</code>,
   * <code>ACT_NAME_</code> and <code>ACT_TYPE_</code> are COLUMNS and have to keep
   * their names.
   * <p>
   * Two shorter rules were tried first and both broke: renaming every
   * <code>ACT_</code> renames the columns along and the first query fails on one,
   * and taking "ends with an underscore" for a column leaves the index
   * <code>ACT_IDX_EVENT_SUBSCR_CONFIG_</code> unrenamed, where it collides with the
   * unprefixed engine of the same database. Nothing about that is visible until it
   * happens, which is why this transformation belongs to whoever owns the schema and
   * not to the adapter.
   */
  private static final java.util.regex.Pattern OBJECT_NAME = java.util.regex.Pattern
      .compile("\\bACT_(?:(?:GE|HI|RE|RU|FK|IDX|UNIQ)_[A-Z0-9_]+|ID_[A-Z][A-Z0-9_]*)");

  static String prefixed(
      final String ddl,
      final String prefix) {

    return OBJECT_NAME
        .matcher(ddl)
        .replaceAll(match -> prefix + match.group());

  }

  private static String ddlOf(
      final String component) {

    final var resource = "org/camunda/bpm/engine/db/create/activiti.h2.create.%s.sql"
        .formatted(component);
    try (InputStream in = ProcessEngine.class
        .getClassLoader()
        .getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("the engine has to ship "
            + resource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new IllegalStateException(e);
    }

  }

}
