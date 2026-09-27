package io.vanillabp.camunda7.engine;

/**
 * Per-adapter-id engine settings of the Camunda 7 adapter, living at the canonical
 * per-adapter location <code>vanillabp.adapters.&lt;id&gt;.*</code> (contributed to
 * the shared configuration tree via the platform-specific overlay - see the Spring
 * Boot module's <code>VanillaBpCamunda7Properties</code> and the Quarkus module's
 * <code>@ConfigMapping</code> overlay):
 * <ul>
 *   <li><code>database-schema-update</code> - create/upgrade the engine schema on
 *       boot (engine values, e.g. <code>true</code>, <code>false</code>,
 *       <code>create-drop</code>); default <code>true</code>;</li>
 *   <li><code>history-time-to-live</code> - engine-wide default history time to
 *       live (Camunda 7.24 rejects deployments of processes without one); default
 *       <code>P180D</code>, overridable per process via
 *       <code>camunda:historyTimeToLive</code>;</li>
 *   <li><code>data-source-name</code> - OPTIONAL name of an
 *       application-/runtime-provided datasource this adapter id's embedded engine
 *       runs on (setting up datasources is deliberately NOT VanillaBP's concern -
 *       the adapter never builds its own pool). Spring Boot: the name of a
 *       {@code DataSource} BEAN; Quarkus: the name of a declared named Agroal
 *       datasource (<code>quarkus.datasource.&lt;name&gt;.*</code>). Without it
 *       the engine shares the application's default datasource and joins the
 *       caller's transaction (the embedded-engine guarantee). With it the engine
 *       runs on its own schema - required for engine-side-by-side migrations (two
 *       embedded engines must never share one schema) - and starting workflows
 *       uses VanillaBP's two-phase pattern (see
 *       <code>Camunda7ProcessService</code>).</li>
 *   <li><code>accept-unscoped-identifiers</code> - OPTIONAL acknowledgement that the
 *       application's identifiers are unique across all of its workflow modules, which
 *       silences the WARN logged while the name-clash-avoidance mode <code>none</code>
 *       applies (this adapter's default mode); default <code>false</code>;</li>
 *   <li><code>table-prefix</code> - OPTIONAL prefix of the engine's database tables
 *       (engine setting <code>databaseTablePrefix</code>). It lets two adapter ids
 *       share ONE datasource while running separate engines - the side-by-side
 *       migration setup on a single database. <b>The tables of the prefix have to
 *       exist before the application starts</b>: Camunda's schema management
 *       ignores the prefix and would create a set of unprefixed
 *       <code>ACT_*</code> tables instead (its own words in
 *       {@code ProcessEngineConfigurationImpl#setDatabaseTablePrefix}, recorded by
 *       {@code Camunda7TablePrefixEngineBehaviourTest}). A prefixed adapter id
 *       therefore also needs <code>database-schema-update: false</code>, and
 *       {@link Camunda7TablePrefixSchema} says so while the application boots
 *       rather than letting the engine fail on its first query.</li>
 *   <li><code>sleep-until-something-is-due</code> - OPTIONAL: the job executor waits
 *       until the next job is due instead of polling every 5 to 60 seconds; default
 *       <code>false</code>, see {@link Camunda7JobExecutorSleep};</li>
 *   <li><code>allow-listeners</code> - OPTIONAL: whether the execution listeners somebody
 *       MODELLED are served by <code>@WorkflowTask</code> methods, resolvable per workflow
 *       module and workflow, default <code>false</code>, see
 *       {@link io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver};</li>
 *   <li><code>db-metrics-reporting</code> - OPTIONAL: whether the engine's metrics
 *       reporter writes its counters to the database every 900 seconds. Unset means
 *       the opposite of the sleep, so an engine which is allowed to sleep is not woken
 *       by its own metrics.</li>
 * </ul>
 */
public class Camunda7EngineProperties {

  /**
   * The platform integration builds one per configured adapter id and fills it through the
   * setters, so a key a deployment says nothing about keeps the default written next to it.
   */
  public Camunda7EngineProperties() {

  }

  /** What the engine does to its schema on boot. */
  private String databaseSchemaUpdate = "true";

  /** The engine-wide default history time to live. */
  private String historyTimeToLive = "P180D";

