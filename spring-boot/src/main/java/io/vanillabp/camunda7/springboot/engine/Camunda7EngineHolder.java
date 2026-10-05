package io.vanillabp.camunda7.springboot.engine;

import javax.sql.DataSource;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.spring.SpringProcessEngineConfiguration;
import org.camunda.bpm.engine.spring.components.jobexecutor.SpringJobExecutor;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.camunda7.deployment.Camunda7WorkflowProcessingLifecycle;
import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.camunda7.engine.Camunda7JobExecutorLifecycle;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns ONE embedded Camunda 7 engine per configured adapter id (engine name
 * <code>vanillabp-camunda7-&lt;id&gt;</code>) including its complete lifecycle:
 * <ul>
 *   <li><b>Datasource:</b> by default the engine shares the application's
 *       {@link DataSource} and {@link PlatformTransactionManager} - the whole point
 *       of the embedded adapter (engine state commits/rolls back with the business
 *       data). With <code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>
 *       configured, the engine runs on the application-provided {@link DataSource}
 *       BEAN of that name instead (setting up datasources is deliberately NOT
 *       VanillaBP's concern - the adapter never builds its own pool; required for
 *       engine-side-by-side migrations, where two embedded engines must never share
 *       one schema). Engine commands on such a named datasource run on an
 *       adapter-internal {@link DataSourceTransactionManager} - they do not join
 *       the caller's transaction, see the two-phase notes on
 *       {@code Camunda7ProcessService}.</li>
 *   <li><b>Job executor:</b> an idiomatic {@link SpringJobExecutor} backed by a
 *       dedicated {@link ThreadPoolTaskExecutor} (thread-name prefix contains the
 *       adapter id) instead of the engine-default {@code DefaultJobExecutor} with
 *       its raw unmanaged threads. Activation is DEFERRED: the engine is built with
 *       {@code jobExecutorActivate=false}; the deployment pipeline's
 *       {@code startWorkflowProcessing} starts the executor once the first workflow
 *       module starts (reference-counted per module by the shared
 *       {@link Camunda7JobExecutorLifecycle}) - and it stops unconditionally on
 *       {@link #close()}, before the engine closes. Where
 *       <code>sleep-until-something-is-due</code> is configured it is a
 *       {@link Camunda7SleepingSpringJobExecutor} instead, whose acquisition waits for the
 *       next due date rather than polling.</li>
 * </ul>
 */
@Slf4j
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public class Camunda7EngineHolder implements Camunda7WorkflowProcessingLifecycle, ApplicationContextAware, InitializingBean, AutoCloseable {

  private final String adapterId;

  /**
   * The task connectables of this engine, registered by the deployment service
   * during wireBpmn and looked up by the engine's EL resolver.
   */
  @Getter
  private final Camunda7TaskRegistry taskRegistry = new Camunda7TaskRegistry();

  private final WorkflowTaskInvoker workflowTaskInvoker;

  private final Camunda7EngineProperties properties;

  private final DataSource applicationDataSource;

  private final PlatformTransactionManager applicationTransactionManager;

  /**
   * Required by {@code SpringProcessEngineConfiguration} (Spring-bean resolution in
   * scripting/expressions) and for resolving a named datasource bean - injected via
   * {@link ApplicationContextAware}; the engine is built in
   * {@link #afterPropertiesSet()} once the context is available.
   */
  private ApplicationContext applicationContext;

  private ProcessEngine processEngine;

  private SpringJobExecutor jobExecutor;

  /**
   * The reference-counted executor lifecycle shared with the Quarkus module (the
   * executor is engine-global while start/stop is notified per workflow module).
   */
  private Camunda7JobExecutorLifecycle jobExecutorLifecycle;

  private ThreadPoolTaskExecutor taskExecutor;

  private volatile boolean closed = false;

  /**
   * The core's entry point for workflows the engine starts on its own (timer, signal
   * or conditional start events). May be <code>null</code> - the engine then attaches
   * no start listener, and such processes never obtain a workflow aggregate.
   */
  private io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker;

  /**
   * The core's entry point for workflows which ended. May be
   * <code>null</code> - the engine then attaches no end listener.
   */
  private final io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker workflowEndedInvoker;

  /**
   * There is deliberately only ONE constructor, and it takes every invoker the core
   * offers - the same rule as on Quarkus. A convenience constructor (or a setter
   * called later) leaves an invoker at <code>null</code>, which switches a feature
   * off silently: the engine attaches no end listener, the application boots without
   * a warning and a <code>&#64;WorkflowEnded</code> method is never called.
   * <p>
   * The engine itself is built in {@link #afterPropertiesSet()}.
   *
   * @param adapterId The adapter id
   * @param properties The adapter id's engine settings
   * @param applicationDataSource The application's data source (null for an id using
   *        a named one)
   * @param applicationTransactionManager The application's transaction manager (null
   *        for an id using a named data source)
   * @param workflowTaskInvoker The core's entry point for BPMN tasks
   * @param bpmsInitiatedStartInvoker The core's entry point for workflows the engine
   *        starts on its own (may be <code>null</code> if the core provides none)
   * @param workflowEndedInvoker The core's entry point for workflows which ended (may
   *        be <code>null</code> if the core provides none)
   */
  public Camunda7EngineHolder(
      final String adapterId,
      final Camunda7EngineProperties properties,
      final DataSource applicationDataSource,
      final PlatformTransactionManager applicationTransactionManager,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker,
      final io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker workflowEndedInvoker) {

    this.adapterId = adapterId;
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.bpmsInitiatedStartInvoker = bpmsInitiatedStartInvoker;
    this.workflowEndedInvoker = workflowEndedInvoker;
    this.properties = properties;
    this.applicationDataSource = applicationDataSource;
    this.applicationTransactionManager = applicationTransactionManager;
    // every listener building an invocation context has to know whose transaction it
    // delivers in, and the datasource this engine was configured with is what decides it
    this.taskRegistry.setEngineRunsOnItsOwnDataSource(usesSeparateDataSource());

  }

  /**
   * Builds the listener attached to every start event of a process - <code>null</code>
   * where the core's entry point was not handed over (tests).
   *
   * @return The factory or <code>null</code>
   */
  private java.util.function.Function<io.vanillabp.spi.service.BpmsStartTrigger.Kind, org.camunda.bpm.engine.delegate.ExecutionListener> startListenerFactory() {

    if (bpmsInitiatedStartInvoker == null) {
      return null;
    }
    return kind -> new io.vanillabp.camunda7.wiring.Camunda7BpmsInitiatedStartListener(
        bpmsInitiatedStartInvoker, taskRegistry, kind, this::servesTheProcess);

  }

  /**
   * Whether a workflow service of this application serves that BPMN process. The engine
   * holds every definition deployed against its database, so a start of a process this
   * application does not claim reaches the listener as well - and the core has no workflow
   * service to answer for it.
   *
   * @param workflowModuleId The workflow module
   * @param bpmnProcessId The plain BPMN process id
   * @return Whether the core knows a workflow service for it
   */
  private boolean servesTheProcess(
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (workflowTaskInvoker == null) {
      return true;
    }
    try {
      return workflowTaskInvoker.resolveWorkflowAggregateIdName(workflowModuleId, bpmnProcessId) != null;
    } catch (final RuntimeException e) {
      return false;
    }

  }

  /**
   * Whether this engine reports the end of a workflow, which it does when the core
   * handed over its invoker. False means every <code>&#64;WorkflowEnded</code> method
   * of a process deployed here stays silent - the deployment service says so instead
   * of leaving the application waiting.
   *
   * @return Whether the end listener was attached
   */
  public boolean deliversWorkflowEnded() {

    return workflowEndedInvoker != null;

  }

  /**
   * Whether the application asked to be told about the end of workflows of the
   * process definition the engine is parsing. The wiring already registered which
   * workflow module and plain process id the definition key belongs to.
   *
   * @param tenantId The tenant of the deployment (may be <code>null</code>)
   * @param processDefinitionKey The process definition key the engine parses
   * @return Whether an end listener has to be attached
   */
  private boolean workflowEndedHandlerExists(
      final String tenantId,
      final String processDefinitionKey) {

    final var workflowModuleId = taskRegistry.resolveWorkflowModuleId(tenantId, processDefinitionKey);
    if (workflowModuleId == null) {
      return false;
    }
    return workflowEndedInvoker
        .workflowEndedHandlerExists(
            workflowModuleId,
            taskRegistry.plainBpmnProcessId(workflowModuleId, processDefinitionKey));

  }

  @Override
  public void setApplicationContext(
      final ApplicationContext applicationContext) {

    this.applicationContext = applicationContext;

  }

  @Override
  public void afterPropertiesSet() {

    final DataSource dataSource;
    final PlatformTransactionManager transactionManager;
    if (properties.usesSeparateDataSource()) {
      dataSource = resolveNamedDataSource();
      // engine commands on the named datasource run on an adapter-internal
      // transaction manager - they cannot join the caller's transaction (which is
      // why such adapter ids start workflows two-phase)
      transactionManager = new DataSourceTransactionManager(dataSource);
      log.info(
          "Camunda7[{}]: using the application-provided datasource bean '{}' - the engine does not "
              + "join the application's transactions; starting workflows uses the two-phase pattern",
          adapterId,
          properties.getDataSourceName());
    } else {
      dataSource = applicationDataSource;
      transactionManager = applicationTransactionManager;
    }

    // An adapter id running on a table prefix needs its tables to exist -
    // Camunda's schema management ignores the prefix and would create a set of
    // unprefixed ACT_* tables here. Asked BEFORE the engine is built, so those tables
    // are never written
    io.vanillabp.camunda7.engine.Camunda7TablePrefixSchema.validate(adapterId, properties, dataSource);

    // idiomatic job executor: SpringJobExecutor on a managed thread pool instead of
    // the engine-default DefaultJobExecutor (raw threads, activated at engine build)
    this.taskExecutor = new ThreadPoolTaskExecutor();
    this.taskExecutor.setThreadNamePrefix("vanillabp-camunda7-%s-jobs-".formatted(adapterId));
    this.taskExecutor.setCorePoolSize(3);
    this.taskExecutor.setMaxPoolSize(10);
    this.taskExecutor.setQueueCapacity(10);
    this.taskExecutor.initialize();
    // an adapter id which may let its engine sleep needs the acquisition loop which asks
    // for the next due date, and that loop is installed by the executor itself
    this.jobExecutor = properties.sleepsUntilSomethingIsDue()
        ? new Camunda7SleepingSpringJobExecutor(adapterId)
        : new SpringJobExecutor();
    this.jobExecutor.setTaskExecutor(this.taskExecutor);

    final var configuration = new SpringProcessEngineConfiguration();
    configuration.setApplicationContext(applicationContext);
    // VanillaBP task wiring: top-level EL names resolve @WorkflowTask methods and
    // workflow-aggregate attributes (Spring beans stay resolvable); the parse
    // listener aligns transaction boundaries with remote BPMS (async before/after)
    configuration.setExpressionManager(new Camunda7SpringExpressionManager(
        applicationContext, taskRegistry, workflowTaskInvoker));
    final var parseListener = new io.vanillabp.camunda7.wiring.Camunda7AsyncBpmnParseListener(
        new io.vanillabp.camunda7.wiring.Camunda7TaskCancellationListener(
            workflowTaskInvoker, taskRegistry), new io.vanillabp.camunda7.wiring.Camunda7UserTaskEventListener(
                workflowTaskInvoker, taskRegistry), startListenerFactory());
    if (workflowEndedInvoker != null) {
      parseListener.setWorkflowEnded(
          new io.vanillabp.camunda7.wiring.Camunda7WorkflowEndedListener(workflowEndedInvoker, taskRegistry),
          (
              tenantId,
              processDefinitionKey) -> workflowEndedHandlerExists(tenantId, processDefinitionKey));
    }
    configuration.setCustomPreBPMNParseListeners(new java.util.ArrayList<>(java.util.List.of(parseListener)));
    configuration.setProcessEngineName("vanillabp-camunda7-%s".formatted(adapterId));
    configuration.setDataSource(dataSource);
    configuration.setTransactionManager(transactionManager);
    configuration.setDatabaseSchemaUpdate(properties.getDatabaseSchemaUpdate());
    configuration.setJobExecutor(this.jobExecutor);
    // activation is deferred to startWorkflowProcessing (see class comment)
    configuration.setJobExecutorActivate(false);
    // Camunda 7.24 rejects deployments of processes without a history-time-to-live.
    // Provide an engine-wide default so BPMN models need not declare it individually
    // (a process may still override it via camunda:historyTimeToLive).
    configuration.setHistoryTimeToLive(properties.getHistoryTimeToLive());
    // Nested values shared by a workflow aggregate become object variables, and
    // the format they are stored in is the application's choice - configured once at the
    // adapter and applied to the engine here, so nobody has to touch the engine
    // configuration for it. A workflow or a workflow module may override the format for
    // the variables VanillaBP writes itself
    if ((properties.getSerializationFormat() != null) && !properties.getSerializationFormat().isBlank()) {
      configuration.setDefaultSerializationFormat(properties.getSerializationFormat());
    }
    // an own table prefix makes two adapter ids distinct engines on ONE datasource
    // (the side-by-side migration setup on a single database). The tables
    // of the prefix exist - Camunda7TablePrefixSchema asked about that above
    if ((properties.getTablePrefix() != null) && !properties.getTablePrefix().isBlank()) {
      configuration.setDatabaseTablePrefix(properties.getTablePrefix());
    }

    // The engine plugins - the way a serialization dataformat (camunda-xstream,
    // SPIN) reaches an embedded engine. Two ways in: configured per adapter id
    // ('vanillabp.adapters.<id>.engine-plugins', properties applied by Camunda itself), or
    // contributed as a bean, which suits a plugin configuring itself from the
    // application's properties and applies to every engine this adapter builds
    final var plugins = new java.util.LinkedList<org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin>(
        io.vanillabp.camunda7.engine.Camunda7EnginePlugins
            .of(adapterId, properties.getEnginePlugins()));
    plugins
        .addAll(
            applicationContext
                .getBeanProvider(org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin.class)
                .orderedStream()
                .toList());
    if (!plugins.isEmpty()) {
      configuration
          .getProcessEnginePlugins()
          .addAll(plugins);
      log.info(
          "Camunda7[{}]: applying {} engine plugin(s) of the application: {}",
          adapterId,
          plugins.size(),
          plugins
              .stream()
              .map(plugin -> plugin.getClass().getName())
              .toList());
    }

    // What an idle engine is allowed to stop doing: waiting for the next due date instead
    // of polling, waking on a commit, and leaving the metrics reporter's timer alone.
    // Applied for every adapter id, because the metrics reporter is a setting of its own
    io.vanillabp.camunda7.engine.Camunda7JobExecutorSleep.applyTo(adapterId, configuration, properties);

    // what an extension contributes to THIS engine: parse listeners before or after
    // VanillaBP's own, and a history event handler installed next to the engine's
    io.vanillabp.camunda7.engine.Camunda7EngineCustomizers
        .apply(
            adapterId,
            configuration,
            applicationContext
                .getBeanProvider(io.vanillabp.camunda7.engine.Camunda7EngineCustomizer.class)
                .orderedStream()
                .toList());

    this.processEngine = configuration.buildProcessEngine();
    this.jobExecutorLifecycle = new Camunda7JobExecutorLifecycle(adapterId, this.jobExecutor);

  }

  /**
   * Resolves the application-provided {@link DataSource} bean referenced by
   * <code>data-source-name</code> - with a GUIDING failure listing the available
   * datasource beans (setting up datasources is the application's concern, so a
   * missing bean is a configuration defect the developer has to learn about with
   * the remedy named).
   */
  private DataSource resolveNamedDataSource() {

    final var dataSourceName = properties.getDataSourceName();
    try {
      return applicationContext.getBean(dataSourceName, DataSource.class);
    } catch (final BeansException e) {
      throw new IllegalStateException(
          """
              Camunda 7 adapter '%s' references the datasource bean '%s' \
              ('vanillabp.adapters.%s.data-source-name') but no such DataSource bean exists! Define a \
              DataSource bean of that name in your application (setting up datasources is the \
              application's concern - VanillaBP never builds its own pool). Available DataSource \
              beans: %s."""
              .formatted(
                  adapterId,
                  dataSourceName,
                  adapterId,
                  String.join(", ", applicationContext.getBeanNamesForType(DataSource.class))), e);
    }

  }

  /**
   * Which configured adapter id this engine belongs to.
   *
   * @return The adapter id
   */
  public String getAdapterId() {

    return adapterId;

  }

  /**
   * The engine itself, for a caller which needs something none of the services below
   * offers.
   *
   * @return The embedded engine of this adapter id
   */
  public ProcessEngine getProcessEngine() {

    return processEngine;

  }

  /**
   * The engine's runtime, where a workflow is started and a message is correlated.
   *
   * @return The runtime service of this engine
   */
  public RuntimeService getRuntimeService() {

    return processEngine.getRuntimeService();

  }

  /**
   * The engine's tasks, where a user task is completed.
   *
   * @return The task service of this engine
   */
  public org.camunda.bpm.engine.TaskService getTaskService() {

    return processEngine.getTaskService();

  }

  /**
   * The engine's repository, where the resources of a workflow module are deployed.
   *
   * @return The repository service of this engine
   */
  public RepositoryService getRepositoryService() {

    return processEngine.getRepositoryService();

  }

  /**
   * The engine's history, which is what the viewer API reads.
   *
   * @return The history service of this engine
   */
  public org.camunda.bpm.engine.HistoryService getHistoryService() {

    return processEngine.getHistoryService();

  }

  /**
   * Whether this engine has a datasource of its own. On the application's default
   * datasource an engine command joins the caller's transaction, which is the guarantee an
   * embedded engine is chosen for; on a named one it cannot.
   *
   * @return Whether this adapter id's engine runs on a named datasource (see class
   *         comment)
   */
  public boolean usesSeparateDataSource() {

    return properties.usesSeparateDataSource();

  }

  /**
   * Visible for tests asserting the deferred activation.
   *
   * @return Whether the job executor is currently active
   */
  public boolean isJobExecutorActive() {

    return (jobExecutorLifecycle != null) && jobExecutorLifecycle.isActive();

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId) {

    jobExecutorLifecycle.startWorkflowProcessing(workflowModuleId);

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId) {

    jobExecutorLifecycle.stopWorkflowProcessing(workflowModuleId);

  }

  /**
   * Shutdown ordering: job executor stop &rarr; engine close &rarr; thread pool
   * shutdown (the datasource itself is application-provided and NOT closed by the
   * adapter). Safe to call more than once.
   */
  @Override
  public synchronized void close() {

    if (closed) {
      return;
    }
    closed = true;

    if (jobExecutorLifecycle != null) {
      jobExecutorLifecycle.shutdown();
    }
    if (processEngine != null) {
      processEngine.close();
    }
    if (taskExecutor != null) {
      taskExecutor.shutdown();
    }
    log.info("Camunda7[{}]: engine closed", adapterId);

  }

}
