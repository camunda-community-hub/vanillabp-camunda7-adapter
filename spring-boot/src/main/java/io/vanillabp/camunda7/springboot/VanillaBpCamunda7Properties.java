package io.vanillabp.camunda7.springboot;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.camunda7.engine.Camunda7EngineProperties;

/**
 * The Camunda 7 adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree. The adapter's engine settings live at the canonical place of every adapter,
 * <code>vanillabp.adapters.&lt;id&gt;.*</code>. The keys are documented in
 * {@link Camunda7EngineProperties}: <code>database-schema-update</code>,
 * <code>history-time-to-live</code>, <code>data-source-name</code>. On Spring Boot the
 * last one is the name of a {@code DataSource} BEAN which the application provides. So
 * this is a second {@code @ConfigurationProperties} class over the same prefix. It lives
 * next to the platform's binding of the core model. The JavaBean binding ignores the keys
 * which one view does not know.
 * <p>
 * The set of adapter ids NEVER comes from this overlay map. It always comes from the
 * platform's core properties: {@code adapterTypes()}, filtered by the type
 * {@code camunda7}. The overlay only looks up the settings of an id that is known. The
 * reason: environment variables can create phantom map entries in the overlay.
 */
@ConfigurationProperties("vanillabp")
public class VanillaBpCamunda7Properties {

  /**
   * Spring Boot builds the class and fills it with the keys of this overlay it knows. An
   * application which configures nothing keeps the empty maps. Then every lookup answers
   * what the adapter section or the default says.
   */
  public VanillaBpCamunda7Properties() {

  }

  /**
   * The adapter sections of the shared tree, keyed by adapter ID. Only the Camunda 7
   * engine keys are modeled here. They are bound directly onto the platform-neutral
   * {@link Camunda7EngineProperties}.
   */
  private Map<String, Camunda7EngineProperties> adapters = Map.of();

  /**
   * The workflow-module sections of the shared tree - only the Camunda 7 keys resolvable
   * per scope are modeled here: the serialization format of the shared values the engine
   * has no variable type for, the tenant the module is deployed into, and
   * <code>allow-listeners</code>. Below a workflow, the <code>tasks</code> section is read as
   * well, to name an <code>allow-listeners</code> put at task level.
   */
  private Map<String, Camunda7WorkflowModuleProperties> workflowModules = Map.of();

  /**
   * The adapter sections of the shared tree, keyed by adapter id.
   *
   * @return The sections found, empty where the application configured none
   */
  public Map<String, Camunda7EngineProperties> getAdapters() {
    return adapters;
  }

  /**
   * The adapter sections of the shared tree, keyed by adapter id.
   *
   * @param adapters The sections Spring Boot bound
   */
  public void setAdapters(
      final Map<String, Camunda7EngineProperties> adapters) {
    this.adapters = adapters;
  }

  /**
   * The workflow-module sections of the shared tree, keyed by workflow module id.
   *
   * @return The sections found, empty where the application configured none
   */
  public Map<String, Camunda7WorkflowModuleProperties> getWorkflowModules() {
    return workflowModules;
  }

  /**
   * The workflow-module sections of the shared tree, keyed by workflow module id.
   *
   * @param workflowModules The sections Spring Boot bound
   */
  public void setWorkflowModules(
      final Map<String, Camunda7WorkflowModuleProperties> workflowModules) {
    this.workflowModules = workflowModules;
  }

  /**
   * The Camunda 7 keys of one <code>vanillabp.workflow-modules.&lt;module&gt;</code>
   * section which may override what the adapter section says.
   */
  public static class Camunda7WorkflowModuleProperties {

    /**
     * Spring Boot builds one per section it finds and fills it through the setters.
     */
    public Camunda7WorkflowModuleProperties() {

    }

    /** The per-adapter-id overrides of this workflow module. */
    private Map<String, Camunda7ModuleScopedProperties> adapters = Map.of();

    /** The workflows of this workflow module, keyed by BPMN process id. */
    private Map<String, Camunda7WorkflowProperties> workflows = Map.of();

    /**
     * The per-adapter-id overrides of this workflow module.
     *
     * @return The adapter sections of this module, empty where there are none
     */
    public Map<String, Camunda7ModuleScopedProperties> getAdapters() {
      return adapters;
    }

    /**
     * The per-adapter-id overrides of this workflow module.
     *
     * @param adapters The adapter sections Spring Boot bound
     */
    public void setAdapters(
        final Map<String, Camunda7ModuleScopedProperties> adapters) {
      this.adapters = adapters;
    }

