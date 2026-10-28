package io.vanillabp.camunda7.engine;

/**
 * The engine settings of one adapter id of the Camunda 7 adapter. They live at the
 * canonical per-adapter location <code>vanillabp.adapters.&lt;id&gt;.*</code>. Each platform
 * adds them to the shared configuration tree with its own overlay: the Spring Boot module's
 * <code>VanillaBpCamunda7Properties</code> and the Quarkus module's
 * <code>@ConfigMapping</code> overlay.
 * <ul>
 *   <li><code>database-schema-update</code> - creates or upgrades the engine schema on
 *       boot. It takes engine values, e.g. <code>true</code>, <code>false</code>,
 *       <code>create-drop</code>. Default <code>true</code>.</li>
 *   <li><code>history-time-to-live</code> - the engine-wide default history time to
 *       live. Camunda 7.24 rejects the deployment of a process without one. Default
 *       <code>P180D</code>. A process may override it with
 *       <code>camunda:historyTimeToLive</code>.</li>
 *   <li><code>data-source-name</code> - OPTIONAL. The name of the datasource this adapter
 *       id's embedded engine runs on. The application or the runtime provides it. Setting
 *       up datasources is on purpose NOT VanillaBP's job, and the adapter never builds its
 *       own pool. On Spring Boot it is the name of a {@code DataSource} BEAN. On Quarkus
 *       it is the name of a declared named Agroal datasource
 *       (<code>quarkus.datasource.&lt;name&gt;.*</code>). Without it the engine shares the
 *       application's default datasource, and a task handler runs in the engine's job
 *       transaction. With it the engine runs on its own schema. A side-by-side migration
 *       of engines needs that or a <code>table-prefix</code>, because two embedded engines
 *       must never share one set of tables. In both cases an operation which moves a
 *       workflow on runs after the caller's commit, through VanillaBP's two-phase pattern
 *       (see <code>Camunda7ProcessService</code>).</li>
 *   <li><code>accept-unscoped-identifiers</code> - OPTIONAL acknowledgement that the
 *       application's identifiers are unique across all of its workflow modules, which
 *       silences the WARN logged while the name-clash-avoidance mode <code>none</code>
 *       applies (the default mode of this adapter is <code>by-adapter</code>); default
 *       <code>false</code>;</li>
 *   <li><code>table-prefix</code> - OPTIONAL. The prefix of the engine's database tables
 *       (engine setting <code>databaseTablePrefix</code>). With it two adapter ids share
 *       ONE datasource and still run separate engines. This is the side-by-side migration
 *       setup on a single database. <b>The tables of the prefix have to exist before the
 *       application starts.</b> Camunda's schema management ignores the prefix. It would
 *       create a set of unprefixed <code>ACT_*</code> tables instead. Camunda says so in
 *       {@code ProcessEngineConfigurationImpl#setDatabaseTablePrefix}, and
 *       {@code Camunda7TablePrefixEngineBehaviourTest} records it. So a prefixed adapter id
 *       also needs <code>database-schema-update: false</code>.
 *       {@link Camunda7TablePrefixSchema} says so while the application boots. Otherwise
 *       the engine would fail on its first query.</li>
 *   <li><code>sleep-until-something-is-due</code> - OPTIONAL. The job executor waits
 *       until the next job is due, instead of polling every 5 to 60 seconds. Default
 *       <code>false</code>, see {@link Camunda7JobExecutorSleep}.</li>
 *   <li><code>allow-listeners</code> - OPTIONAL. Whether <code>@WorkflowTask</code> methods
 *       serve the execution listeners somebody MODELLED. It is resolvable per workflow
 *       module and workflow. Default <code>false</code>, see
 *       {@link io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver}.</li>
 *   <li><code>db-metrics-reporting</code> - OPTIONAL. Whether the engine's metrics
 *       reporter writes its counters to the database every 900 seconds. Unset means
 *       the opposite of the sleep. So an engine which may sleep is not woken by its own
 *       metrics.</li>
 *   <li><code>dmn-feel-legacy-behavior</code> - OPTIONAL. The engine evaluates decisions with
 *       its older FEEL implementation, which needs no FEEL engine library. Default
 *       <code>false</code>, see {@link Camunda7DmnFeelLegacyBehavior}.</li>
 * </ul>
 */
public class Camunda7EngineProperties {

  /**
   * The platform integration builds one per configured adapter id and fills it through the
   * setters. So a key which a deployment does not set keeps the default written next to it.
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
   * The serialization format for a shared value the engine has no variable type for, e.g.
   * {@code application/json} (the SPIN JSON dataformat) or {@code application/xstream}
   * (camunda-xstream). Such a value is a nested value, or a number this engine cannot store
   * as itself. The format is applied twice. First to the engine's
   * {@code defaultSerializationFormat}, so an application configures the format once. Then
   * per written variable, so a workflow module or a single workflow may override it
   * ({@code vanillabp.workflow-modules.<module>.adapters.<id>.serialization-format},
   * {@code vanillabp.workflow-modules.<module>.workflows.<workflow>.adapters.<id>.serialization-format}).
   * <p>
   * The matching dataformat has to be on the classpath. That dependency and its own
   * settings belong to the application. VanillaBP only names the format.
   */
  private String serializationFormat;

  /**
   * The Camunda engine plugins of this adapter id. Each one is a named section. It names a
   * class and has its own properties, and Camunda itself applies them (see
   * {@link Camunda7EnginePlugins}). This is how a serialization dataformat (camunda-xstream,
   * SPIN) gets into an engine VanillaBP builds. It is per adapter id, so two embedded engines
   * can have different plugins.
   */
  private java.util.Map<String, Camunda7EnginePluginProperties> enginePlugins = java.util.Map.of();

