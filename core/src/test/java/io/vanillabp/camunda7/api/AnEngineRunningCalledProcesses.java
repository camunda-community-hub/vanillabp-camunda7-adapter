package io.vanillabp.camunda7.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.h2.jdbcx.JdbcDataSource;

import io.vanillabp.camunda7.wiring.Camunda7CallActivities;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;

/**
 * A real embedded engine running processes which call each other, prepared the way the
 * deployment prepares them.
 * <p>
 * Every called process lives in a BPMN file of ITS OWN, and that is the point of this
 * harness. The walk looks an enclosing element up by id, and with the calling and the
 * called process in one file that lookup finds the element of the other process by
 * chance. The defect this harness measures stayed invisible for exactly that reason, in
 * version 1 as well as in version 2.
 */
final class AnEngineRunningCalledProcesses implements AutoCloseable {

  static final String WORKFLOW_MODULE = "orders";

  /** The one process of these models which works on a workflow aggregate of its own. */
  static final String PROCESS_WITH_AN_AGGREGATE_OF_ITS_OWN = "OwnAggregateProcess";

  static final List<String> ORDERS = List.of("first", "second");

  static final List<String> REGIONS = List.of("north", "south");

  static final List<String> BATCHES = List.of("morning", "evening");

  static final List<String> ITEMS = List.of("screws", "nails");

  private static final List<String> MODELS = List
      .of("api/mi-callers.bpmn", "api/mi-outer-caller.bpmn", "api/mi-called.bpmn");

  /**
   * What the core answers about these models: everything of this workflow module
   * continues the aggregate of whoever called it, except the one process which has one of
   * its own.
   */
  private static final WorkflowTaskWiring CORE = new WorkflowTaskWiring() {

    @Override
    public boolean workflowsShareTheWorkflowAggregate(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String otherBpmnProcessId) {

      return WORKFLOW_MODULE
          .equals(workflowModuleId) && !PROCESS_WITH_AN_AGGREGATE_OF_ITS_OWN.equals(otherBpmnProcessId);

    }

    @Override
    public void validateTaskWiring(
        final String workflowModuleId,
        final String bpmnProcessId,
        final Collection<BpmnTaskSpec> tasks) {
    }

    @Override
    public void validateNoUnwiredWorkflowTaskMethods(
        final String workflowModuleId) {
    }

    @Override
    public String resolveWorkflowAggregateIdName(
        final String workflowModuleId,
        final String bpmnProcessId) {

      throw new UnsupportedOperationException("not part of this test");

    }

  };

  private final ProcessEngine processEngine;

  /**
   * The registry of this engine, filled the way the deployment fills it and told the same
   * answer about the workflow aggregate the core gives above. The walk needs it for a call
   * activity which names the process it calls in an expression, where no answer was written
   * into the model.
   */
  private final Camunda7TaskRegistry taskRegistry = new Camunda7TaskRegistry();

  private AnEngineRunningCalledProcesses(
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

    final var deployment = processEngine.getRepositoryService().createDeployment();
    MODELS
        .forEach(resource -> {
          final var model = Bpmn
              .readModelFromStream(
                  AnEngineRunningCalledProcesses.class.getClassLoader().getResourceAsStream(resource));
          // what the deployment does with a call activity, so the note about the
          // workflow aggregate is on the models this engine runs
          Camunda7CallActivities.prepareCallActivities(model, WORKFLOW_MODULE, CORE);
          model
              .getModelElementsByType(org.camunda.bpm.model.bpmn.instance.Process.class)
              .forEach(process -> taskRegistry.registerProcess(WORKFLOW_MODULE, process.getId(), process.getId()));
          deployment.addModelInstance(resource, model);
        });
    deployment.deploy();

    taskRegistry
        .setWorkflowAggregateSharing(
            CORE::workflowsShareTheWorkflowAggregate);

  }

  /**
   * @param databaseName A name of this test's own, so two engines never meet in one H2
   * @param bpmnProcessId The process to start
   * @param variables What that process iterates over
   * @return The engine, with the models deployed and one workflow started
   */
  static AnEngineRunningCalledProcesses started(
      final String databaseName,
      final String bpmnProcessId,
      final Map<String, Object> variables) {

    final var engine = new AnEngineRunningCalledProcesses(databaseName);
    engine.processEngine.getRuntimeService().startProcessInstanceByKey(bpmnProcessId, variables);
    return engine;

  }

  ProcessEngine processEngine() {

    return processEngine;

  }

  Camunda7TaskRegistry taskRegistry() {

    return taskRegistry;

  }

  /**
   * @param taskId The BPMN id of the user task
   * @return The execution the one waiting instance of that task hangs on
   */
  String executionWaitingAt(
      final String taskId) {

    return processEngine
        .getTaskService()
        .createTaskQuery()
        .taskDefinitionKey(taskId)
        .singleResult()
        .getExecutionId();

  }

  /**
   * @param activityId The BPMN id of the element
   * @return The execution standing on that element, which for a call activity is the one
   *         waiting for the called process
   */
  String executionStandingOn(
      final String activityId) {

    return processEngine
        .getRuntimeService()
        .createExecutionQuery()
        .activityId(activityId)
        .singleResult()
        .getId();

  }

  @Override
  public void close() {

    processEngine.close();

  }

}
