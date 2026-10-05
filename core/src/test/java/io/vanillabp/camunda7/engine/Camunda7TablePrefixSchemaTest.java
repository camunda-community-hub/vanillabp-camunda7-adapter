package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The table-prefix startup check: an adapter id running on a table prefix learns while
 * booting that its tables have to be there, instead of dying on a MyBatis query
 * against a table nobody created.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7TablePrefixSchemaTest {

  private static DataSource h2(
      final String name) {

    final var dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name));
    return dataSource;

  }

  /**
   * The marker tables Camunda's schema check asks for - created empty here, which is
   * all this check looks at.
   */
  private static void createMarkerTables(
      final DataSource dataSource,
      final String prefix,
      final String... tables) {

    try (var connection = dataSource.getConnection()) {
      for (final var table : tables) {
        try (Statement statement = connection.createStatement()) {
          statement.execute("create table %s%s (ID_ varchar(64))".formatted(prefix, table));
        }
      }
    } catch (final SQLException e) {
      throw new IllegalStateException(e);
    }

  }

  private static Camunda7EngineProperties properties(
      final String tablePrefix,
      final String databaseSchemaUpdate) {

    final var properties = new Camunda7EngineProperties();
    properties.setTablePrefix(tablePrefix);
    properties.setDatabaseSchemaUpdate(databaseSchemaUpdate);
    return properties;

  }

  private static final String[] ALL_MARKER_TABLES = {
      "ACT_RU_EXECUTION", "ACT_HI_PROCINST", "ACT_ID_USER", "ACT_RE_CASE_DEF", "ACT_HI_CASEINST", "ACT_RE_DECISION_DEF", "ACT_HI_DECINST"
  };

  @Test
  @DisplayName("An adapter id without a prefix is none of this check's business")
  public void withoutAPrefixNothingIsChecked() {

    assertDoesNotThrow(
        () -> Camunda7TablePrefixSchema.validate("c7", properties(null, "true"), null));
    assertDoesNotThrow(
        () -> Camunda7TablePrefixSchema.validate("c7", properties("  ", "true"), null));

  }

  @Test
  @DisplayName("A prefix plus a creating database-schema-update fails, naming both and the ways on")
  public void aPrefixWithSchemaUpdateFails() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> Camunda7TablePrefixSchema
            .validate("c7-new", properties("NEW_", "true"), h2("prefix-check-schema-update")));

    final var message = exception.getMessage();
    assertTrue(message.contains("'c7-new'"), () -> message);
    assertTrue(message.contains("'NEW_'"), () -> message);
    assertTrue(message.contains("'database-schema-update: true'"), () -> message);
    assertTrue(message.contains("the application's default datasource"), () -> message);
    assertTrue(message.contains("vanillabp.adapters.c7-new.database-schema-update: false"), () -> message);
    assertTrue(message.contains("vanillabp.adapters.c7-new.data-source-name"), () -> message);

  }

  @Test
  @DisplayName("Every value but 'false' creates tables, so every one of them is refused")
  public void everyCreatingValueIsRefused() {

    assertTrue(Camunda7TablePrefixSchema.createsTables(null));
    assertTrue(Camunda7TablePrefixSchema.createsTables("true"));
    assertTrue(Camunda7TablePrefixSchema.createsTables("create-drop"));
    assertTrue(Camunda7TablePrefixSchema.createsTables("drop-create"));
    assertFalse(Camunda7TablePrefixSchema.createsTables("false"));
    assertFalse(Camunda7TablePrefixSchema.createsTables(" FALSE "));

  }

  @Test
  @DisplayName("Missing prefixed tables fail the boot, and the message names them")
  public void missingTablesFailTheBoot() {

    final var dataSource = h2("prefix-check-missing");
    // the engine tables are there, the decision ones are not - which is what an
    // application applying only a part of Camunda's statements ends up with
    createMarkerTables(dataSource, "NEW_", "ACT_RU_EXECUTION", "ACT_HI_PROCINST", "ACT_ID_USER");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> Camunda7TablePrefixSchema.validate("c7-new", properties("NEW_", "false"), dataSource));

    final var message = exception.getMessage();
    assertTrue(message.contains("NEW_ACT_RE_DECISION_DEF"), () -> message);
    assertTrue(message.contains("NEW_ACT_HI_DECINST"), () -> message);
    assertFalse(message.contains("NEW_ACT_RU_EXECUTION"), () -> message);
    assertTrue(message.contains("data-source-name"), () -> message);

  }

  @Test
  @DisplayName("A named datasource is named in the message")
  public void theNamedDataSourceIsNamed() {

    final var properties = properties("NEW_", "false");
    properties.setDataSourceName("legacy");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> Camunda7TablePrefixSchema.validate("c7-new", properties, h2("prefix-check-named")));

    assertTrue(exception.getMessage().contains("the datasource 'legacy'"), exception::getMessage);

  }

  @Test
  @DisplayName("Tables which are there let the boot pass")
  public void tablesWhichExistPass() {

    final var dataSource = h2("prefix-check-complete");
    createMarkerTables(dataSource, "NEW_", ALL_MARKER_TABLES);

    assertDoesNotThrow(
        () -> Camunda7TablePrefixSchema.validate("c7-new", properties("NEW_", "false"), dataSource));

  }

  @Test
  @DisplayName("A prefix naming a schema is looked up in that schema")
  public void aPrefixNamingASchemaIsLookedUpThere() {

    // Camunda derives databaseSchema from a prefix containing a dot, and this check
    // asks the metadata the same way - a table of the same name in the DEFAULT schema
    // must not count as present
    final var dataSource = h2("prefix-check-schema;INIT=create schema if not exists NEW_ENGINE");
    createMarkerTables(dataSource, "", ALL_MARKER_TABLES);

    final var missing = Camunda7TablePrefixSchema.missingTables(dataSource, "NEW_ENGINE.");
    assertEquals(ALL_MARKER_TABLES.length, missing.size(), () -> String.valueOf(missing));
    assertTrue(missing.contains("NEW_ENGINE.ACT_RU_EXECUTION"), () -> String.valueOf(missing));

    createMarkerTables(dataSource, "NEW_ENGINE.", ALL_MARKER_TABLES);
    assertTrue(
        Camunda7TablePrefixSchema.missingTables(dataSource, "NEW_ENGINE.").isEmpty(),
        "the tables of the schema are found");

  }

  @Test
  @DisplayName("A database which cannot be read fails with a message naming the prefix")
  public void anUnreachableDatabaseIsReported() {

    final var dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:prefix-check-unreachable;IFEXISTS=TRUE");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> Camunda7TablePrefixSchema.missingTables(dataSource, "NEW_"));

    assertTrue(exception.getMessage().contains("'NEW_'"), exception::getMessage);

  }

}
