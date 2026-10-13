package io.vanillabp.camunda7.engine;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;

import io.vanillabp.integration.adapter.spi.AddressedSystem;

/**
 * What makes two configured <code>camunda7</code> adapter ids DIFFERENT engines:
 * the database they run on. Camunda 7 is embedded. So two ids which share one
 * datasource AND one table prefix are really the same engine state. Configuring
 * them as separate adapters is a defect.
 * <p>
 * Two ids are distinguishable if they use
 * <ul>
 * <li>different datasources (<code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>),
 * or</li>
 * <li>different engine table prefixes
 * (<code>vanillabp.adapters.&lt;id&gt;.table-prefix</code>). With them two engines
 * share ONE datasource. This is the migration setup on a single database. Such an
 * engine needs a schema which exists already, see
 * {@link Camunda7TablePrefixSchema}.</li>
 * </ul>
 *
 * @param dataSourceName The configured datasource name or <code>null</code> for the
 *          application's default datasource. The reserved name <code>default</code> means
 *          the default datasource as well
 * @param tablePrefix The configured engine table prefix or <code>null</code> for
 *          the engine's default (no prefix)
 */
public record Camunda7InstanceIdentity(
                                       String dataSourceName,
                                       String tablePrefix) {

  private String describeDataSource() {

    // 'default' names the application's datasource too, so it must not count as another one
    return Camunda7EngineProperties.isDefaultDataSourceName(dataSourceName)
        ? "<the application's default datasource>"
        : dataSourceName;

  }

  private String describeTablePrefix() {

    return (tablePrefix == null) || tablePrefix.isBlank()
        ? "<no table prefix>"
        : tablePrefix;

  }

  /**
   * The key two ids are compared by: datasource plus table prefix.
   */
  private String key() {

    return describeDataSource()
        + "|"
        + describeTablePrefix();

  }

  /**
   * The same comparison for the core, which compares ids across adapter types. A fork of
   * Camunda 7 uses the same tables (<code>ACT_*</code>). So a <code>camunda7</code> id and
   * an id of a fork adapter on one datasource and one prefix are the same engine state, and
   * each job executor would run the jobs of the other one. The key therefore names no
   * adapter type, and an adapter generated from this one builds the same key. See decision
   * 47 in the repository's DECISIONS.md.
   *
   * @return The tables this identity addresses, as the adapter SPI compares them
   */
  public AddressedSystem addressedSystem() {

    return new AddressedSystem(
        "embedded engine tables|"
            + key(), "datasource %s, table prefix %s".formatted(describeDataSource(), describeTablePrefix()), """
                give each one its own datasource ('vanillabp.adapters.<id>.data-source-name'), or \
                its own engine tables on the shared datasource ('vanillabp.adapters.<id>.table-prefix', \
                with the tables created beforehand and 'vanillabp.adapters.<id>.database-schema-update: \
                false')""");

  }

  /**
   * Fails the boot if two of the given adapter ids would run on the same database
   * AND table prefix (see the class comment). The adapter SPI hook
   * {@code AdapterDeploymentService#validateDistinctAdapterInstances} calls it.
   *
   * @param adapterIds The configured adapter ids of type <code>camunda7</code>
   * @param identityResolver Resolves an adapter id's identity from the
   *          configuration (platform-specific binding)
   */
  public static void validateDistinct(
      final List<String> adapterIds,
      final Function<String, Camunda7InstanceIdentity> identityResolver) {

    if ((adapterIds == null) || (adapterIds.size() < 2) || (identityResolver == null)) {
      return;
    }

    final var idsByIdentity = new LinkedHashMap<Camunda7InstanceIdentity, List<String>>();
    adapterIds.forEach(
        adapterId -> idsByIdentity
            .computeIfAbsent(identityResolver.apply(adapterId), identity -> new LinkedList<>())
            .add(adapterId));

    idsByIdentity.forEach((
        identity,
        idsSharingIt) -> {
      if (idsSharingIt.size() < 2) {
        return;
      }
      throw new IllegalStateException(
          """
              The Camunda 7 adapters '%s' would run on the SAME engine database (datasource %s, \
              table prefix %s)! Two embedded engines on one schema are the same engine state - \
              configuring them as separate adapters is an error. Make them distinguishable:
                - give each one its own database/schema by referencing an own datasource \
              ('vanillabp.adapters.<id>.data-source-name'), or
                - let them share one datasource but use separate engine tables \
              ('vanillabp.adapters.<id>.table-prefix'). Camunda does not create prefixed tables, so \
              such an id needs its tables created beforehand and \
              'vanillabp.adapters.<id>.database-schema-update: false',
              or remove all but one of these adapters."""
              .formatted(
                  String.join("', '", idsSharingIt),
                  identity.describeDataSource(),
                  identity.describeTablePrefix()));
    });

  }

  /**
   * @return A key-based equality: two identities are equal if datasource and table
   *         prefix are equal (blank and <code>null</code> alike)
   */
  @Override
  public boolean equals(
      final Object other) {

    return (other instanceof Camunda7InstanceIdentity identity) && key().equals(identity.key());

  }

  @Override
  public int hashCode() {

    return key().hashCode();

  }

}
