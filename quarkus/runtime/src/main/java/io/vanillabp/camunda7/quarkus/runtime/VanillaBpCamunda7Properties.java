package io.vanillabp.camunda7.quarkus.runtime;

import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigDocMapKey;
import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

/**
 * The Camunda 7 adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree. The adapter's engine settings live at the canonical place of every adapter,
 * <code>vanillabp.adapters.&lt;id&gt;.*</code>. So this is a second RUN_TIME
 * {@code @ConfigMapping} over the same prefix. It lives next to the platform's mapping.
 * The platform dropped the blanket {@code withMappingIgnore}. Since then this overlay
 * also covers the adapter's keys when unknown keys are validated.
 * <p>
 * The keys are IDENTICAL to the Spring Boot module. On both platforms an adapter id names
 * its own datasource BY NAME (<code>data-source-name</code>). The application or the
 * runtime always provides it, VanillaBP never builds a pool. On Quarkus the name points at
 * a declared <code>quarkus.datasource.&lt;name&gt;.*</code> datasource, on Spring Boot at a
 * {@code DataSource} bean.
 * <p>
 * The set of adapter ids NEVER comes from this overlay map. It always comes from the
 * platform's core properties: {@code adapterTypes()}, filtered by the type
 * {@code camunda7}. The overlay only looks up the settings of an id that is known.
 */
