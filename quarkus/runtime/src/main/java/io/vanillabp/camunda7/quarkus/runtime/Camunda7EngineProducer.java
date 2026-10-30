package io.vanillabp.camunda7.quarkus.runtime;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.config.ConfigProvider;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.runtime.DataSourcesJdbcBuildTimeConfig;
import io.quarkus.agroal.runtime.TransactionIntegration;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.camunda7.Camunda7Adapter;
import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import jakarta.transaction.TransactionManager;

/**
 * Produces the {@link Camunda7QuarkusEngineRegistry}: ONE embedded engine per configured
 * {@code camunda7} adapter id. The engines are built EAGERLY at startup, because the
 * {@link Camunda7StartupObserver} forces this producer on {@code StartupEvent}. So
 * configuration defects show up at boot, with guiding messages:
 * <ul>
 *   <li>each id's engine runs on the application's default Agroal datasource. Or it runs
 *       on a declared NAMED datasource (<code>quarkus.datasource.&lt;name&gt;.*</code>),
 *       set in <code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>. An unknown
 *       name fails the boot and lists the declared names.</li>
 *   <li>two embedded engines on one set of tables are the same engine state. More than
 *       one {@code camunda7} id on the SAME datasource AND the same
 *       <code>table-prefix</code> fails the boot, naming the remedies. A distinct
 *       <code>table-prefix</code> on a shared datasource is allowed. The check is not made
 *       here but in {@code validateDistinctAdapterInstances}, which the platform's
 *       deployment pipeline calls.</li>
 *   <li>an engine on a named datasource shares its transactions with the application's
 *       datasource. At most one of the two may be without XA
 *       (<code>quarkus.datasource.&lt;name&gt;.jdbc.transactions: xa</code>). Otherwise the
 *       boot fails and names the key.</li>
 * </ul>
 * The set of adapter ids comes from the platform's core properties: the adapter ids of
 * type {@code camunda7}. The overlay map ({@link VanillaBpCamunda7Properties}) only looks
 * up the settings of an id that is known. The
 * {@link #close(Camunda7QuarkusEngineRegistry) disposer} closes the registry when the
 * application shuts down.
 */
@ApplicationScoped
public class Camunda7EngineProducer {

  /**
   * Quarkus builds the bean to call the producers below. It keeps no state. What they
   * return are beans of their own, and they live as long as the application does.
   */
  public Camunda7EngineProducer() {

  }

  /**
   * Builds one engine per configured adapter id, at startup and not on first use. A
   * datasource nobody declared, or a schema the engine cannot work with, is a
   * configuration defect. It belongs in the boot log, not in the first workflow.
   *
   * @param properties The platform's own configuration, which is where the adapter ids
   *          come from
   * @param transactionManager The Narayana transaction manager every engine command joins
   * @param workflowTaskRegistry What the core knows about the application's methods
   * @param dataSources The declared Agroal datasources, which an adapter id picks one of
   * @return The engines, keyed by adapter id
   */
  @Produces
  @Singleton
  public Camunda7QuarkusEngineRegistry camunda7EngineRegistry(
      final MigrationAdapterProperties properties,
      final TransactionManager transactionManager,
      final WorkflowTaskRegistry workflowTaskRegistry,
      @Any final Instance<AgroalDataSource> dataSources) {

    final var overlay = ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(VanillaBpCamunda7Properties.class);

    final var camunda7AdapterIds = properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> Camunda7Adapter.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .sorted()
        .toList();

    // checked for all ids before the first engine is built. So one boot names every id
    // which needs XA, and no engine is left open when the boot stops
    final var transactionsWithoutXa = camunda7AdapterIds
        .stream()
        .map(adapterId -> {
          final var keys = overlay.adapters().get(adapterId);
          final var dataSourceName = (keys == null)
              ? null
              : keys.dataSourceName().orElse(null);
          resolveDataSource(dataSources, adapterId, dataSourceName);
          return transactionWithoutXa(dataSources, adapterId, dataSourceName);
        })
        .flatMap(Optional::stream)
        .toList();
    if (!transactionsWithoutXa.isEmpty()) {
      throw new IllegalStateException(String.join("\n", transactionsWithoutXa));
    }

    final var engines = new LinkedHashMap<String, Camunda7QuarkusEngineHolder>();
    camunda7AdapterIds
        .forEach(adapterId -> {
          final var keys = overlay.adapters().get(adapterId);
          final var dataSourceName = (keys == null)
              ? null
              : keys.dataSourceName().orElse(null);
          final var dataSource = resolveDataSource(dataSources, adapterId, dataSourceName);
          try {
            engines.put(adapterId, new Camunda7QuarkusEngineHolder(
                adapterId, toEngineProperties(
                    keys), dataSource, !io.vanillabp.camunda7.engine.Camunda7EngineProperties
                        .isDefaultDataSourceName(
                            dataSourceName), transactionManager, workflowTaskRegistry, workflowTaskRegistry, workflowTaskRegistry));
          } catch (final RuntimeException e) {
            throw new IllegalStateException(
                """
                    Camunda 7 adapter '%s': building the embedded engine on datasource '%s' failed! \
                    Check the datasource configuration ('quarkus.datasource.%s') and the engine keys \
                    'vanillabp.adapters.%s.*'."""
                    .formatted(
                        adapterId,
                        dataSourceName != null
                            ? dataSourceName
                            : "<default>",
                        dataSourceName != null
                            ? dataSourceName
                                + ".*"
                            : "*",
                        adapterId), e);
          }
        });

    return new Camunda7QuarkusEngineRegistry(engines);

  }

