package io.vanillabp.camunda7.quarkus.runtime;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.agroal.api.AgroalDataSource;
import io.vanillabp.camunda7.deployment.Camunda7WorkflowProcessingLifecycle;
import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.camunda7.engine.Camunda7JobExecutorLifecycle;
import io.vanillabp.camunda7.wiring.Camunda7AsyncBpmnParseListener;
import io.vanillabp.camunda7.wiring.Camunda7TaskExpressionManager;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import jakarta.transaction.TransactionManager;

/**
 * Owns ONE embedded Camunda 7 engine per configured adapter id on Quarkus. The engine is
 * named <code>vanillabp-camunda7-&lt;id&gt;</code>. This class is the Quarkus counterpart
 * of the Spring Boot module's engine holder. It wires:
 * <ul>
 *   <li>{@link Camunda7QuarkusProcessEngineConfiguration} on an Agroal datasource, with
 *       the CDI Narayana {@link TransactionManager}. The datasource is the application's
 *       default, or the NAMED datasource configured in
 *       <code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>.</li>
 *   <li>the engine classloader, pinned to the Quarkus runtime TCCL. Without it the job
 *       executor's threads fail with {@code ClassNotFoundException} on delegate classes.
 *       The engine keeps its own thread pool. It does not use a context-propagating
 *       {@code ManagedExecutor}. With the classloader pinned, the own pool does the job,
 *       and the C7 family runs in JVM mode only.</li>
 *   <li>the job executor. Its activation is DEFERRED until the deployment pipeline calls
 *       {@code startWorkflowProcessing}. The shared {@link Camunda7JobExecutorLifecycle}
 *       counts the modules, with the same semantics as on Spring Boot.</li>
 *   <li>where <code>sleep-until-something-is-due</code> is configured, the core's
 *       {@code Camunda7SleepingJobExecutor}. Its acquisition waits for the next due date
 *       and does not poll.</li>
 * </ul>
 */