  /** The name of the datasource this adapter id's engine runs on. */
  private String dataSourceName;

  /** The prefix of the engine's database tables. */
  private String tablePrefix;

  /**
   * The serialization format a shared value the engine has no variable type for is stored
   * in, e.g. {@code application/json} (the SPIN JSON dataformat) or
   * {@code application/xstream} (camunda-xstream). That is a nested value and a number
   * this engine cannot store as itself. It is applied twice: to the engine's
   * {@code defaultSerializationFormat}, so an application configures the format once, and
   * per written variable, so a workflow module or a single workflow may override it
   * ({@code vanillabp.workflow-modules.<module>.adapters.<id>.serialization-format},
   * {@code vanillabp.workflow-modules.<module>.workflows.<workflow>.adapters.<id>.serialization-format}).
   * <p>
   * The matching dataformat has to be on the classpath - that dependency and its own
   * settings belong to the application, VanillaBP only names the format.
   */
  private String serializationFormat;

  /**
   * The Camunda engine plugins of this adapter id, each a named section naming a class and
   * carrying its own properties - which Camunda itself applies (see
   * {@link Camunda7EnginePlugins}). This is how a serialization dataformat
   * (camunda-xstream, SPIN) reaches an engine VanillaBP builds, and it is per adapter id,
   * so two embedded engines can carry different plugins.
   */
  private java.util.Map<String, Camunda7EnginePluginProperties> enginePlugins = java.util.Map.of();

  /**
   * The Camunda tenant a workflow module is deployed to under the name-clash
   * avoidance mode {@code by-adapter}. Unset (the default) means the
   * workflow module ID is the tenant - VanillaBP 1's behavior.
   */
  private String tenantId;

  /**
   * Whether the application states that its identifiers are unique across all of its
   * workflow modules, which is what the name-clash-avoidance mode {@code none} relies
   * on. It silences the WARN the adapter logs per workflow module while that mode
   * applies - a deliberate acknowledgement, not a log-level setting: with a wrong one,
   * two workflow modules address the same process definitions and tasks. Default
   * {@code false}.
   */
  private boolean acceptUnscopedIdentifiers = false;

  /**
   * Whether the execution listeners somebody MODELLED are served by <code>@WorkflowTask</code>
   * methods. Adapter-level base of the most-specific-wins resolution over three levels (workflow
   * &gt; workflow-module &gt; adapter), see
   * {@link io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver}. Default
   * <code>false</code>.
   * <p>
   * Without it a model carrying such a listener does not boot: the engine would evaluate the
   * listener's expression itself, and a workflow reaching the element either fails on a name
   * nothing resolves or runs a method the application never meant for that element. With it the
   * listener is a task like any other - a method is asked for and a method serving no listener is
   * reported - and what that costs is written into the boot log of every workflow module it
   * applies to.
   * <p>
   * There is deliberately no TASK level: that level is keyed by a task DEFINITION, and whether a
   * listener becomes a task at all is what this key decides.
   */
  private boolean allowListeners = false;

  /**
   * Whether this adapter id's job executor waits until the next job is due instead of
   * polling. An application which waits for a timer most of its life pays for every poll
   * on a database billed by active use, and a due date is an exact answer to the question
   * those polls keep asking. Off by default: replacing the timing of the engine's job
   * executor is not something to do to an application which did not ask for it.
   * <p>
   * This is one switch rather than two, for the sleeping and for the waking, because the
   * two make no sense apart. An executor which sleeps on a due date and nobody wakes is
   * worse than either half.
   */
  private boolean sleepUntilSomethingIsDue = false;

  /**
   * Whether the engine's metrics reporter writes its counters to the database every 900
   * seconds. Unset (the default) means the opposite of {@link #sleepUntilSomethingIsDue},
   * so the engine keeps Camunda's behaviour while it polls anyway and stops writing once
   * it is allowed to sleep. Set it to <code>true</code> to keep the metrics on a sleeping
   * engine, which costs a wake-up four times an hour. Metrics are still counted in memory
   * either way, only the writing stops.
   */
  private Boolean dbMetricsReporting;