  /**
   * Stops every engine with the application. Each holder stops its job executor before it
   * closes its engine, so nothing is acquired while the engine is going away.
   *
   * @param registry The registry being disposed of
   */
  public void close(
      @Disposes final Camunda7QuarkusEngineRegistry registry) {

    registry.close();

  }

  private static Camunda7EngineProperties toEngineProperties(
      final VanillaBpCamunda7Properties.Camunda7AdapterKeys keys) {

    final var properties = new Camunda7EngineProperties();
    if (keys == null) {
      return properties;
    }
    keys.databaseSchemaUpdate().ifPresent(properties::setDatabaseSchemaUpdate);
    keys.historyTimeToLive().ifPresent(properties::setHistoryTimeToLive);
    keys.tablePrefix().ifPresent(properties::setTablePrefix);
    keys.serializationFormat().ifPresent(properties::setSerializationFormat);
    keys.sleepUntilSomethingIsDue().ifPresent(properties::setSleepUntilSomethingIsDue);
    keys.dbMetricsReporting().ifPresent(properties::setDbMetricsReporting);
    keys.dmnFeelLegacyBehavior().ifPresent(properties::setDmnFeelLegacyBehavior);
    // the named plugin sections are copied onto the platform-neutral model. So the core
    // builds and configures them the same way on both platforms
    properties
        .setEnginePlugins(
            keys
                .enginePlugins()
                .entrySet()
                .stream()
                .collect(
                    java.util.stream.Collectors
                        .toMap(
                            java.util.Map.Entry::getKey,
                            entry -> {
                              final var plugin = new io.vanillabp.camunda7.engine.Camunda7EnginePluginProperties();
                              plugin
                                  .setPluginClass(
                                      entry
                                          .getValue()
                                          .pluginClass()
                                          .orElse(null));
                              plugin
                                  .setProperties(
                                      entry
                                          .getValue()
                                          .properties());
                              return plugin;
                            })));
    return properties;

  }

  /**
   * Resolves the Agroal datasource the adapter id's engine runs on. That is the
   * application's default datasource. If <code>data-source-name</code> is configured, it
   * is the named datasource declared under <code>quarkus.datasource.&lt;name&gt;.*</code>.
   * A missing datasource fails with a guiding message.
   */
  private static AgroalDataSource resolveDataSource(
      final Instance<AgroalDataSource> dataSources,
      final String adapterId,
      final String dataSourceName) {

    // the explicit @Default literal is required. With named datasources present, an
    // unqualified select() on the @Any instance would be ambiguous.
    // several datasources declared: VanillaBP does not guess which one the engine runs
    // on. Not even the default datasource decides it, because an embedded engine writes
    // its ACT_* tables into whatever database it gets. The reserved value 'default'
    // names the default datasource explicitly.
    if (dataSourceName == null) {
      final var declaredDataSources = declaredDataSourceNames(dataSources);
      if (declaredDataSources.size() > 1) {
        throw new IllegalStateException(
            """
                Camunda 7 adapter '%s' runs embedded and needs a database, but the application \
                declares SEVERAL datasources: %s. Name the one this adapter id runs on:
                  vanillabp.adapters.%s.data-source-name: <datasource name>
                Use the reserved value 'default' for the application's default datasource. \
                (Two adapter ids may also share one datasource if each uses its own \
                'vanillabp.adapters.<id>.table-prefix' - Camunda does not create prefixed tables, \
                so those have to exist beforehand.)"""
                .formatted(adapterId, declaredDataSources, adapterId));
      }
    }

    final Instance<AgroalDataSource> selected = io.vanillabp.camunda7.engine.Camunda7EngineProperties
        .isDefaultDataSourceName(dataSourceName)
            ? dataSources.select(jakarta.enterprise.inject.Default.Literal.INSTANCE)
            : dataSources.select(new io.quarkus.agroal.DataSource.DataSourceLiteral(dataSourceName));
    if (!selected.isResolvable()) {
      if (io.vanillabp.camunda7.engine.Camunda7EngineProperties.isDefaultDataSourceName(dataSourceName)) {
        throw new IllegalStateException(
            """
                Camunda 7 adapter '%s' is configured ('vanillabp.adapters.%s.type: camunda7') but no \
                default datasource is available. Camunda 7 runs embedded and always needs a database - \
                configure 'quarkus.datasource.*', reference a named datasource via \
                'vanillabp.adapters.%s.data-source-name' or remove the adapter configuration."""
                .formatted(adapterId, adapterId, adapterId));
      }
      throw new IllegalStateException(
          """
              Camunda 7 adapter '%s' references the datasource '%s' \
              ('vanillabp.adapters.%s.data-source-name') but no such datasource is declared! Declare it \
              via 'quarkus.datasource.%s.*' (named Quarkus datasources are build-time-declared). \
              Declared datasources: %s."""
              .formatted(adapterId, dataSourceName, adapterId, dataSourceName, declaredDataSourceNames(dataSources)));
    }
    return selected.get();

  }

