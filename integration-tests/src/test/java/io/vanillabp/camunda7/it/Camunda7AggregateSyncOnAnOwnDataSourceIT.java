package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an engine on a datasource of its own gets to see of a workflow aggregate, asserted
 * against the real engine.
 * <p>
 * On such an engine, VanillaBP runs the handler in a transaction of its own. That
 * transaction has committed when the handler returns. The job of the engine runs on the
 * other datasource, so the thread has no transaction of the application any more. The
 * values the handler computed are read in a new transaction then, and they still have to
 * become process variables before the engine goes on. The gateway right behind the task
 * branches on them.
 * <p>
 * {@link Camunda7AggregateSyncIT} asserts the same for an engine which shares the
 * datasource of the application.
 */
@SpringBootTest(classes = {
    TestApplication.class, Camunda7AggregateSyncOnAnOwnDataSourceIT.NamedDataSourceConfiguration.class
}, properties = {
    // a database of its own. Test contexts are cached and live in parallel, and an engine
    // of another context would poll the jobs of this one.
    "spring.datasource.url=jdbc:h2:mem:c7-sync-own-datasource-it;DB_CLOSE_DELAY=-1",
    // new workflows start in c7b, the engine on its own datasource
    "vanillabp.prioritized-adapters=c7b,c7", "vanillabp.adapters.c7b.type=camunda7", "vanillabp.adapters.c7b.name-clash-avoidance=by-adapter", "vanillabp.adapters.c7b.data-source-name=c7bDataSource", "vanillabp.workflow-modules.c7-it.adapters.c7b.resources-location=classpath*:c7-it/processes"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done. This IT has a database of its own, and so a context of its
// own. Spring would keep every context until the JVM exits. An engine which outlives its test
// keeps its job executor running against a database which the next classes work on.
@DirtiesContext
public class Camunda7AggregateSyncOnAnOwnDataSourceIT {

  /**
   * The datasource bean which the application provides, and which the {@code c7b} engine
   * runs on. {@code defaultCandidate = false} keeps it out of injection by type.
   */
  @org.springframework.boot.test.context.TestConfiguration
  public static class NamedDataSourceConfiguration {

    @org.springframework.context.annotation.Bean(defaultCandidate = false)
    public javax.sql.DataSource c7bDataSource() {

      return new org.springframework.jdbc.datasource.SimpleDriverDataSource(
          new org.h2.Driver(), "jdbc:h2:mem:c7b-sync-own-datasource;DB_CLOSE_DELAY=-1");

    }

  }

  private static final String MODULE_ID = "c7-it";

  @Autowired
  @Qualifier("Camunda7_Engine_c7b")
  private Camunda7EngineHolder separateDataSourceEngine;

  @Autowired
  private DecisionTestRepository decisionRepository;

  @Autowired
  private DecisionTestWorkflowService decisionWorkflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("On an own datasource the gateway behind a task branches on what THAT task computed")
  public void theGatewayBehindATaskReadsWhatTheTaskComputed() {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new DecisionTestAggregate();
      final var customer = new DecisionTestCustomer();
      customer.setCustomerName("ACME");
      customer.setVip(true);
      aggregate.setCustomer(customer);
      // NOT decided when the workflow starts. The first task decides.
      return decisionWorkflowService.startDecisionProcess(aggregate).getId();
    });

    final var decisionResult = AwaitPhaseTwo
        .untilAvailable(
            () -> decisionRepository
                .findById(aggregateId)
                .map(DecisionTestAggregate::getDecisionResult)
                .orElse(null),
            "the workflow to get past the gateway behind the deciding task");
    assertEquals(
        "decided",
        decisionResult,
        "the gateway took the rejecting branch - it did not see what the task computed");

    final var variables = variablesOfWorkflow(aggregateId);
    assertEquals("true", variables.get("decided"), "variables: "
        + variables);

  }

  private Map<String, Object> variablesOfWorkflow(
      final Object aggregateId) {

    final var runtimeService = separateDataSourceEngine.getRuntimeService();
    final var processInstance = runtimeService
        .createProcessInstanceQuery()
        .processDefinitionKey("SyncDecisionProcess")
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .tenantIdIn(MODULE_ID)
        .singleResult();
    assertNotNull(
        processInstance,
        "the workflow has to be active in the engine on its own datasource");
    return runtimeService
        .createVariableInstanceQuery()
        .processInstanceIdIn(processInstance.getProcessInstanceId())
        .list()
        .stream()
        .collect(Collectors.toMap(
            org.camunda.bpm.engine.runtime.VariableInstance::getName,
            variable -> String.valueOf(variable.getValue())));

  }

}
