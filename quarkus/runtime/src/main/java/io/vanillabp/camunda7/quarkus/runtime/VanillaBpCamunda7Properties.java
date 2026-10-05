package io.vanillabp.camunda7.quarkus.runtime;

import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

/**
 * The Camunda 7 adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree: the adapter's engine settings live at the canonical per-adapter location
 * <code>vanillabp.adapters.&lt;id&gt;.*</code>. A second RUN_TIME
 * {@code @ConfigMapping} over the same prefix coexists with the platform's mapping;
 * since the platform dropped the blanket {@code withMappingIgnore}, this overlay
 * doubles as the unknown-key validation coverage for the adapter's keys.
 * <p>
 * The keys are IDENTICAL to the Spring Boot module: an adapter id's own datasource is
 * referenced BY NAME on both platforms (<code>data-source-name</code>; always
 * application-/runtime-provided - VanillaBP never builds a pool). On Quarkus the name
 * points at a declared <code>quarkus.datasource.&lt;name&gt;.*</code> datasource, on
 * Spring Boot at a {@code DataSource} bean.
 * <p>
 * The adapter-id set is NEVER derived from this overlay map - it always comes from the
 * platform's core properties ({@code adapterTypes()} filtered by type
 * {@code camunda7}); the overlay is a per-known-id lookup only.
 */
