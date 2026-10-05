package io.vanillabp.camunda7.engine;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import lombok.extern.slf4j.Slf4j;

/**
 * What <code>vanillabp.adapters.&lt;id&gt;.table-prefix</code> means, and the check
 * which says so while the application boots.
 * <p>
 * A prefixed engine RUNS: every runtime statement Camunda issues goes through MyBatis,
 * which prepends the prefix. Creating the tables is the part Camunda leaves out, and it
 * says so itself - {@code ProcessEngineConfigurationImpl#setDatabaseTablePrefix}:
 * <i>"the prefix is not respected by automatic database schema management. If you use
 * DB_SCHEMA_UPDATE_CREATE_DROP or DB_SCHEMA_UPDATE_TRUE, activiti will create the
 * database tables using the default names, regardless of the prefix configured
 * here."</i> The schema management runs the DDL files shipped with the engine
 * ({@code org/camunda/bpm/engine/db/create/activiti.<database>.create.<component>.sql})
 * statement by statement, and those statements name the tables verbatim, in every
 * dialect. {@code Camunda7TablePrefixEngineBehaviourTest} records all of it.
 * <p>
 * So <code>table-prefix</code> means: the tables of that prefix exist already. Left
 * alone, an engine configured with a prefix and the default
 * <code>database-schema-update: true</code> creates a full set of UNPREFIXED
 * <code>ACT_*</code> tables in the shared database and then dies on its first query
 * against the prefixed ones - a MyBatis stack trace naming neither the adapter id nor
 * the prefix. This class turns that into a message which names both and the way on,
 * and it runs BEFORE the engine is built, so the stray tables are never written.
 * <p>
 * Why a prefix means the tables already exist rather than that the adapter will create them is
 * decision 7 in the repository's DECISIONS.md.
 */
@Slf4j
public final class Camunda7TablePrefixSchema {

  /**
   * The tables Camunda's own schema check asks for, one per engine component, in the
   * order of {@code AbstractPersistenceSession#dbSchemaUpdate}. All seven components
   * are enabled by default, and the adapter switches none of them off.
   */
  private static final Map<String, String> COMPONENT_TABLES = new LinkedHashMap<>();

  static {
    COMPONENT_TABLES.put("engine", "ACT_RU_EXECUTION");
    COMPONENT_TABLES.put("history", "ACT_HI_PROCINST");
    COMPONENT_TABLES.put("identity", "ACT_ID_USER");
    COMPONENT_TABLES.put("case.engine", "ACT_RE_CASE_DEF");
    COMPONENT_TABLES.put("case.history", "ACT_HI_CASEINST");
    COMPONENT_TABLES.put("decision.engine", "ACT_RE_DECISION_DEF");
    COMPONENT_TABLES.put("decision.history", "ACT_HI_DECINST");
  }

  private Camunda7TablePrefixSchema() {
  }

  /**
   * Whether the engine would create its tables with that setting. Everything Camunda does
   * not know is treated as creating, because a value nobody recognises is more likely a
   * typo than a decision to leave the schema alone.
   *
   * @param databaseSchemaUpdate The configured value of
   *          <code>database-schema-update</code>
   * @return Whether the engine would create tables with it. Camunda knows
   *         <code>false</code> and a handful of creating values
   *         (<code>true</code>, <code>create</code>, <code>create-drop</code>,
   *         <code>drop-create</code>); everything else is treated as creating,
   *         because a value this adapter does not know must not be assumed harmless
   */
  public static boolean createsTables(
      final String databaseSchemaUpdate) {

    return (databaseSchemaUpdate == null) || !"false".equalsIgnoreCase(databaseSchemaUpdate.trim());

  }

  /**
   * Whether this adapter id has its tables behind a prefix. A prefix is what lets two
   * engines share one database, and Camunda's own schema management ignores it, which is
   * why the two answers here belong together.
   *
   * @param tablePrefix A configured table prefix
   * @return Whether the adapter id runs on a prefix at all
   */
  public static boolean hasTablePrefix(
      final String tablePrefix) {

    return (tablePrefix != null) && !tablePrefix.isBlank();

  }