    /**
     * The workflows of this workflow module, keyed by BPMN process id.
     *
     * @return The workflow sections of this module, empty where there are none
     */
    public Map<String, Camunda7WorkflowProperties> getWorkflows() {
      return workflows;
    }

    /**
     * The workflows of this workflow module, keyed by BPMN process id.
     *
     * @param workflows The workflow sections Spring Boot bound
     */
    public void setWorkflows(
        final Map<String, Camunda7WorkflowProperties> workflows) {
      this.workflows = workflows;
    }

  }

  /**
   * The Camunda 7 keys of one workflow.
   */
  public static class Camunda7WorkflowProperties {

    /**
     * Spring Boot builds one per section it finds and fills it through the setters.
     */
    public Camunda7WorkflowProperties() {

    }

    /** The per-adapter-id overrides of this workflow. */
    private Map<String, Camunda7ScopedProperties> adapters = Map.of();

    /**
     * The tasks of this workflow. They are modelled here for one reason only. A key may be
     * set at this level although the adapter does not resolve it there. The boot has to
     * find such a key, so it can say where the key IS read. Otherwise the line would just
     * do nothing.
     */
    private Map<String, Camunda7TaskProperties> tasks = Map.of();

    /**
     * The messages of this workflow, keyed by message name. A message is the most specific
     * level of <code>message-time-to-live</code>. It is a map of its own and not the task
     * level, so a value meant for a message never applies to a task of the same name.
     */
    private Map<String, Camunda7MessageProperties> messages = Map.of();

    /**
     * The messages of this workflow, keyed by message name.
     *
     * @return The message sections of this workflow, empty where there are none
     */
    public Map<String, Camunda7MessageProperties> getMessages() {
      return messages;
    }

    /**
     * The messages of this workflow, keyed by message name.
     *
     * @param messages The message sections Spring Boot bound
     */
    public void setMessages(
        final Map<String, Camunda7MessageProperties> messages) {
      this.messages = messages;
    }

    /**
     * The per-adapter-id overrides of this workflow.
     *
     * @return The adapter sections of this workflow, empty where there are none
     */
    public Map<String, Camunda7ScopedProperties> getAdapters() {
      return adapters;
    }

    /**
     * The per-adapter-id overrides of this workflow.
     *
     * @param adapters The adapter sections Spring Boot bound
     */
    public void setAdapters(
        final Map<String, Camunda7ScopedProperties> adapters) {
      this.adapters = adapters;
    }

    /**
     * The tasks of this workflow, keyed by task definition.
     *
     * @return The task sections of this workflow, empty where there are none
     */
    public Map<String, Camunda7TaskProperties> getTasks() {
      return tasks;
    }

    /**
     * The tasks of this workflow, keyed by task definition.
     *
     * @param tasks The task sections Spring Boot bound
     */
    public void setTasks(
        final Map<String, Camunda7TaskProperties> tasks) {
      this.tasks = tasks;
    }

  }

  /**
   * The Camunda 7 keys of one task. This is the most specific level, and
   * <code>allow-listeners</code> is not resolved at it.
   */
  public static class Camunda7TaskProperties {

    /**
     * Spring Boot builds one per section it finds and fills it through the setters.
     */
    public Camunda7TaskProperties() {

    }

    /** The per-adapter-id overrides of this task. */
    private Map<String, Camunda7ScopedProperties> adapters = Map.of();

    /**
     * The per-adapter-id overrides of this task.
     *
     * @return The adapter sections of this task, empty where there are none
     */
    public Map<String, Camunda7ScopedProperties> getAdapters() {
      return adapters;
    }

    /**
     * The per-adapter-id overrides of this task.
     *
     * @param adapters The adapter sections Spring Boot bound
     */
    public void setAdapters(
        final Map<String, Camunda7ScopedProperties> adapters) {
      this.adapters = adapters;
    }

  }

  /**
   * The Camunda 7 keys of one message of a workflow. Only <code>message-time-to-live</code> is
   * read at this level.
   */
  public static class Camunda7MessageProperties {

    /**
     * Spring Boot builds one per section it finds and fills it through the setters.
     */
    public Camunda7MessageProperties() {

    }

    /** The per-adapter-id overrides of this message. */
    private Map<String, Camunda7MessageScopedProperties> adapters = Map.of();

