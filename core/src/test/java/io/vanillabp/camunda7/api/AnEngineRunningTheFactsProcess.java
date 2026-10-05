package io.vanillabp.camunda7.api;

import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.h2.jdbcx.JdbcDataSource;

/**
 * A real embedded engine running the one model the tests of this package need: a user task
 * with an EXPRESSION form key, sitting inside a sequential multi-instance subprocess.
 * <p>
 * Both facts these tests are about can only be read from a running engine. The multi-instance
 * walk needs the execution tree, and the difference between a written form key and an
 * evaluated one only exists once something evaluated it.
 */
final class AnEngineRunningTheFactsProcess implements AutoCloseable {

  static final String BPMN_PROCESS_ID = "EngineFactsProcess";

  static final String USER_TASK_ID = "Approve";

  static final String MULTI_INSTANCE_ELEMENT_ID = "Orders";

  /** What the model writes as the form key, expression and all. */
  static final String FORM_KEY_AS_WRITTEN = "${formKeyOfTheDay}";

  /** What that expression evaluates to while the workflow below runs. */
  static final String FORM_KEY_EVALUATED = "daily-form";

  static final List<String> ORDERS = List.of("first", "second");

  private final ProcessEngine processEngine;

  private final String processDefinitionId;

  private AnEngineRunningTheFactsProcess(
      final String databaseName) {

    final var dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(databaseName));
    final var configuration = new StandaloneProcessEngineConfiguration();
    configuration.setDataSource(dataSource);
    configuration.setDatabaseSchemaUpdate("true");
    configuration.setJobExecutorActivate(false);
    configuration.setProcessEngineName(databaseName);
    configuration.setHistoryTimeToLive("P1D");
    this.processEngine = configuration.buildProcessEngine();
    final var deployment = processEngine
        .getRepositoryService()
        .createDeployment()
        .addClasspathResource("api/engine-facts.bpmn")
        .deployWithResult();
    this.processDefinitionId = deployment
        .getDeployedProcessDefinitions()
        .getFirst()
        .getId();

  }

  /**
   * @param databaseName A name of this test's own, so two engines never meet in one H2
   * @return The engine, with the model deployed and one workflow waiting at the user task
   */
  static AnEngineRunningTheFactsProcess started(
      final String databaseName) {

    final var engine = new AnEngineRunningTheFactsProcess(databaseName);
    engine.processEngine
        .getRuntimeService()
        .startProcessInstanceByKey(
            BPMN_PROCESS_ID,
            Map.of("orders", ORDERS, "formKeyOfTheDay", FORM_KEY_EVALUATED));
    return engine;

  }

  ProcessEngine processEngine() {

    return processEngine;

  }

  String processDefinitionId() {

    return processDefinitionId;

  }

  /**
   * @return The one user task the workflow waits at
   */
  org.camunda.bpm.engine.task.Task waitingUserTask() {

    return processEngine
        .getTaskService()
        .createTaskQuery()
        .taskDefinitionKey(USER_TASK_ID)
        .initializeFormKeys()
        .singleResult();

  }

  @Override
  public void close() {

    processEngine.close();

  }

}
