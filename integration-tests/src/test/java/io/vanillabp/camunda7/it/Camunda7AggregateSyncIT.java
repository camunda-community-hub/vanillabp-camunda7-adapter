package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Collectors;

import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the EMBEDDED Camunda 7 engine gets to see of a workflow aggregate, asserted
 * against the real engine:
 * <ul>
 * <li>this adapter shares like every other BPMS: an aggregate carrying NO
 * annotation writes every attribute as a process variable, and the engine's expressions
 * read those variables;</li>
 * <li>an aggregate which minimizes ({@code @SyncWithBPMS} on one attribute derives opt-out
 * for the rest) writes exactly what it named - and an expression reading something it did
 * NOT share only works through the MIGRATION FALLBACK of the EL resolver, which will be
 * removed;</li>
 * <li>the demanding case: the gateway right behind a service task branches
 * on what THAT task computed, which means the value has to be a variable by then. The
 * condition also navigates a NESTED shared value, which travels as an object variable.</li>
 * </ul>
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: test contexts are cached and live in parallel - another
    // context's engine (and job executor) on the same H2 database would compete for
    // this test's jobs, and Hibernate's ddl-auto=create resets the aggregate tables
    // (restarting the ID sequences) while Camunda's tables survive
    "spring.datasource.url=jdbc:h2:mem:c7-sync-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, Spring would keep every context until the JVM exits, and an engine outliving its
// test keeps its job executor running against a database the next classes work on
@DirtiesContext
public class Camunda7AggregateSyncIT {

  private static final String MODULE_ID = "c7-it";

  @Autowired
  private RuntimeService runtimeService;

  @Autowired
  private org.camunda.bpm.engine.ProcessEngine processEngine;

  @Autowired
  private SyncTestRepository syncRepository;

  @Autowired
  private SyncTestWorkflowService syncWorkflowService;

  @Autowired
  private TaskTestRepository taskRepository;

  @Autowired
  private TaskTestWorkflowService taskWorkflowService;

  @Autowired
  private DecisionTestRepository decisionRepository;

  @Autowired
  private DecisionTestWorkflowService decisionWorkflowService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  private Map<String, Object> variablesOfWorkflow(
      final Object aggregateId) {

    return variablesOfWorkflow(aggregateId, "SyncProcess");

  }

  private Map<String, Object> variablesOfWorkflow(
      final Object aggregateId,
      final String bpmnProcessId) {

    final var processInstance = runtimeService
        .createProcessInstanceQuery()
        .processDefinitionKey(bpmnProcessId)
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .tenantIdIn(MODULE_ID)
        .singleResult();
    assertNotNull(processInstance, "the workflow has to be active to inspect its variables");
    return runtimeService
        .createVariableInstanceQuery()
        .processInstanceIdIn(processInstance.getProcessInstanceId())
        .list()
        .stream()
        .collect(Collectors.toMap(
            org.camunda.bpm.engine.runtime.VariableInstance::getName,
            variable -> String.valueOf(variable.getValue())));

  }