@StaticInitSafe
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface VanillaBpCamunda7Properties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID. Only the Camunda 7
   * engine keys are modeled here.
   *
   * @return The configured adapter sections, keyed by adapter id
   */
  @ConfigDocMapKey("adapter-id")
  Map<String, Camunda7AdapterKeys> adapters();

  /**
   * The workflow-module sections of the shared tree - only the Camunda 7 keys which are
   * resolvable per scope are modeled here: the serialization format, the tenant the module
   * is deployed into, and <code>allow-listeners</code>. Below a workflow, the
   * <code>tasks</code> section is read as well, to name an <code>allow-listeners</code> put at
   * task level.
   *
   * @return The configured workflow-module sections, keyed by workflow module id
   */
  @ConfigDocMapKey("workflow-module-id")
  Map<String, Camunda7WorkflowModuleKeys> workflowModules();

  /**
   * Resolves how long a correlation of a message waits for a subscription, most specific
   * first: the message, the workflow, its workflow module, the adapter. Below a workflow the
   * message is the most specific level, not a task.
   *
   * @param adapterId The adapter id
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID of the process whose model waits for the message
   * @param messageName The message name as the application wrote it
   * @return The time-to-live, <code>null</code> where none is configured
   */
  default java.time.Duration messageTimeToLiveFor(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName) {

    final var module = workflowModuleId != null
        ? workflowModules().get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module
            .workflows()
            .get(bpmnProcessId)
        : null;
    final var message = (workflow != null) && (messageName != null)
        ? workflow
            .messages()
            .get(messageName)
        : null;
    final var perMessage = message != null
        ? message
            .adapters()
            .get(adapterId)
        : null;
    if ((perMessage != null) && perMessage.messageTimeToLive().isPresent()) {
      return perMessage.messageTimeToLive().get();
    }
    for (final var scoped : java.util.Arrays
        .asList(
            workflow != null
                ? workflow.adapters()
                : null,
            module != null
                ? module.adapters()
                : null)) {
      final var section = scoped != null
          ? scoped.get(adapterId)
          : null;
      if ((section != null) && section.messageTimeToLive().isPresent()) {
        return section.messageTimeToLive().get();
      }
    }
    final var adapter = adapters().get(adapterId);
    return adapter != null
        ? adapter.messageTimeToLive().orElse(null)
        : null;

  }

  /**
   * The keys of every <code>message-time-to-live</code> of an adapter id which is below zero.
   * A negative time makes no sense, so the boot names them and ends.
   *
   * @param adapterId The adapter id
   * @return The keys, each written as <code>key=value</code>, empty where every value is
   *         zero or more
   */
  default java.util.List<String> negativeMessageTimeToLiveKeys(
      final String adapterId) {

    final var keys = new java.util.ArrayList<String>();
    final java.util.function.BiConsumer<String, Optional<java.time.Duration>> check = (
        key,
        value) -> value
            .filter(java.time.Duration::isNegative)
            .ifPresent(negative -> keys.add("%s=%s".formatted(key, negative)));
    final var adapter = adapters().get(adapterId);
    if (adapter != null) {
      check.accept("vanillabp.adapters.%s.message-time-to-live".formatted(adapterId), adapter.messageTimeToLive());
    }
    workflowModules().forEach((
        moduleId,
        module) -> {
      final var perModule = module.adapters().get(adapterId);
      if (perModule != null) {
        check.accept("vanillabp.workflow-modules.%s.adapters.%s.message-time-to-live".formatted(moduleId, adapterId),
            perModule.messageTimeToLive());
      }
      module.workflows().forEach((
          workflowId,
          workflow) -> {
        final var perWorkflow = workflow.adapters().get(adapterId);
        if (perWorkflow != null) {
          check.accept("vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.message-time-to-live"
              .formatted(moduleId, workflowId, adapterId), perWorkflow.messageTimeToLive());
        }
        workflow.messages().forEach((
            messageName,
            message) -> {
          final var perMessage = message.adapters().get(adapterId);
          if (perMessage != null) {
            check.accept("vanillabp.workflow-modules.%s.workflows.%s.messages.%s.adapters.%s.message-time-to-live"
                .formatted(moduleId, workflowId, messageName, adapterId), perMessage.messageTimeToLive());
          }
        });
      });
    });
    return keys;

  }

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
   * Every <code>allow-listeners</code> this configuration puts at TASK level, fully spelled
   * out. That level does not resolve this key.
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
    @ConfigDocMapKey("adapter-id")
    Map<String, Camunda7ModuleScopedKeys> adapters();

    /**
     * The workflows of this workflow module, keyed by BPMN process ID.
     *
     * @return The workflow sections of this module, keyed by BPMN process id
     */
    @ConfigDocMapKey("bpmn-process-id")
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
    @ConfigDocMapKey("adapter-id")
    Map<String, Camunda7ScopedKeys> adapters();

    /**
     * The tasks of this workflow. They are modelled here for one reason only. A key may be
     * set at this level although the adapter does not resolve it there. The boot has to
     * find such a key, so it can say where the key IS read. Otherwise the line would just
     * do nothing.
     *
     * @return The task sections of this workflow, keyed by task definition
     */
    @ConfigDocMapKey("task-definition")
    Map<String, Camunda7TaskKeys> tasks();

    /**
     * The messages of this workflow. A message is the most specific level of
     * <code>message-time-to-live</code>. It is a map of its own and not the task level, so a
     * value meant for a message never applies to a task of the same name.
     *
     * @return The message sections of this workflow, keyed by message name
     */
    @ConfigDocMapKey("message-name")
    Map<String, Camunda7MessageKeys> messages();

  }

  /**
   * The Camunda 7 keys of one message of a workflow. Only <code>message-time-to-live</code> is
   * read at this level.
   */
  interface Camunda7MessageKeys {

    /**
     * The per-adapter-id overrides of this message.
     *
     * @return The adapter sections of this message, keyed by adapter id
     */
    @ConfigDocMapKey("adapter-id")
    Map<String, Camunda7MessageScopedKeys> adapters();

  }

  /**
   * The Camunda 7 keys of one message's adapter section. Only the time-to-live is read for
   * a message, so it is the only key here.
   */
  interface Camunda7MessageScopedKeys {

    /**
     * How long a correlation of this message waits for a subscription.
     *
     * @return The time-to-live, empty where this message says nothing
     */
    Optional<java.time.Duration> messageTimeToLive();

  }

  /**
   * The Camunda 7 keys of one task. This is the most specific level, and
   * <code>allow-listeners</code> is not resolved at it.
   */
  interface Camunda7TaskKeys {

    /**
     * The per-adapter-id overrides of this task.
     *
     * @return The adapter sections of this task, keyed by adapter id
     */
    @ConfigDocMapKey("adapter-id")
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
     * The plugin's own properties in kebab-case. Camunda converts them to the types the
     * plugin declares.
     *
     * @return The plugin's properties, empty where it has none
     */
    @ConfigDocMapKey("property-name")
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
     * <code>@WorkflowTask</code> methods, for this scope. It is empty, not <code>false</code>,
     * where nothing is configured. That lets a workflow module switch OFF what the adapter
     * switched on.
     *
     * @return The configured answer, empty where this scope says nothing
     */
    Optional<Boolean> allowListeners();

    /**
     * How long a correlation waits for a subscription, for this scope. It is read per
     * workflow module, per workflow and per message.
     *
     * @return The time-to-live, empty where this scope says nothing
     */
    Optional<java.time.Duration> messageTimeToLive();

  }

  /**
   * The Camunda 7 keys of one workflow module's adapter section. These are the scoped keys
   * every level has, plus the tenant. Only a workflow module may override the tenant. The
   * reason: a tenant id belongs to the deployment, and this adapter makes one deployment
   * per workflow module.
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
     * Whether the engine schema is created or upgraded on boot. It takes the engine's
     * values, e.g. <code>true</code>, <code>false</code>, <code>create-drop</code>. The
     * default is <code>true</code>.
     *
     * @return The configured value, empty for the default
     */
    Optional<String> databaseSchemaUpdate();

    /**
     * The default history time to live for the whole engine. Camunda 7.24 rejects
     * deployments of processes without one. The default is <code>P180D</code>. A process
     * may override it through <code>camunda:historyTimeToLive</code>.
     *
     * @return The configured duration, empty for the default
     */
    Optional<String> historyTimeToLive();

    /**
     * OPTIONAL name of a declared Quarkus datasource
     * (<code>quarkus.datasource.&lt;name&gt;.*</code>) which this adapter id's embedded
     * engine runs on. Without it the engine shares the application's default datasource.
     * That is the embedded-engine guarantee: engine commands join the caller's JTA
     * transaction. With it the engine runs on its own schema. Engines which run side by
     * side for a migration need that. Starting a workflow then uses VanillaBP's two-phase
     * pattern (see the README's transaction caveat).
     *
     * @return The configured datasource name, empty for the application's default one
     */
    Optional<String> dataSourceName();

    /**
     * OPTIONAL prefix of the engine's database tables (engine setting
     * <code>databaseTablePrefix</code>). With it two adapter ids share ONE datasource and
     * still run separate engines. This is the side-by-side migration setup on a single
     * database. The tables of the prefix have to exist before the application starts. And
     * <code>database-schema-update</code> has to be <code>false</code>, because Camunda's
     * schema management ignores the prefix. It would create a set of unprefixed
     * <code>ACT_*</code> tables instead (see {@code Camunda7TablePrefixSchema}).
     *
     * @return The configured prefix, empty where the engine uses the plain table names
     */
    Optional<String> tablePrefix();

    /**
     * OPTIONAL serialization format for a shared value which has no variable type in the
     * engine, e.g. <code>application/json</code> (the SPIN JSON dataformat) or
     * <code>application/xstream</code> (camunda-xstream). It is applied to the engine's
     * <code>defaultSerializationFormat</code> and to the variables VanillaBP writes. A
     * workflow module or a workflow may override it (see
     * {@link VanillaBpCamunda7Properties#workflowModules()}). The matching dataformat is a
     * dependency of the application.
     *
     * @return The configured format, empty where the engine's own default applies
     */
    Optional<String> serializationFormat();

    /**
     * How long a correlated message waits for the workflow to reach the point where it waits
     * for it. It has the key and the meaning of the Camunda 8 adapter. Not set, or zero,
     * refuses a correlation nothing waits for where the application made the call, as in
     * version 1. It is also read per workflow module, per workflow and per message, and the
     * most specific value wins.
     *
     * @return The time-to-live, empty where none is configured
     */
    Optional<java.time.Duration> messageTimeToLive();

    /**
     * OPTIONAL Camunda engine plugins of this adapter id. Each is a named section which
     * names a class and carries its own properties. Camunda applies them, exactly like the
     * <code>&lt;property&gt;</code> elements of a <code>bpm-platform.xml</code>. This is how
     * a serialization dataformat reaches the embedded engine.
     *
     * @return The configured plugin sections, empty where there are none
     */
    @ConfigDocMapKey("plugin-name")
    Map<String, Camunda7EnginePluginKeys> enginePlugins();

    /**
     * OPTIONAL name of the Camunda tenant a workflow module is deployed to under the
     * name-clash-avoidance mode <code>by-adapter</code>. Without it the workflow module ID
     * names the tenant, as in VanillaBP 1.
     *
     * @return The configured tenant, empty where the workflow module id names it
     */
    Optional<String> tenantId();

    /**
     * OPTIONAL acknowledgement that the application's identifiers are unique across all
     * of its workflow modules - it silences the WARN logged while the
     * name-clash-avoidance mode <code>none</code> applies (the default mode of this adapter
     * is <code>by-adapter</code>). Default <code>false</code>.
     *
     * @return The configured acknowledgement, empty for <code>false</code>
     */
    Optional<Boolean> acceptUnscopedIdentifiers();

    /**
     * OPTIONAL: whether the execution listeners somebody MODELLED are served by
     * <code>@WorkflowTask</code> methods. This is the adapter level, the base of a resolution
     * over three levels (workflow &gt; workflow-module &gt; adapter). The default is
     * <code>false</code>. See {@link io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver}.
     *
     * @return The configured answer, empty for <code>false</code>
     */
    Optional<Boolean> allowListeners();

    /**
     * OPTIONAL: the job executor waits until the next job is due, and does not poll every
     * 5 to 60 seconds. A transaction which writes a job wakes it. The default is
     * <code>false</code>. See {@code Camunda7JobExecutorSleep}.
     *
     * @return The configured answer, empty for <code>false</code>
     */
    Optional<Boolean> sleepUntilSomethingIsDue();

    /**
     * OPTIONAL: whether the engine's metrics reporter writes its counters to the database
     * every 900 seconds. If unset, it is the opposite of {@link #sleepUntilSomethingIsDue()}.
     * So an engine which may sleep is not woken by its own metrics.
     *
     * @return The configured answer, empty to follow the setting above
     */
    Optional<Boolean> dbMetricsReporting();

    /**
     * OPTIONAL: whether the engine evaluates decisions with its older FEEL implementation,
     * which needs no FEEL engine library. An application which runs the Operaton adapter next
     * to this one needs it. The default is <code>false</code>. See
     * {@code Camunda7DmnFeelLegacyBehavior}.
     *
     * @return The configured answer, empty for <code>false</code>
     */
    Optional<Boolean> dmnFeelLegacyBehavior();

  }

}