    /**
     * The per-adapter-id overrides of this message.
     *
     * @return The adapter sections of this message, empty where there are none
     */
    public Map<String, Camunda7MessageScopedProperties> getAdapters() {
      return adapters;
    }

    /**
     * The per-adapter-id overrides of this message.
     *
     * @param adapters The adapter sections Spring Boot bound
     */
    public void setAdapters(
        final Map<String, Camunda7MessageScopedProperties> adapters) {
      this.adapters = adapters;
    }

  }

  /**
   * The Camunda 7 keys of one message's adapter section. Only the time-to-live is read for
   * a message, so it is the only key here.
   */
  public static class Camunda7MessageScopedProperties {

    /**
     * Spring Boot builds one per section it finds and fills it through the setters.
     */
    public Camunda7MessageScopedProperties() {

    }

    /**
     * How long a correlation of this message waits for a subscription.
     */
    private java.time.Duration messageTimeToLive;

    /**
     * How long a correlation of this message waits for a subscription.
     *
     * @return The time-to-live, <code>null</code> where this message says nothing
     */
    public java.time.Duration getMessageTimeToLive() {
      return messageTimeToLive;
    }

    /**
     * How long a correlation of this message waits for a subscription.
     *
     * @param messageTimeToLive The time-to-live of this message
     */
    public void setMessageTimeToLive(
        final java.time.Duration messageTimeToLive) {
      this.messageTimeToLive = messageTimeToLive;
    }

  }

  /**
   * The Camunda 7 keys which may be set per workflow module and per workflow.
   */
  public static class Camunda7ScopedProperties {

    /**
     * Spring Boot builds one per section it finds and fills it through the setters.
     */
    public Camunda7ScopedProperties() {

    }

    /**
     * The serialization format of the shared values the engine has no variable type for,
     * for this scope.
     */
    private String serializationFormat;

    /**
     * Whether the execution listeners somebody modelled are served by
     * <code>@WorkflowTask</code> methods, for this scope. It is a {@code Boolean}, not a
     * primitive, because "unset" has to differ from {@code false}. That lets a workflow
     * module switch OFF what the adapter switched on.
     */
    private Boolean allowListeners;

    /**
     * How long a correlation waits for a subscription, for this scope. It is read per
     * workflow module, per workflow and per message.
     */
    private java.time.Duration messageTimeToLive;

    /**
     * How long a correlation waits for a subscription, for this scope.
     *
     * @return The time-to-live, <code>null</code> where this scope says nothing
     */
    public java.time.Duration getMessageTimeToLive() {
      return messageTimeToLive;
    }

    /**
     * How long a correlation waits for a subscription, for this scope.
     *
     * @param messageTimeToLive The time-to-live of this scope
     */
    public void setMessageTimeToLive(
        final java.time.Duration messageTimeToLive) {
      this.messageTimeToLive = messageTimeToLive;
    }

    /**
     * The serialization format of the shared values the engine has no variable type
     * for, for this scope.
     *
     * @return The configured format, <code>null</code> where this scope says nothing
     */
    public String getSerializationFormat() {
      return serializationFormat;
    }

    /**
     * The serialization format of the shared values the engine has no variable type
     * for, for this scope.
     *
     * @param serializationFormat The format this scope asks for
     */
    public void setSerializationFormat(
        final String serializationFormat) {
      this.serializationFormat = serializationFormat;
    }

    /**
     * Whether the execution listeners somebody modelled are served by
     * <code>@WorkflowTask</code> methods, for this scope.
     *
     * @return The configured answer, <code>null</code> where this scope says nothing
     */
    public Boolean getAllowListeners() {
      return allowListeners;
    }

    /**
     * Whether the execution listeners somebody modelled are served by
     * <code>@WorkflowTask</code> methods, for this scope.
     *
     * @param allowListeners The answer of this scope, <code>null</code> where it says nothing
     */
    public void setAllowListeners(
        final Boolean allowListeners) {
      this.allowListeners = allowListeners;
    }

  }

  /**
   * The Camunda 7 keys of one workflow module's adapter section. These are the scoped keys
   * every level has, plus the tenant. Only a workflow module may override the tenant. The
   * reason: a tenant id belongs to the deployment, and this adapter makes one deployment
   * per workflow module.
   */
  public static class Camunda7ModuleScopedProperties extends Camunda7ScopedProperties {