@StaticInitSafe
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface VanillaBpCamunda7Properties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID - only the
   * Camunda 7 engine keys are modeled here.
   *
   * @return The configured adapter sections, keyed by adapter id
   */
  Map<String, Camunda7AdapterKeys> adapters();

  /**
   * The workflow-module sections of the shared tree - only the Camunda 7 keys which are
   * resolvable per scope are modeled here (the serialization format, and the tenant the
   * module is deployed into).
   *
   * @return The configured workflow-module sections, keyed by workflow module id
   */
  Map<String, Camunda7WorkflowModuleKeys> workflowModules();

  /**
   * Resolves whether the execution listeners somebody modelled are served, most specific first:
   * the workflow, its workflow module, the adapter. The most specific CONFIGURED value wins in
   * both directions.
   *
   * @param adapterId The adapter id
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The setting together with the key it stands in, never <code>null</code>
   */
  default io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver.Setting allowListenersFor(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var module = workflowModuleId != null
        ? workflowModules().get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module
            .workflows()
            .get(bpmnProcessId)
        : null;
    final var perWorkflow = workflow != null
        ? workflow
            .adapters()
            .get(adapterId)
        : null;
    if ((perWorkflow != null) && perWorkflow.allowListeners().isPresent()) {
      return new io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver.Setting(
          perWorkflow.allowListeners().get(), "vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.%s"
              .formatted(
                  workflowModuleId, bpmnProcessId, adapterId,
                  io.vanillabp.camunda7.wiring.Camunda7Listeners.ALLOW_LISTENERS_KEY));
    }
    final var perModule = module != null
        ? module
            .adapters()
            .get(adapterId)
        : null;
    if ((perModule != null) && perModule.allowListeners().isPresent()) {
      return new io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver.Setting(
          perModule.allowListeners().get(), "vanillabp.workflow-modules.%s.adapters.%s.%s"
              .formatted(
                  workflowModuleId, adapterId,
                  io.vanillabp.camunda7.wiring.Camunda7Listeners.ALLOW_LISTENERS_KEY));
    }
    final var adapter = adapters().get(adapterId);
    if ((adapter != null) && adapter.allowListeners().orElse(Boolean.FALSE)) {
      return new io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver.Setting(
          true, io.vanillabp.camunda7.wiring.Camunda7Listeners.propertyKeyOf(adapterId));
    }
    return io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver.Setting.NOTHING_CONFIGURED;

  }

  /**
   * Every <code>allow-listeners</code> this configuration puts at TASK level, fully spelled out -
   * the level which does not resolve this key.
   *
   * @param adapterId The adapter id
   * @return The keys found
   */
  default java.util.List<String> allowListenersKeysAtTaskLevel(
      final String adapterId) {

    return workflowModules()
        .entrySet()
        .stream()
        .flatMap(module -> module
            .getValue()
            .workflows()
            .entrySet()
            .stream()
            .flatMap(workflow -> workflow
                .getValue()
                .tasks()
                .entrySet()
                .stream()
                .filter(task -> {
                  final var keys = task
                      .getValue()
                      .adapters()
                      .get(adapterId);
                  return (keys != null) && keys.allowListeners().isPresent();
                })
                .map(task -> "vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.%s"
                    .formatted(
                        module.getKey(), workflow.getKey(), task.getKey(), adapterId,
                        io.vanillabp.camunda7.wiring.Camunda7Listeners.ALLOW_LISTENERS_KEY))))
        .toList();

  }

  /**
   * The Camunda 7 keys of one <code>vanillabp.workflow-modules.&lt;module&gt;</code>
   * section which may override what the adapter section says.
   */
  interface Camunda7WorkflowModuleKeys {

    /**
     * The per-adapter-id overrides of this workflow module.
     *
     * @return The adapter sections of this workflow module, keyed by adapter id
     */
    Map<String, Camunda7ModuleScopedKeys> adapters();

    /**
     * The workflows of this workflow module, keyed by BPMN process ID.
     *
     * @return The workflow sections of this module, keyed by BPMN process id
     */
    Map<String, Camunda7WorkflowKeys> workflows();

  }

  /**
   * The Camunda 7 keys of one workflow.
   */
  interface Camunda7WorkflowKeys {

    /**
     * The per-adapter-id overrides of this workflow.
     *
     * @return The adapter sections of this workflow, keyed by adapter id
     */
    Map<String, Camunda7ScopedKeys> adapters();

    /**
     * The tasks of this workflow. Modelled here for one reason only: a key set at this level
     * which the adapter does not resolve there has to be findable, so the boot can say where
     * the key IS read instead of leaving a line which does nothing.
     *
     * @return The task sections of this workflow, keyed by task definition
     */
    Map<String, Camunda7TaskKeys> tasks();

  }

  /**
   * The Camunda 7 keys of one task - the most specific level, and the one
   * <code>allow-listeners</code> does not resolve at.
   */
  interface Camunda7TaskKeys {

    /**
     * The per-adapter-id overrides of this task.
     *
     * @return The adapter sections of this task, keyed by adapter id
     */
    Map<String, Camunda7ScopedKeys> adapters();

  }

  /**
   * One engine plugin of an adapter id.
   */
  interface Camunda7EnginePluginKeys {

    /**
     * The plugin's class, e.g. <code>org.camunda.xstream.ProcessEnginePlugin</code>.
     *
     * @return The class name, empty where the section names none
     */
    Optional<String> pluginClass();

    /**
     * The plugin's own properties in kebab-case - Camunda converts them to the types the
     * plugin declares.
     *
     * @return The plugin's properties, empty where it has none
     */
    Map<String, String> properties();

  }

  /**
   * The Camunda 7 keys which may be set per workflow module and per workflow.
   */
  interface Camunda7ScopedKeys {

    /**
     * The serialization format of nested shared values for this scope.
     *
     * @return The configured format, empty where this scope says nothing
     */
    Optional<String> serializationFormat();

    /**
     * Whether the execution listeners somebody modelled are served by
     * <code>@WorkflowTask</code> methods, for this scope. Empty rather than <code>false</code>
     * where nothing is configured, which is what lets a workflow module switch OFF what the
     * adapter switched on.
     *
     * @return The configured answer, empty where this scope says nothing
     */
    Optional<Boolean> allowListeners();

  }

  /**
   * The Camunda 7 keys of one workflow module's adapter section: the scoped keys every level
   * has, plus the tenant, which only a workflow module may override because a tenant id is an
   * attribute of the deployment this adapter makes per workflow module.
   */
  interface Camunda7ModuleScopedKeys extends Camunda7ScopedKeys {

    /**
     * The Camunda tenant this workflow module is deployed into, overriding the name the
     * adapter section gives every module of this application.
     *
     * @return The configured tenant, empty where the adapter section decides
     */
    Optional<String> tenantId();

  }

  /**
   * The Camunda 7 engine keys of one <code>vanillabp.adapters.&lt;id&gt;</code>
   * section.
   */
  interface Camunda7AdapterKeys {

    /**
     * Create/upgrade the engine schema on boot (engine values, e.g.
     * <code>true</code>, <code>false</code>, <code>create-drop</code>); default
     * <code>true</code>.
     *
     * @return The configured value, empty for the default
     */
    Optional<String> databaseSchemaUpdate();

    /**
     * Engine-wide default history time to live (Camunda 7.24 rejects deployments
     * of processes without one); default <code>P180D</code>, overridable per
     * process via <code>camunda:historyTimeToLive</code>.
     *
     * @return The configured duration, empty for the default
     */
    Optional<String> historyTimeToLive();

    /**
     * OPTIONAL name of a declared Quarkus datasource
     * (<code>quarkus.datasource.&lt;name&gt;.*</code>) this adapter id's embedded
     * engine runs on. Without it the engine shares the application's default
     * datasource (the embedded-engine guarantee: engine commands join the caller's
     * JTA transaction). With it the engine runs on its own schema - required for
     * engine-side-by-side migrations - and starting workflows uses VanillaBP's
     * two-phase pattern (see the README's transaction caveat).
     *
     * @return The configured datasource name, empty for the application's default one
     */
    Optional<String> dataSourceName();

    /**
     * OPTIONAL prefix of the engine's database tables (engine setting
     * <code>databaseTablePrefix</code>). It lets two adapter ids share ONE
     * datasource while running separate engines - the side-by-side migration setup
     * on a single database. The tables of the prefix have to exist before the
     * application starts, and <code>database-schema-update</code> has to be
     * <code>false</code>: Camunda's schema management ignores the prefix and would
     * create a set of unprefixed <code>ACT_*</code> tables instead (see
     * {@code Camunda7TablePrefixSchema}).
     *
     * @return The configured prefix, empty where the engine uses the plain table names
     */
    Optional<String> tablePrefix();

    /**
     * OPTIONAL serialization format a shared value the engine has no variable type for
     * is stored in, e.g. <code>application/json</code> (the SPIN JSON dataformat) or
     * <code>application/xstream</code> (camunda-xstream). Applied to the engine's
     * <code>defaultSerializationFormat</code> and to the variables VanillaBP writes;
     * overridable per workflow module and per workflow (see
     * {@link VanillaBpCamunda7Properties#workflowModules()}). The matching dataformat is
     * the application's dependency.
     *
     * @return The configured format, empty where the engine's own default applies
     */
    Optional<String> serializationFormat();

    /**
     * OPTIONAL Camunda engine plugins of this adapter id: named sections, each naming a
     * class and carrying its own properties - which Camunda applies, exactly like the
     * <code>&lt;property&gt;</code> elements of a <code>bpm-platform.xml</code>.
     * This is how a serialization dataformat reaches the embedded engine.
     *
     * @return The configured plugin sections, empty where there are none
     */
    Map<String, Camunda7EnginePluginKeys> enginePlugins();

    /**
     * OPTIONAL name of the Camunda tenant a workflow module is deployed to under the
     * name-clash-avoidance mode <code>by-adapter</code>. Without it the
     * workflow module ID names the tenant - VanillaBP 1's behavior.
     *
     * @return The configured tenant, empty where the workflow module id names it
     */
    Optional<String> tenantId();

    /**
     * OPTIONAL acknowledgement that the application's identifiers are unique across all
     * of its workflow modules - it silences the WARN logged while the
     * name-clash-avoidance mode <code>none</code> applies (this adapter's default
     * mode). Default <code>false</code>.
     *
     * @return The configured acknowledgement, empty for <code>false</code>
     */
    Optional<Boolean> acceptUnscopedIdentifiers();

    /**
     * OPTIONAL: whether the execution listeners somebody MODELLED are served by
     * <code>@WorkflowTask</code> methods. Adapter-level base of a resolution over three levels
     * (workflow &gt; workflow-module &gt; adapter), default <code>false</code>, see
     * {@link io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver}.
     *
     * @return The configured answer, empty for <code>false</code>
     */
    Optional<Boolean> allowListeners();

    /**
     * OPTIONAL: the job executor waits until the next job is due instead of polling every
     * 5 to 60 seconds, and a transaction which writes a job wakes it. Default
     * <code>false</code> - see {@code Camunda7JobExecutorSleep}.
     *
     * @return The configured answer, empty for <code>false</code>
     */
    Optional<Boolean> sleepUntilSomethingIsDue();

    /**
     * OPTIONAL: whether the engine's metrics reporter writes its counters to the database
     * every 900 seconds. Unset means the opposite of
     * {@link #sleepUntilSomethingIsDue()}, so an engine which is allowed to sleep is not
     * woken by its own metrics.
     *
     * @return The configured answer, empty to follow the setting above
     */
    Optional<Boolean> dbMetricsReporting();

  }

}