  /**
   * Fails the boot of an adapter id whose table prefix cannot work, with a message
   * naming the prefix, the database and both ways on. Does nothing for an adapter id
   * without a prefix - there the engine's schema management is in charge, as always.
   *
   * @param adapterId The adapter id
   * @param properties The adapter id's engine settings
   * @param dataSource The datasource the engine of this adapter id runs on
   */
  public static void validate(
      final String adapterId,
      final Camunda7EngineProperties properties,
      final DataSource dataSource) {

    final var tablePrefix = properties.getTablePrefix();
    if (!hasTablePrefix(tablePrefix)) {
      return;
    }

    if (createsTables(properties.getDatabaseSchemaUpdate())) {
      throw new IllegalStateException(
          """
              Camunda 7 adapter '%s' is configured with the table prefix '%s' \
              ('vanillabp.adapters.%s.table-prefix') and 'database-schema-update: %s'. Camunda \
              cannot create prefixed tables: its schema management executes the DDL shipped with \
              the engine, and those statements name the tables verbatim, so it would create a set \
              of ACT_* tables in %s - not the tables this adapter id reads. %s"""
              .formatted(
                  adapterId,
                  tablePrefix,
                  adapterId,
                  properties.getDatabaseSchemaUpdate(),
                  describeDataSource(properties),
                  waysOn(adapterId, tablePrefix)));
    }

    final var missing = missingTables(dataSource, tablePrefix);
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          """
              Camunda 7 adapter '%s' runs on the table prefix '%s' \
              ('vanillabp.adapters.%s.table-prefix') in %s, but these tables of it are missing: \
              %s. A prefixed engine needs a schema which is there already, because Camunda's own \
              schema management ignores the prefix. %s"""
              .formatted(
                  adapterId,
                  tablePrefix,
                  adapterId,
                  describeDataSource(properties),
                  String.join(", ", missing),
                  waysOn(adapterId, tablePrefix)));
    }

    log.info(
        "Camunda7[{}]: running on the table prefix '{}' in {} - its tables are there, and the "
            + "engine's schema management stays out of it ('database-schema-update: false')",
        adapterId,
        tablePrefix,
        describeDataSource(properties));

  }

  /**
   * The remedy both messages end with - written once, because both defects have the
   * same two answers.
   */
  private static String waysOn(
      final String adapterId,
      final String tablePrefix) {

    return """
        Two ways on:
          - create the tables of the prefix '%s' yourself and switch the engine's schema \
        management off ('vanillabp.adapters.%s.database-schema-update: false'). The statements \
        come with the engine, under 'org/camunda/bpm/engine/db/create/' respectively as the \
        Liquibase changelog 'org/camunda/bpm/engine/db/liquibase/camunda-changelog.xml' in \
        camunda-engine-<version>.jar, and the prefix has to be applied to them.
          - give this adapter id a database of its own \
        ('vanillabp.adapters.%s.data-source-name'), where the engine creates and upgrades its \
        schema itself and no prefix is needed."""
        .formatted(tablePrefix, adapterId, adapterId);

  }

  private static String describeDataSource(
      final Camunda7EngineProperties properties) {

    return properties.usesSeparateDataSource()
        ? "the datasource '%s'".formatted(properties.getDataSourceName())
        : "the application's default datasource";

  }

  /**
   * Which of the engine's component tables are missing under the prefix - asked the
   * way Camunda's own {@code DbSqlSession#isTablePresent} asks: through
   * {@link java.sql.DatabaseMetaData}, with a prefix naming a schema split off the
   * table name (Camunda derives {@code databaseSchema} from a prefix containing a
   * dot).
   *
   * @param dataSource The engine's datasource
   * @param tablePrefix The configured prefix
   * @return The missing tables, prefix included, in the engine's own order
   */
  static List<String> missingTables(
      final DataSource dataSource,
      final String tablePrefix) {

    final var dot = tablePrefix.indexOf('.');
    final var schema = dot < 0
        ? null
        : tablePrefix.substring(0, dot);
    final var prefixWithoutSchema = dot < 0
        ? tablePrefix
        : tablePrefix.substring(dot + 1);

    final var missing = new LinkedList<String>();
    try (Connection connection = dataSource.getConnection()) {
      final var metaData = connection.getMetaData();
      for (final var table : COMPONENT_TABLES.values()) {
        final var tableName = prefixWithoutSchema + table;
        if (!tableExists(metaData, schema, tableName)) {
          missing.add(tablePrefix + table);
        }
      }
    } catch (final SQLException e) {
      throw new IllegalStateException(
          "Camunda 7: cannot read the database metadata to check the tables of the prefix '%s'"
              .formatted(tablePrefix), e);
    }
    return missing;

  }

  /**
   * Asks the metadata in every casing a database may store identifiers in - the
   * engine lowercases for PostgreSQL and leaves the name alone everywhere else, and
   * this check runs before the engine determined which database it talks to.
   */
  private static boolean tableExists(
      final java.sql.DatabaseMetaData metaData,
      final String schema,
      final String tableName) throws SQLException {

    for (final var name : List.of(tableName, tableName.toUpperCase(), tableName.toLowerCase())) {
      try (var tables = metaData.getTables(null, schema, name, new String[]{
          "TABLE"
      })) {
        if (tables.next()) {
          return true;
        }
      }
      if (schema == null) {
        continue;
      }
      // a schema which is not the one the connection points at may be spelled in the
      // other casing as well
      try (var tables = metaData.getTables(null, schema.toUpperCase(), name, new String[]{
          "TABLE"
      })) {
        if (tables.next()) {
          return true;
        }
      }
    }
    return false;

  }

}