  /**
   * What the engine does to its schema on boot, e.g. <code>true</code>,
   * <code>false</code> or <code>create-drop</code>.
   *
   * @return The configured value, <code>true</code> unless a deployment says otherwise
   */
  public String getDatabaseSchemaUpdate() {
    return databaseSchemaUpdate;
  }

  /**
   * What the engine does to its schema on boot.
   *
   * @param databaseSchemaUpdate An engine value, e.g. <code>create-drop</code>
   */
  public void setDatabaseSchemaUpdate(
      final String databaseSchemaUpdate) {
    this.databaseSchemaUpdate = databaseSchemaUpdate;
  }

  /**
   * The engine-wide default history time to live, which Camunda 7.24 asks every
   * deployed process for.
   *
   * @return The configured duration, <code>P180D</code> unless a deployment says otherwise
   */
  public String getHistoryTimeToLive() {
    return historyTimeToLive;
  }

  /**
   * The engine-wide default history time to live.
   *
   * @param historyTimeToLive An ISO-8601 duration, e.g. <code>P180D</code>
   */
  public void setHistoryTimeToLive(
      final String historyTimeToLive) {
    this.historyTimeToLive = historyTimeToLive;
  }

  /**
   * The name of the datasource this adapter id's engine runs on. Unset means the
   * application's default one, which is what lets the engine join the caller's
   * transaction.
   *
   * @return The configured name, <code>null</code> for the application's default datasource
   */
  public String getDataSourceName() {
    return dataSourceName;
  }

  /**
   * The name of the datasource this adapter id's engine runs on.
   *
   * @param dataSourceName The name of a datasource the application provides
   */
  public void setDataSourceName(
      final String dataSourceName) {
    this.dataSourceName = dataSourceName;
  }

  /**
   * The prefix of the engine's database tables, which lets two adapter ids run
   * separate engines on one datasource.
   *
   * @return The configured prefix, <code>null</code> for the plain table names
   */
  public String getTablePrefix() {
    return tablePrefix;
  }

  /**
   * The prefix of the engine's database tables.
   *
   * @param tablePrefix The prefix the tables of this engine carry
   */
  public void setTablePrefix(
      final String tablePrefix) {
    this.tablePrefix = tablePrefix;
  }

  /**
   * The serialization format of a shared value the engine has no variable type for,
   * e.g. <code>application/json</code>.
   *
   * @return The configured format, <code>null</code> where the engine's own default applies
   */
  public String getSerializationFormat() {
    return serializationFormat;
  }

  /**
   * The serialization format of a shared value the engine has no variable type for.
   *
   * @param serializationFormat A format the application brought a dataformat for
   */
  public void setSerializationFormat(
      final String serializationFormat) {
    this.serializationFormat = serializationFormat;
  }

  /**
   * The Camunda engine plugins of this adapter id, keyed by the name the application
   * gave the section.
   *
   * @return The plugin sections, empty where there are none
   */
  public java.util.Map<String, Camunda7EnginePluginProperties> getEnginePlugins() {
    return enginePlugins;
  }

  /**
   * The Camunda tenant a workflow module is deployed to under the name-clash-avoidance
   * mode {@code by-adapter}.
   *
   * @return The configured tenant, <code>null</code> where the workflow module id names it
   */
  public String getTenantId() {
    return tenantId;
  }

  /**
   * The Camunda tenant a workflow module is deployed to.
   *
   * @param tenantId The name of the tenant
   */
  public void setTenantId(
      final String tenantId) {
    this.tenantId = tenantId;
  }

  /**
   * Whether the application states that its identifiers are unique across all of its
   * workflow modules, which silences the WARN of the mode {@code none}.
   *
   * @return Whether the application acknowledged unscoped identifiers
   */
  public boolean isAcceptUnscopedIdentifiers() {
    return acceptUnscopedIdentifiers;
  }

  /**
   * Whether the application states that its identifiers are unique across all of its
   * workflow modules.
   *
   * @param acceptUnscopedIdentifiers The acknowledgement
   */
  public void setAcceptUnscopedIdentifiers(
      final boolean acceptUnscopedIdentifiers) {
    this.acceptUnscopedIdentifiers = acceptUnscopedIdentifiers;
  }