// see decision 4 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public class Camunda7QuarkusEngineHolder implements Camunda7WorkflowProcessingLifecycle, AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(Camunda7QuarkusEngineHolder.class);

  private final String adapterId;

  /**
   * The task connectables of this engine. The deployment service registers them during
   * wireBpmn, and the engine's EL resolver looks them up.
   */
  private final Camunda7TaskRegistry taskRegistry = new Camunda7TaskRegistry();

  private final boolean usesSeparateDataSource;

  /**
   * Whether the core's entry point for ended workflows was handed over, see
   * {@link #deliversWorkflowEnded()}.
   */
  private final boolean deliversWorkflowEnded;

  private final ProcessEngine processEngine;

  private final Camunda7JobExecutorLifecycle jobExecutorLifecycle;

  private volatile boolean closed = false;

  /**
   * Whether this engine reports the end of a workflow. It does when the core handed over
   * its invoker. False means that every <code>&#64;WorkflowEnded</code> method of a process
   * deployed here stays silent. The deployment service says so, so the application is
   * not left waiting.
   *
   * @return Whether the end listener was attached
   */
  public boolean deliversWorkflowEnded() {

    return deliversWorkflowEnded;

  }

  /**
   * Whether the application asked to be told about the end of workflows of the
   * process definition the engine is parsing.
   *
   * @param workflowEndedInvoker The core's invoker
   * @param tenantId The tenant of the deployment (may be <code>null</code>)
   * @param processDefinitionKey The process definition key the engine parses
   * @return Whether an end listener has to be attached
   */
  private boolean workflowEndedHandlerExists(
      final io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker workflowEndedInvoker,
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

  /**
   * There is only ONE constructor, on purpose, and it takes every invoker the core offers.
   * This class used to have convenience constructors. They filled the missing invokers
   * with <code>null</code>, and that switched a feature off without a word. The engine
   * attached no end listener, the application booted without a warning, and a
   * <code>&#64;WorkflowEnded</code> method was never called. The adapter decides what an
   * engine does not support. A forgotten argument must not decide it.
   *
   * @param adapterId The adapter id
   * @param properties The adapter id's engine settings
   *        (<code>vanillabp.adapters.&lt;id&gt;.*</code>)
   * @param dataSource The Agroal datasource the engine runs on
   * @param usesSeparateDataSource Whether the datasource is a NAMED one, not the
   *        application's default. Then the engine cannot join the caller's transaction,
   *        and the task delivery has to take that into account
   * @param transactionManager The CDI (Narayana) transaction manager
   * @param workflowTaskInvoker Where the application's method is called, handed to the
   *        listeners this engine is given
   * @param bpmsInitiatedStartInvoker The core's notification of a workflow the engine
   *        started on its own, or <code>null</code> where no method asks for one
   * @param workflowEndedInvoker The core's notification of a workflow which ended, or
   *        <code>null</code> where the application has no method for it
   */
  public Camunda7QuarkusEngineHolder(
      final String adapterId,
      final Camunda7EngineProperties properties,
      final AgroalDataSource dataSource,
      final boolean usesSeparateDataSource,
      final TransactionManager transactionManager,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartInvoker bpmsInitiatedStartInvoker,
      final io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker workflowEndedInvoker) {

    this.adapterId = adapterId;
    this.usesSeparateDataSource = usesSeparateDataSource;
    this.deliversWorkflowEnded = workflowEndedInvoker != null;
    // every listener building an invocation context has to know whose transaction it
    // delivers in, and the datasource this engine was configured with is what decides it
    this.taskRegistry.setEngineRunsOnItsOwnDataSource(usesSeparateDataSource);

    // an adapter id which runs on a table prefix needs its tables to exist. Camunda's
    // schema management ignores the prefix and would create a set of unprefixed ACT_*
    // tables here. We ask BEFORE the engine is built, so those tables are never written
    io.vanillabp.camunda7.engine.Camunda7TablePrefixSchema.validate(adapterId, properties, dataSource);

    final var parseListener = new Camunda7AsyncBpmnParseListener(
        new io.vanillabp.camunda7.wiring.Camunda7TaskCancellationListener(
            workflowTaskInvoker, taskRegistry), new io.vanillabp.camunda7.wiring.Camunda7UserTaskEventListener(
                workflowTaskInvoker, taskRegistry), bpmsInitiatedStartInvoker == null
                    ? null
                    : kind -> new io.vanillabp.camunda7.wiring.Camunda7BpmsInitiatedStartListener(
                        bpmsInitiatedStartInvoker, taskRegistry, kind, taskRegistry::isClaimedByAWorkflowService));
    if (workflowEndedInvoker != null) {
      parseListener
          .setWorkflowEnded(
              new io.vanillabp.camunda7.wiring.Camunda7WorkflowEndedListener(
                  workflowEndedInvoker, taskRegistry),
              (
                  tenantId,
                  processDefinitionKey) -> workflowEndedHandlerExists(
                      workflowEndedInvoker, tenantId, processDefinitionKey));
    }

    final var configuration = new Camunda7QuarkusProcessEngineConfiguration(transactionManager);
    // VanillaBP task wiring: top-level EL names resolve @WorkflowTask methods and
    // workflow-aggregate attributes. The parse listener sets transaction boundaries
    // like a remote BPMS has them (async before/after)
    configuration.setExpressionManager(new Camunda7TaskExpressionManager(taskRegistry, workflowTaskInvoker));
    configuration.setCustomPreBPMNParseListeners(new java.util.ArrayList<>(
        java.util.List.of(parseListener)));
    configuration.setProcessEngineName("vanillabp-camunda7-%s".formatted(adapterId));
    configuration.setDataSource(dataSource);
    configuration.setDatabaseSchemaUpdate(properties.getDatabaseSchemaUpdate());
    // activation waits for startWorkflowProcessing, as on Spring Boot
    configuration.setJobExecutorActivate(false);
    // an adapter id which may let its engine sleep needs the acquisition loop which asks
    // for the next due date. The executor itself installs that loop. Without the sleep
    // the engine builds its own executor, exactly as before
    if (properties.sleepsUntilSomethingIsDue()) {
      configuration.setJobExecutor(new io.vanillabp.camunda7.engine.Camunda7SleepingJobExecutor(adapterId));
    }
    // what an idle engine may stop doing: it waits for the next due date and does not
    // poll, it wakes on a commit, and it leaves the metrics reporter's timer alone. This
    // is applied for every adapter id, because the metrics reporter is a setting of its own
    io.vanillabp.camunda7.engine.Camunda7JobExecutorSleep.applyTo(adapterId, configuration, properties);

    // the FEEL implementation decisions are evaluated with. The older one needs no FEEL
    // engine library, which is what an application needs next to the Operaton adapter
    io.vanillabp.camunda7.engine.Camunda7DmnFeelLegacyBehavior.applyTo(adapterId, configuration, properties);
    // job-executor threads must load delegate classes through the Quarkus runtime
    // classloader. Otherwise they fail with ClassNotFoundException; we have seen it
    configuration.setClassLoader(Thread.currentThread().getContextClassLoader());
    // Camunda 7.24 rejects deployments of processes without a history-time-to-live.
    // A default for the whole engine means that BPMN models need not declare it one by
    // one. A process may still override it through camunda:historyTimeToLive.
    configuration.setHistoryTimeToLive(properties.getHistoryTimeToLive());
    // nested values shared by a workflow aggregate become object variables. The
    // application chooses the format they are stored in. It is configured once at the
    // adapter and applied to the engine here, so nobody has to touch the engine
    // configuration for it
    if ((properties.getSerializationFormat() != null) && !properties.getSerializationFormat().isBlank()) {
      configuration.setDefaultSerializationFormat(properties.getSerializationFormat());
    }
    // a table prefix of its own makes two adapter ids distinct engines on ONE
    // datasource. That is the side-by-side migration setup on a single database. The
    // tables of the prefix exist, because Camunda7TablePrefixSchema checked that above
    if ((properties.getTablePrefix() != null) && !properties.getTablePrefix().isBlank()) {
      configuration.setDatabaseTablePrefix(properties.getTablePrefix());
    }

    // the engine plugins. This is how a serialization dataformat (camunda-xstream, SPIN)
    // reaches an embedded engine. There are two ways in. One is the configuration per
    // adapter id ('vanillabp.adapters.<id>.engine-plugins'), whose properties Camunda
    // applies itself. The other is a CDI bean, which applies to every engine this
    // adapter builds
    final var plugins = new java.util.LinkedList<org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin>(
        io.vanillabp.camunda7.engine.Camunda7EnginePlugins
            .of(adapterId, properties.getEnginePlugins()));
    plugins
        .addAll(
            io.quarkus.arc.Arc
                .container()
                .listAll(org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin.class)
                .stream()
                .map(io.quarkus.arc.InstanceHandle::get)
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

    // what an extension contributes to THIS engine: parse listeners before or after
    // VanillaBP's own, and a history event handler installed next to the engine's
    io.vanillabp.camunda7.engine.Camunda7EngineCustomizers
        .apply(
            adapterId,
            configuration,
            io.quarkus.arc.Arc
                .container()
                .listAll(io.vanillabp.camunda7.engine.Camunda7EngineCustomizer.class)
                .stream()
                .map(io.quarkus.arc.InstanceHandle::get)
                .toList());

    try {
      this.processEngine = configuration.buildProcessEngine();
    } catch (final RuntimeException | LinkageError e) {
      // a FEEL engine library which does not fit Camunda 7 surfaces here as a linkage
      // error. The developer gets the remedy instead of a NoSuchMethodError
      io.vanillabp.camunda7.engine.Camunda7DmnFeelLegacyBehavior.explainWhyTheEngineDidNotStart(adapterId, properties,
          e);
      throw e;
    }
    this.jobExecutorLifecycle = new Camunda7JobExecutorLifecycle(
        adapterId, ((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration()).getJobExecutor());

  }

  /**
   * What the listeners of this engine look a task up in. One registry per engine, because
   * one engine serves the workflow modules of one adapter id.
   *
   * @return This engine's task registry
   */
  public Camunda7TaskRegistry getTaskRegistry() {

    return taskRegistry;

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
   * The engine's tasks, where a user task is completed.
   *
   * @return The task service of this engine
   */
  public org.camunda.bpm.engine.TaskService getTaskService() {

    return processEngine.getTaskService();

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
   * datasource an engine command joins the caller's transaction. That guarantee is why
   * people choose an embedded engine. On a named datasource it cannot join.
   *
   * @return Whether this adapter id's engine runs on a NAMED datasource (see class
   *         comment)
   */
  public boolean usesSeparateDataSource() {

    return usesSeparateDataSource;

  }

  /**
   * Visible for tests asserting the deferred activation.
   *
   * @return Whether the job executor is currently active
   */
  public boolean isJobExecutorActive() {

    return jobExecutorLifecycle.isActive();

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
   * Shutdown order: job executor stop &rarr; engine close. Quarkus owns and closes the
   * Agroal datasource itself. Safe to call more than once.
   */
  @Override
  public synchronized void close() {

    if (closed) {
      return;
    }
    closed = true;

    jobExecutorLifecycle.shutdown();
    processEngine.close();
    log.info("Camunda7[{}]: engine closed", adapterId);

  }

}