  @Test
  @DisplayName("An aggregate which minimizes writes what it named - the rest needs the migration fallback")
  public void sharedAttributeBecomesAProcessVariable() {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new SyncTestAggregate();
      aggregate.setCustomerName("ACME");
      aggregate.setSecret("s3cr3t");
      // NOT shared, yet the gateway ${approved} has to evaluate it
      aggregate.setApproved(true);
      return syncWorkflowService.startSyncProcess(aggregate).getId();
    });

    // the gateway condition reads the NOT shared attribute, so the workflow got past it
    // through the migration fallback - the EL resolver still reads the
    // aggregate where the engine has no variable of that name. That fallback will be removed,
    // and the startup check names such expressions while the application boots
    assertTrue(
        waitForTaskId(aggregateId),
        "the workflow has to reach the asynchronous task behind the gateway");

    assertEquals(
        Map.of("customerName", "ACME"),
        variablesOfWorkflow(aggregateId),
        "exactly the shared attribute - the class mode derived from it excludes everything else");

  }

  @Test
  @DisplayName("An aggregate without any annotation shares everything - the default of every adapter")
  public void unannotatedAggregateSharesEverything() {

    // started through VanillaBP (the sync point under test): TaskProcess runs to its end,
    // so the assertion is made against the ENGINE'S HISTORY
    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(true);
      return taskWorkflowService.startTaskProcess(aggregate).getId();
    });

    assertTrue(
        waitFor(() -> taskRepository
            .findById(aggregateId)
            .map(TaskTestAggregate::getResults)
            .filter(results -> results.contains("approved"))
            .isPresent()),
        "TaskProcess has to pass the gateway reading the shared attribute");

    final var historyService = processEngine.getHistoryService();
    final var historicInstance = historyService
        .createHistoricProcessInstanceQuery()
        .processDefinitionKey("TaskProcess")
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .singleResult();
    assertNotNull(historicInstance, "the finished workflow has to be in the history");
    final var variableNames = historyService
        .createHistoricVariableInstanceQuery()
        .processInstanceId(historicInstance.getId())
        .list()
        .stream()
        .map(org.camunda.bpm.engine.history.HistoricVariableInstance::getName)
        .toList();
    // FULL is the default of every adapter, so every attribute of this
    // unannotated aggregate is a variable the model may read
    assertTrue(variableNames.contains("approved"), "shared attributes: "
        + variableNames);
    assertTrue(variableNames.contains("results"), "shared attributes: "
        + variableNames);
    assertTrue(variableNames.contains("id"), "shared attributes: "
        + variableNames);

  }

  @Test
  @DisplayName("The gateway behind a task branches on what THAT task computed")
  public void theGatewayBehindATaskReadsWhatTheTaskComputed() {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new DecisionTestAggregate();
      final var customer = new DecisionTestCustomer();
      customer.setCustomerName("ACME");
      customer.setVip(true);
      aggregate.setCustomer(customer);
      // NOT decided when the workflow starts - the first task decides
      return decisionWorkflowService.startDecisionProcess(aggregate).getId();
    });

    assertTrue(
        waitFor(() -> decisionRepository
            .findById(aggregateId)
            .map(DecisionTestAggregate::getDecisionResult)
            .isPresent()),
        "the workflow has to get past the gateway behind the deciding task");
    assertEquals(
        "decided",
        decisionRepository.findById(aggregateId).orElseThrow().getDecisionResult(),
        "the gateway took the rejecting branch - it did not see what the task computed");

    // the value the task computed IS a process variable now, which is what the gateway
    // read (see decision 1 in the repository's DECISIONS.md)
    final var variables = variablesOfWorkflow(aggregateId, "SyncDecisionProcess");
    assertEquals("true", variables.get("decided"), "variables: "
        + variables);

  }

  @Test
  @DisplayName("A nested shared value travels as an object variable a condition can navigate")
  public void nestedValuesBecomeObjectVariables() {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new DecisionTestAggregate();
      final var customer = new DecisionTestCustomer();
      customer.setCustomerName("ACME");
      customer.setVip(true);
      aggregate.setCustomer(customer);
      return decisionWorkflowService.startDecisionProcess(aggregate).getId();
    });

    assertTrue(
        waitFor(() -> decisionRepository
            .findById(aggregateId)
            .map(DecisionTestAggregate::getTaskId)
            .isPresent()),
        "the workflow has to park at the task behind the gateway");

    // the condition of that gateway reads '${decided and customer.vip}', so the dot
    // notation navigated the nested value - which only works because it is an OBJECT
    // variable the engine deserializes, not a text
    final var processInstance = runtimeService
        .createProcessInstanceQuery()
        .processDefinitionKey("SyncDecisionProcess")
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .tenantIdIn(MODULE_ID)
        .singleResult();
    assertNotNull(processInstance);
    final var customerVariable = runtimeService
        .createVariableInstanceQuery()
        .processInstanceIdIn(processInstance.getProcessInstanceId())
        .variableName("customer")
        .disableCustomObjectDeserialization()
        .singleResult();
    assertNotNull(customerVariable, "the nested value has to be a variable");
    assertEquals("object", customerVariable.getTypeName(), "a nested value is an object variable");

  }

  private boolean waitForTaskId(
      final Long aggregateId) {

    return waitFor(() -> syncRepository
        .findById(aggregateId)
        .map(SyncTestAggregate::getTaskId)
        .orElse(null) != null);

  }

  private boolean waitFor(
      final java.util.function.Supplier<Boolean> condition) {

    final var deadline = System.currentTimeMillis() + 30000;
    while (System.currentTimeMillis() < deadline) {
      if (Boolean.TRUE.equals(condition.get())) {
        return true;
      }
      try {
        Thread.sleep(100);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;

  }

}