    /**
     * Spring Boot builds one per section it finds and fills it through the setters.
     */
    public Camunda7ModuleScopedProperties() {

    }

    /**
     * The Camunda tenant this workflow module is deployed into, overriding the name the
     * adapter section gives every module of this application.
     */
    private String tenantId;

    /**
     * The Camunda tenant this workflow module is deployed into.
     *
     * @return The configured tenant, <code>null</code> where the adapter section decides
     */
    public String getTenantId() {
      return tenantId;
    }

    /**
     * The Camunda tenant this workflow module is deployed into.
     *
     * @param tenantId The name of the tenant
     */
    public void setTenantId(
        final String tenantId) {
      this.tenantId = tenantId;
    }

  }

  /**
   * How long a correlation of a message waits for a subscription, most specific first: the
   * message, the workflow, its workflow module, the adapter. Below a workflow the message is
   * the most specific level, not a task.
   *
   * @param adapterId The adapter id
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID of the process whose model waits for the message
   * @param messageName The message name as the application wrote it
   * @return The time-to-live or <code>null</code> where none is configured
   */
  public java.time.Duration messageTimeToLiveFor(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String messageName) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module
            .getWorkflows()
            .get(bpmnProcessId)
        : null;
    final var message = (workflow != null) && (messageName != null)
        ? workflow
            .getMessages()
            .get(messageName)
        : null;
    final var perMessage = message != null
        ? message
            .getAdapters()
            .get(adapterId)
        : null;
    if ((perMessage != null) && (perMessage.getMessageTimeToLive() != null)) {
      return perMessage.getMessageTimeToLive();
    }
    for (final var scoped : java.util.Arrays
        .asList(
            workflow != null
                ? workflow.getAdapters()
                : null,
            module != null
                ? module.getAdapters()
                : null)) {
      final var section = scoped != null
          ? scoped.get(adapterId)
          : null;
      if ((section != null) && (section.getMessageTimeToLive() != null)) {
        return section.getMessageTimeToLive();
      }
    }
    return enginePropertiesFor(adapterId).getMessageTimeToLive();

  }

  /**
   * The keys of every <code>message-time-to-live</code> of an adapter id which is below zero.
   * A negative time makes no sense, so the boot names them and ends.
   *
   * @param adapterId The adapter id
   * @return The keys, empty where every value is zero or more
   */
  public java.util.List<String> negativeMessageTimeToLiveKeys(
      final String adapterId) {

    final var keys = new java.util.ArrayList<String>();
    final java.util.function.BiConsumer<String, java.time.Duration> check = (
        key,
        value) -> {
      if ((value != null) && value.isNegative()) {
        keys.add("%s=%s".formatted(key, value));
      }
    };
    check.accept("vanillabp.adapters.%s.message-time-to-live".formatted(adapterId), enginePropertiesFor(adapterId)
        .getMessageTimeToLive());
    workflowModules.forEach((
        moduleId,
        module) -> {
      final var perModule = module.getAdapters().get(adapterId);
      if (perModule != null) {
        check.accept("vanillabp.workflow-modules.%s.adapters.%s.message-time-to-live".formatted(moduleId, adapterId),
            perModule.getMessageTimeToLive());
      }
      module.getWorkflows().forEach((
          workflowId,
          workflow) -> {
        final var perWorkflow = workflow.getAdapters().get(adapterId);
        if (perWorkflow != null) {
          check.accept("vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.message-time-to-live"
              .formatted(moduleId, workflowId, adapterId), perWorkflow.getMessageTimeToLive());
        }
        workflow.getMessages().forEach((
            messageName,
            message) -> {
          final var perMessage = message.getAdapters().get(adapterId);
          if (perMessage != null) {
            check.accept("vanillabp.workflow-modules.%s.workflows.%s.messages.%s.adapters.%s.message-time-to-live"
                .formatted(moduleId, workflowId, messageName, adapterId), perMessage.getMessageTimeToLive());
          }
        });
      });
    });
    return keys;

  }

  /**
   * The serialization format configured for one workflow, most specific first: the
   * workflow, its workflow module, the adapter.
   *
   * @param adapterId The adapter id
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The format or <code>null</code> where none is configured
   */
  public String serializationFormatFor(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module
            .getWorkflows()
            .get(bpmnProcessId)
        : null;
    return io.vanillabp.camunda7.sync.Camunda7SerializationFormats
        .firstConfigured(
            scopedFormat(workflow != null
                ? workflow.getAdapters()
                : null, adapterId),
            scopedFormat(module != null
                ? module.getAdapters()
                : null, adapterId),
            enginePropertiesFor(adapterId).getSerializationFormat());

  }

  private static String scopedFormat(
      final Map<String, ? extends Camunda7ScopedProperties> adapters,
      final String adapterId) {

    final var scoped = adapters != null
        ? adapters.get(adapterId)
        : null;
    return scoped != null
        ? scoped.getSerializationFormat()
        : null;

  }

  /**
   * Resolves whether the execution listeners somebody modelled are served, most specific
   * first: the workflow, its workflow module, the adapter. The most specific CONFIGURED value
   * wins in both directions.
   *
   * @param adapterId The adapter id
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The setting together with the key it stands in, never <code>null</code>
   */
  public io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver.Setting allowListenersFor(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var workflow = (module != null) && (bpmnProcessId != null)
        ? module
            .getWorkflows()
            .get(bpmnProcessId)
        : null;
    final var perWorkflow = workflow != null
        ? workflow
            .getAdapters()
            .get(adapterId)
        : null;
    if ((perWorkflow != null) && (perWorkflow.getAllowListeners() != null)) {
      return new io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver.Setting(
          perWorkflow.getAllowListeners(), "vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.%s"
              .formatted(
                  workflowModuleId, bpmnProcessId, adapterId,
                  io.vanillabp.camunda7.wiring.Camunda7Listeners.ALLOW_LISTENERS_KEY));
    }
    final var perModule = module != null
        ? module
            .getAdapters()
            .get(adapterId)
        : null;
    if ((perModule != null) && (perModule.getAllowListeners() != null)) {
      return new io.vanillabp.camunda7.wiring.Camunda7AllowListenersResolver.Setting(
          perModule.getAllowListeners(), "vanillabp.workflow-modules.%s.adapters.%s.%s"
              .formatted(
                  workflowModuleId, adapterId,
                  io.vanillabp.camunda7.wiring.Camunda7Listeners.ALLOW_LISTENERS_KEY));
    }
    if (enginePropertiesFor(adapterId).isAllowListeners()) {
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
   * @return The keys found, in configuration order
   */
  public java.util.List<String> allowListenersKeysAtTaskLevel(
      final String adapterId) {

    return workflowModules
        .entrySet()
        .stream()
        .flatMap(module -> module
            .getValue()
            .getWorkflows()
            .entrySet()
            .stream()
            .flatMap(workflow -> workflow
                .getValue()
                .getTasks()
                .entrySet()
                .stream()
                .filter(task -> {
                  final var keys = task
                      .getValue()
                      .getAdapters()
                      .get(adapterId);
                  return (keys != null) && (keys.getAllowListeners() != null);
                })
                .map(task -> "vanillabp.workflow-modules.%s.workflows.%s.tasks.%s.adapters.%s.%s"
                    .formatted(
                        module.getKey(), workflow.getKey(), task.getKey(), adapterId,
                        io.vanillabp.camunda7.wiring.Camunda7Listeners.ALLOW_LISTENERS_KEY))))
        .toList();

  }

  /**
   * The Camunda tenant configured for one workflow module of one adapter id. It is the
   * module's own name where it has one, otherwise the adapter's. What the mode then makes
   * of it is the adapter's business.
   *
   * @param adapterId The adapter id
   * @param workflowModuleId The workflow module ID
   * @return The name and the key it was read from, or <code>null</code> where nothing
   *         configured one
   */
  public io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant configuredTenantFor(
      final String adapterId,
      final String workflowModuleId) {

    final var module = workflowModuleId != null
        ? workflowModules.get(workflowModuleId)
        : null;
    final var perWorkflowModule = module != null
        ? module
            .getAdapters()
            .get(adapterId)
        : null;
    return io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant
        .firstConfigured(
            adapterId,
            workflowModuleId,
            perWorkflowModule != null
                ? perWorkflowModule.getTenantId()
                : null,
            enginePropertiesFor(adapterId).getTenantId());

  }

  /**
   * The engine settings of an adapter id, defaults if the section is absent.
   *
   * @param adapterId The adapter id
   * @return The engine settings (never <code>null</code>)
   */
  public Camunda7EngineProperties enginePropertiesFor(
      final String adapterId) {

    final var properties = adapters.get(adapterId);
    return properties != null
        ? properties
        : new Camunda7EngineProperties();

  }

}