  /**
   * Says whether the engine of an adapter id on a NAMED datasource would share a
   * transaction with a second datasource while neither of them is an XA datasource.
   * <p>
   * The engine's job transaction also loads and saves the workflow aggregate, and the
   * platform's outbox and delivery log write to the default datasource. So two
   * datasources take part in one JTA transaction. Narayana takes in only one of them
   * without XA. The second one fails with "Exception in association of connection to
   * existing transaction". The task handler then runs, but the engine's job rolls back,
   * and a start through the outbox fails the same way. The boot itself goes through
   * without a sign of it, so this check stops it.
   * <p>
   * The second datasource is the default datasource, if there is one, and the datasource
   * of Hibernate ORM (<code>quarkus.hibernate-orm.datasource</code>). An engine on the
   * default datasource is not checked. Its transaction has one datasource only. An
   * application whose only datasource is the engine's passes as well. It has no second
   * datasource, and it runs without XA.
   *
   * @return The guiding message, or nothing if the engine's transactions are fine
   */
  private static Optional<String> transactionWithoutXa(
      final Instance<AgroalDataSource> dataSources,
      final String adapterId,
      final String dataSourceName) {

    if (io.vanillabp.camunda7.engine.Camunda7EngineProperties.isDefaultDataSourceName(dataSourceName)) {
      return Optional.empty();
    }
    final var config = ConfigProvider.getConfig();
    final var transactions = config
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(DataSourcesJdbcBuildTimeConfig.class)
        .dataSources();
    if (transactionsOf(transactions, dataSourceName) != TransactionIntegration.ENABLED) {
      return Optional.empty();
    }

    final var otherDataSources = new LinkedHashSet<String>();
    if (dataSources
        .select(jakarta.enterprise.inject.Default.Literal.INSTANCE)
        .isResolvable()) {
      otherDataSources.add(DataSourceUtil.DEFAULT_DATASOURCE_NAME);
    }
    config
        .getOptionalValue("quarkus.hibernate-orm.datasource", String.class)
        .ifPresent(otherDataSources::add);
    otherDataSources.remove(dataSourceName);

    return otherDataSources
        .stream()
        .filter(other -> transactionsOf(transactions, other) == TransactionIntegration.ENABLED)
        .findFirst()
        .map(other -> """
            Camunda 7 adapter '%s' runs on the datasource '%s' ('vanillabp.adapters.%s.data-source-name'). \
            The application uses the datasource '%s' in the same transactions. Neither of them is an XA \
            datasource, but a transaction takes in only one datasource without XA. The task handler would \
            run, and then the engine's job would roll back. A start through the outbox would fail the \
            same way. Make the engine's datasource an XA datasource:
              %s: xa
            The JDBC driver has to support XA (it provides a javax.sql.XADataSource). Making '%s' an XA \
            datasource instead works as well:
              %s: xa"""
            .formatted(
                adapterId,
                dataSourceName,
                adapterId,
                other,
                transactionsKeyOf(dataSourceName),
                other,
                transactionsKeyOf(other)));

  }

  private static TransactionIntegration transactionsOf(
      final Map<String, DataSourcesJdbcBuildTimeConfig.DataSourceJdbcOuterNamedBuildTimeConfig> transactions,
      final String dataSourceName) {

    final var dataSource = transactions.get(dataSourceName);
    return dataSource == null
        ? TransactionIntegration.ENABLED
        : dataSource
            .jdbc()
            .transactions();

  }

  private static String transactionsKeyOf(
      final String dataSourceName) {

    return DataSourceUtil.isDefault(dataSourceName)
        ? "quarkus.datasource.jdbc.transactions"
        : "quarkus.datasource.%s.jdbc.transactions".formatted(dataSourceName);

  }

  /**
   * The names of all declared Agroal datasources, for guiding messages. The unnamed
   * application datasource is listed as <code>&lt;default&gt;</code>.
   */
  private static List<String> declaredDataSourceNames(
      final Instance<AgroalDataSource> dataSources) {

    final var names = new LinkedList<String>();
    dataSources
        .handles()
        .forEach(handle -> handle
            .getBean()
            .getQualifiers()
            .forEach(qualifier -> {
              if (qualifier instanceof io.quarkus.agroal.DataSource dataSourceQualifier) {
                names.add(dataSourceQualifier.value());
              } else if (qualifier instanceof jakarta.enterprise.inject.Default) {
                names.add("<default>");
              }
            }));
    return names;

  }

}