  /**
   * Whether the execution listeners somebody modelled are served by
   * <code>@WorkflowTask</code> methods. The adapter-level base of a resolution over
   * three levels.
   *
   * @return Whether modelled listeners become tasks for this adapter id
   */
  public boolean isAllowListeners() {
    return allowListeners;
  }

  /**
   * Whether the execution listeners somebody modelled are served by
   * <code>@WorkflowTask</code> methods.
   *
   * @param allowListeners Whether modelled listeners become tasks
   */
  public void setAllowListeners(
      final boolean allowListeners) {
    this.allowListeners = allowListeners;
  }

  /**
   * Whether this adapter id's job executor waits for the next job to fall due instead
   * of polling for it.
   *
   * @return Whether the job executor waits for a due date
   */
  public boolean isSleepUntilSomethingIsDue() {
    return sleepUntilSomethingIsDue;
  }

  /**
   * Whether this adapter id's job executor waits for the next job to fall due.
   *
   * @param sleepUntilSomethingIsDue Whether the job executor waits for a due date
   */
  public void setSleepUntilSomethingIsDue(
      final boolean sleepUntilSomethingIsDue) {
    this.sleepUntilSomethingIsDue = sleepUntilSomethingIsDue;
  }

  /**
   * Whether the engine's metrics reporter writes its counters to the database.
   * <code>null</code> follows {@link #sleepUntilSomethingIsDue}.
   *
   * @return The configured answer, <code>null</code> to follow the setting above
   */
  public Boolean getDbMetricsReporting() {
    return dbMetricsReporting;
  }

  /**
   * Whether the engine's metrics reporter writes its counters to the database.
   *
   * @param dbMetricsReporting The answer, <code>null</code> to follow the setting above
   */
  public void setDbMetricsReporting(
      final Boolean dbMetricsReporting) {
    this.dbMetricsReporting = dbMetricsReporting;
  }

  /**
   * Whether this engine's job executor waits for the next job instead of polling for it.
   * It is asked while the engine is built, and it decides the metrics answer below as
   * well.
   *
   * @return Whether the job executor of this adapter id waits until the next job is due
   */
  public boolean sleepsUntilSomethingIsDue() {

    return sleepUntilSomethingIsDue;

  }

  /**
   * Whether the metrics reporter writes its counters to the database. Unset follows the
   * answer above: an engine which is allowed to rest should not be woken four times an
   * hour by its own bookkeeping.
   *
   * @return Whether the engine's metrics reporter writes to the database
   */
  public boolean reportsMetricsToTheDatabase() {

    return (dbMetricsReporting == null)
        ? !sleepUntilSomethingIsDue
        : dbMetricsReporting.booleanValue();

  }

  /**
   * Keeps an empty map instead of <code>null</code>, so the engine is built the same way
   * whether or not an application configured plugins.
   *
   * @param enginePlugins The configured plugin sections, may be <code>null</code>
   */
  public void setEnginePlugins(
      final java.util.Map<String, Camunda7EnginePluginProperties> enginePlugins) {
    this.enginePlugins = enginePlugins == null
        ? java.util.Map.of()
        : enginePlugins;
  }

  /**
   * The reserved value of {@code data-source-name} naming the application's
   * DEFAULT datasource explicitly. Needed because an application providing several
   * datasources has to name the one each adapter id runs on - and the
   * default datasource has no name of its own on either platform.
   */
  public static final String DEFAULT_DATA_SOURCE_NAME = "default";

  /**
   * Whether a configured name means the application's own datasource. Unset and blank mean
   * the same as the reserved word, because leaving the key out is how most applications
   * say it.
   *
   * @param dataSourceName A configured datasource name
   * @return Whether it names the application's DEFAULT datasource
   */
  public static boolean isDefaultDataSourceName(
      final String dataSourceName) {

    return (dataSourceName == null) || dataSourceName.isBlank() || DEFAULT_DATA_SOURCE_NAME
        .equalsIgnoreCase(dataSourceName.trim());

  }

  /**
   * Whether this adapter id runs on a datasource of its own. That is what decides whether
   * the engine can join the caller's transaction, so a good deal of behaviour hangs off
   * this one answer.
   *
   * @return Whether the engine runs on a named datasource
   */
  public boolean usesSeparateDataSource() {
    return !isDefaultDataSourceName(dataSourceName);
  }

}
