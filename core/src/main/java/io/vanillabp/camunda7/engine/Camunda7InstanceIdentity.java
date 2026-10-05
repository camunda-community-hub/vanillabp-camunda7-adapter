package io.vanillabp.camunda7.engine;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Function;

/**
 * What makes two configured <code>camunda7</code> adapter ids DIFFERENT engines:
 * the database they run on. Camunda 7 is embedded, so two ids sharing
 * one datasource AND one table prefix are literally the same engine state -
 * configuring them as separate adapters is a defect.
 * <p>
 * Two ids are distinguishable if they use
 * <ul>
 * <li>different datasources (<code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>),
 * or</li>
 * <li>different engine table prefixes
 * (<code>vanillabp.adapters.&lt;id&gt;.table-prefix</code>) - which lets two engines
 * share ONE datasource, exactly the migration setup on a single database. Such an
 * engine needs a schema which exists already, see
 * {@link Camunda7TablePrefixSchema}.</li>
 * </ul>
 *
 * @param dataSourceName The configured datasource name or <code>null</code> for the
 *          application's default datasource
 * @param tablePrefix The configured engine table prefix or <code>null</code> for
 *          the engine's default (no prefix)
 */
public record Camunda7InstanceIdentity(
                                       String dataSourceName,
                                       String tablePrefix) {

  private String describeDataSource() {

    return (dataSourceName == null) || dataSourceName.isBlank()
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
   * Fails the boot if two of the given adapter ids would run on the same database
   * AND table prefix (see the class comment). Called through the adapter SPI hook
   * {@code AdapterDeploymentService#validateDistinctAdapterInstances}.
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
