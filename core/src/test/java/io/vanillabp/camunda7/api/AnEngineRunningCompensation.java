package io.vanillabp.camunda7.api;

import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.persistence.entity.JobEntity;
import org.h2.jdbcx.JdbcDataSource;

import io.vanillabp.camunda7.wiring.Camunda7AsyncBpmnParseListener;
import io.vanillabp.camunda7.wiring.Camunda7TaskCancellationListener;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.camunda7.wiring.Camunda7UserTaskEventListener;

/**
 * A real embedded engine running a process which books, pays and then compensates both -
 * the model of <code>api/compensation-facts.bpmn</code>.
 * <p>
 * Whether two compensation handlers of one workflow run at the same time can only be read
 * from a running engine, and it has two answers: one for the model as a modeller writes it,
 * and one for the model as this adapter deploys it, which makes every service task an
 * asynchronous, exclusive job.
 */
final class AnEngineRunningCompensation implements AutoCloseable {

  static final String BPMN_PROCESS_ID = "CompensationFactsProcess";

  private final ProcessEngine processEngine;

  private AnEngineRunningCompensation(
      final String databaseName,
      final boolean asThisAdapterDeploysIt,
      final boolean withTheJobExecutor) {

    final var dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(databaseName));
    final var configuration = new StandaloneProcessEngineConfiguration();
    configuration.setDataSource(dataSource);
    configuration.setDatabaseSchemaUpdate("true");
    configuration.setJobExecutorActivate(withTheJobExecutor);
    configuration.setProcessEngineName(databaseName);
    configuration.setHistoryTimeToLive("P1D");
    if (asThisAdapterDeploysIt) {
      // the adapter's own parse listener, so the measurement is about what a VanillaBP
      // application really runs rather than about a model written for the test. The process
      // is registered the way the adapter registers a process it deployed, because the
      // listener leaves a process alone which this application did not deploy
      final var registry = new Camunda7TaskRegistry();
      registry.registerProcess("compensation", BPMN_PROCESS_ID, BPMN_PROCESS_ID);
      final var parseListeners = new java.util.ArrayList<org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener>();
      parseListeners
          .add(
              new Camunda7AsyncBpmnParseListener(
                  new Camunda7TaskCancellationListener(null, registry), new Camunda7UserTaskEventListener(null, registry)));
      configuration.setCustomPostBPMNParseListeners(parseListeners);
    }
    this.processEngine = configuration.buildProcessEngine();
    processEngine
        .getRepositoryService()
        .createDeployment()
        .addClasspathResource("api/compensation-facts.bpmn")
        .deploy();

  }

  static AnEngineRunningCompensation plain(
      final String databaseName) {

    return new AnEngineRunningCompensation(databaseName, false, false);

  }

  static AnEngineRunningCompensation asThisAdapterDeploysIt(
      final String databaseName,
      final boolean withTheJobExecutor) {

    return new AnEngineRunningCompensation(databaseName, true, withTheJobExecutor);

  }

  ProcessEngine processEngine() {

    return processEngine;

  }

  void start() {

    processEngine.getRuntimeService().startProcessInstanceByKey(BPMN_PROCESS_ID);

  }

  List<org.camunda.bpm.engine.runtime.Job> jobs() {

    return processEngine.getManagementService().createJobQuery().list();

  }

  static boolean isExclusive(
      final org.camunda.bpm.engine.runtime.Job job) {

    return ((JobEntity) job).isExclusive();

  }

  void executeJob(
      final String jobId) {

    processEngine.getManagementService().executeJob(jobId);

  }

  boolean asyncBeforeOf(
      final String activityId) {

    final var configuration = ((org.camunda.bpm.engine.impl.ProcessEngineImpl) processEngine)
        .getProcessEngineConfiguration();
    return configuration
        .getCommandExecutorTxRequired()
        .execute(commandContext -> configuration
            .getDeploymentCache()
            .findDeployedLatestProcessDefinitionByKey(BPMN_PROCESS_ID)
            .findActivity(activityId)
            .isAsyncBefore());

  }

  long runningWorkflows() {

    return processEngine.getRuntimeService().createProcessInstanceQuery().count();

  }

  @Override
  public void close() {

    processEngine.close();

  }

}