  /**
   * The Camunda tenant a workflow module is deployed to under the name-clash
   * avoidance mode {@code by-adapter}. Unset (the default) means that the
   * workflow module ID is the tenant. This is how VanillaBP 1 behaved.
   */
  private String tenantId;

  /**
   * Whether the application states that its identifiers are unique across all of its
   * workflow modules. The name-clash-avoidance mode {@code none} relies on that. It
   * silences the WARN which the adapter logs per workflow module while that mode applies.
   * It is a deliberate statement, not a log-level setting. If the statement is wrong, two
   * workflow modules address the same process definitions and tasks. Default
   * {@code false}.
   */
  private boolean acceptUnscopedIdentifiers = false;

  /**
   * Whether <code>@WorkflowTask</code> methods serve the execution listeners somebody MODELLED.
   * This is the adapter level. The most specific of three levels wins (workflow &gt;
   * workflow-module &gt; adapter), see
   * {@link io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver}. Default
   * <code>false</code>.
   * <p>
   * Without it a model with such a listener does not boot. Otherwise the engine would evaluate
   * the listener's expression itself. A workflow reaching the element would then fail on a name
   * nothing resolves, or run a method the application never meant for that element. With it the
   * listener is a task like any other. A method is asked for, and a method serving no listener
   * is reported. What that costs is written into the boot log of every workflow module it
   * applies to.
   * <p>
   * There is on purpose no TASK level. That level is keyed by a task DEFINITION, and this key
   * decides whether a listener becomes a task at all.
   */
  private boolean allowListeners = false;

  /**
   * Whether this adapter id's job executor waits until the next job is due, instead of
   * polling. Think of an application which waits for a timer most of its life, on a
   * database billed by active use. It pays for every poll. A due date answers exactly the
   * question those polls keep asking. Off by default, because the timing of the engine's
   * job executor should not change for an application which did not ask for it.
   * <p>
   * This is one switch, not two for the sleeping and the waking. The two make no sense
   * apart. An executor which sleeps until a due date and which nobody wakes is worse than
   * either half.
   */
  private boolean sleepUntilSomethingIsDue = false;

  /**
   * Whether the engine's metrics reporter writes its counters to the database every 900
   * seconds. Unset (the default) means the opposite of {@link #sleepUntilSomethingIsDue}.
   * So the engine keeps Camunda's behaviour while it polls anyway, and it stops writing
   * once it may sleep. Set it to <code>true</code> to keep the metrics on a sleeping
   * engine. That costs a wake-up four times an hour. Metrics are still counted in memory
   * either way. Only the writing stops.
   */
  private Boolean dbMetricsReporting;

  /**
   * Whether the engine evaluates decisions with its older FEEL implementation (Camunda's
   * {@code dmnFeelEnableLegacyBehavior}). It needs no {@code org.camunda.feel:feel-engine}
   * library. An application which runs the Operaton adapter next to this one needs it,
   * because both engines bring a different version of that library. See
   * {@link Camunda7DmnFeelLegacyBehavior}. Default <code>false</code>, which is how
   * VanillaBP 1 behaved.
   * <p>
   * There is only this adapter level. The setting belongs to the engine, and one engine
   * serves every workflow module of the adapter id.
   */
  private boolean dmnFeelLegacyBehavior = false;

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
   * application's default one. Only then can the engine join the caller's
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
   * The prefix of the engine's database tables. With it two adapter ids run separate
   * engines on one datasource.
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
   * workflow modules. This silences the WARN of the mode {@code none}.
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
   * Whether the engine evaluates decisions with its older FEEL implementation.
   *
   * @return Whether the older FEEL implementation is used
   */
  public boolean isDmnFeelLegacyBehavior() {
    return dmnFeelLegacyBehavior;
  }

  /**
   * Whether the engine evaluates decisions with its older FEEL implementation.
   *
   * @param dmnFeelLegacyBehavior Whether the older FEEL implementation is used
   */
  public void setDmnFeelLegacyBehavior(
      final boolean dmnFeelLegacyBehavior) {
    this.dmnFeelLegacyBehavior = dmnFeelLegacyBehavior;
  }

  /**
   * Whether this engine's job executor waits for the next job instead of polling for it.
   * It is asked while the engine is built. It also decides the metrics answer below.
   *
   * @return Whether the job executor of this adapter id waits until the next job is due
   */
  public boolean sleepsUntilSomethingIsDue() {

    return sleepUntilSomethingIsDue;

  }

  /**
   * Whether the metrics reporter writes its counters to the database. Unset follows the
   * answer above. An engine which may rest should not be woken four times an hour by its
   * own bookkeeping.
   *
   * @return Whether the engine's metrics reporter writes to the database
   */
  public boolean reportsMetricsToTheDatabase() {

    return (dbMetricsReporting == null)
        ? !sleepUntilSomethingIsDue
        : dbMetricsReporting.booleanValue();

  }

  /**
   * Keeps an empty map instead of <code>null</code>. So the engine is built the same way,
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
   * The reserved value of {@code data-source-name} which names the application's
   * DEFAULT datasource explicitly. An application with several datasources has to name
   * the one each adapter id runs on. But the default datasource has no name of its own
   * on either platform.
   */
  public static final String DEFAULT_DATA_SOURCE_NAME = "default";

  /**
   * Whether a configured name means the application's own datasource. Unset and blank mean
   * the same as the reserved word, because most applications say it by leaving the key
   * out.
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
   * Whether this adapter id runs on a datasource of its own. This decides whether the
   * engine can join the caller's transaction. So a good deal of behaviour depends on this
   * one answer.
   *
   * @return Whether the engine runs on a named datasource
   */
  public boolean usesSeparateDataSource() {
    return !isDefaultDataSourceName(dataSourceName);
  }

}
