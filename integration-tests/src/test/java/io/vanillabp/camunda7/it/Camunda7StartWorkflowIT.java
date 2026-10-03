package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.delivery.TaskDeliveryLogReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * Integration test of the Camunda 7 adapter against a <b>real embedded engine on H2</b>
 * (shared data source and transaction manager with the JPA aggregate persistence).
 * <p>
 * It proves the three properties of the C7 start:
 * <ol>
 *   <li>BPMN resources are deployed with the workflow module ID as the Camunda tenant ID,</li>
 *   <li>starting a workflow creates a process instance whose business key equals the
 *       aggregate ID - through the adapter's own method within the transaction, through
 *       the VanillaBP user API right after the commit, and</li>
 *   <li>rolling back the surrounding transaction removes BOTH the aggregate and the process
 *       instance (the embedded-engine guarantee).</li>
 * </ol>
 * The start is exercised both directly via the adapter's
 * {@link Camunda7ProcessService#startProcessInstance(String, String, Object)} and
 * end-to-end via the VanillaBP user API {@link ProcessService#startWorkflow(Object)}.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // a database of its own: the phase-two outbox of a test class Spring keeps
    // cached would otherwise dispatch the entries of the next one
    "spring.datasource.url=jdbc:h2:mem:c7-start-workflow-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7StartWorkflowIT {

  private static final String MODULE_ID = "c7-it";

  private static final String BPMN_PROCESS_ID = "TestProcess";

  @Autowired
  private RepositoryService repositoryService;

  @Autowired
  private RuntimeService runtimeService;

  @Autowired
  private org.camunda.bpm.engine.ProcessEngine processEngine;

  @SuppressWarnings("rawtypes")
  @Autowired
  private Camunda7ProcessService camunda7ProcessService;

  @Autowired
  private AggregateRepository aggregateRepository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private ProcessService<TestAggregate> processService;

  @Autowired
  private DataSource applicationDataSource;

  @Test
  @DisplayName("BPMN resources are deployed with the workflow module ID as the Camunda tenant ID")
  public void deploymentExistsForModuleTenant() {

    final var count = repositoryService
        .createProcessDefinitionQuery()
        .processDefinitionKey(BPMN_PROCESS_ID)
        .tenantIdIn(MODULE_ID)
        .count();

    assertEquals(1, count, "exactly one process definition deployed for tenant = module id");

  }

  @Test
  @DisplayName("Starting inside a transaction creates a process instance with business key = aggregate id")
  public void startInsideTransactionCreatesInstanceWithBusinessKey() {

    final var aggregateId = transactionTemplate.execute(status -> {

      final var aggregate = new TestAggregate();
      aggregate.setContent("start-test");
      final var saved = aggregateRepository.save(aggregate);

      camunda7ProcessService.startProcessInstance(MODULE_ID, BPMN_PROCESS_ID, saved.getId());

      // inside the same transaction the instance exists with the aggregate id as business key
      final var instance = runtimeService
          .createProcessInstanceQuery()
          .processInstanceBusinessKey(String.valueOf(saved.getId()))
          .tenantIdIn(MODULE_ID)
          .singleResult();
      assertNotNull(instance, "process instance exists within the starting transaction");
      assertEquals(String.valueOf(saved.getId()), instance.getBusinessKey());

      return saved.getId();

    });

    assertNotNull(aggregateId);

  }

  @Test
  @DisplayName("The start reports the tenant of this mode and nothing about a prefix")
  public void theStartReportsTheTenantOfThisMode(
      final CapturedOutput output) {

    final var alreadyLogged = output.getAll().length();

    final var aggregateId = transactionTemplate.execute(status -> {

      final var aggregate = new TestAggregate();
      aggregate.setContent("reported-start");
      final var saved = aggregateRepository.save(aggregate);

      camunda7ProcessService.startProcessInstance(MODULE_ID, BPMN_PROCESS_ID, saved.getId());

      return saved.getId();

    });

    final var reported = output.getAll().substring(alreadyLogged);
    assertTrue(
        reported
            .contains(
                "started workflow '%s' of workflow module '%s' (tenant '%s', business key '%s')"
                    .formatted(BPMN_PROCESS_ID, MODULE_ID, MODULE_ID, aggregateId)),
        () -> "'by-adapter' deploys into a tenant named after the workflow module, so the start has "
            + "to name that tenant and no second process id: "
            + reported);

  }

  @Test
  @DisplayName("Rolling back the transaction removes both the aggregate and the process instance")
  public void rollbackRemovesAggregateAndProcessInstance() {

    final var aggregateIdHolder = new AtomicReference<Long>();

    final var exception = assertThrows(
        RuntimeException.class,
        () -> transactionTemplate.execute(status -> {

          final var aggregate = new TestAggregate();
          aggregate.setContent("rollback-test");
          final var saved = aggregateRepository.save(aggregate);
          aggregateIdHolder.set(saved.getId());

          camunda7ProcessService.startProcessInstance(MODULE_ID, BPMN_PROCESS_ID, saved.getId());

          // the instance is visible inside the transaction before rolling back...
          assertEquals(
              1,
              runtimeService
                  .createProcessInstanceQuery()
                  .processInstanceBusinessKey(String.valueOf(saved.getId()))
                  .count());

          throw new RuntimeException("trigger rollback");

        }));
    assertEquals("trigger rollback", exception.getMessage());

    final var aggregateId = aggregateIdHolder.get();
    assertNotNull(aggregateId);

    // ...and after the rollback both the aggregate and the process instance are gone
    assertFalse(
        aggregateRepository.findById(aggregateId).isPresent(),
        "aggregate rolled back with the transaction");
    assertEquals(
        0,
        runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(String.valueOf(aggregateId))
            .count(),
        "process instance rolled back with the transaction");

  }

  /**
   * The canonical end-to-end path through the VanillaBP user API: starting a workflow via
   * {@link ProcessService#startWorkflow(Object)} schedules the instance, which is created
   * right after the transaction committed, and rolling the transaction back
   * removes the aggregate and lets no instance be created at all.
   */
  @Test
  @DisplayName("processService.startWorkflow creates the instance after the commit (nothing on rollback)")
  public void startWorkflowViaProcessServiceHappensAfterTheCommit() {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TestAggregate();
      aggregate.setContent("via-process-service");
      final var saved = processService.startWorkflow(aggregate);
      assertEquals(
          0,
          runtimeService
              .createProcessInstanceQuery()
              .processInstanceBusinessKey(String.valueOf(saved.getId()))
              .tenantIdIn(MODULE_ID)
              .count(),
          "the process instance must not exist before the commit");
      return saved.getId();
    });

    assertNotNull(aggregateId);

    // after the commit the outbox creates it, with the aggregate id as business key
    // asked against the HISTORY: the instance may well have ended by then
    AwaitPhaseTwo.until(
        () -> processEngine
            .getHistoryService()
            .createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(String.valueOf(aggregateId))
            .tenantIdIn(MODULE_ID)
            .count() == 1,
        "the process instance of aggregate "
            + aggregateId
            + " was never created");

    // the start left the id of its instance behind, so a later operation on this workflow
    // need not ask every configured BPMS which of them holds it
    final var instanceId = processEngine
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .tenantIdIn(MODULE_ID)
        .singleResult()
        .getId();
    final var deliveryLog = TaskDeliveryLogReader.of(applicationDataSource);
    final var startsOfTheAggregate = deliveryLog.workflowStartsOfAggregate(String.valueOf(aggregateId));
    assertEquals(1, startsOfTheAggregate.size(), () -> "one row about the start: "
        + startsOfTheAggregate);
    assertEquals(instanceId, startsOfTheAggregate.get(0).workflowId());
    assertEquals(BPMN_PROCESS_ID, startsOfTheAggregate.get(0).bpmnProcessId());

    // rolled-back start removes both the aggregate and the process instance
    final var rollbackIdHolder = new AtomicReference<Long>();
    final var exception = assertThrows(
        RuntimeException.class,
        () -> transactionTemplate.execute(status -> {
          final var aggregate = new TestAggregate();
          aggregate.setContent("via-process-service-rollback");
          final var saved = processService.startWorkflow(aggregate);
          rollbackIdHolder.set(saved.getId());
          throw new RuntimeException("trigger rollback");
        }));
    assertEquals("trigger rollback", exception.getMessage());

    final var rolledBackId = rollbackIdHolder.get();
    assertNotNull(rolledBackId);
    assertFalse(
        aggregateRepository.findById(rolledBackId).isPresent(),
        "aggregate rolled back with the transaction");
    assertEquals(
        0,
        processEngine
            .getHistoryService()
            .createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(String.valueOf(rolledBackId))
            .count(),
        "no process instance was ever created for the rolled-back start");
    assertTrue(
        deliveryLog.workflowStartsOfAggregate(String.valueOf(rolledBackId)).isEmpty(),
        "a start which never happened leaves no row behind");

  }

  /**
   * Phase two of a start names the instance it created, and a start which finds its workflow
   * running already names nothing. The engine reports the same start through the listener on
   * the start event as well, and both write the same row, so the row alone cannot show that
   * phase two reported anything. What phase two reported is therefore listened to directly.
   */
  @Test
  @DisplayName("Phase two of a start reports the process instance it created, and a repeated one reports nothing")
  @SuppressWarnings("unchecked")
  public void phaseTwoOfAStartReportsTheInstanceItCreated() {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TestAggregate();
      aggregate.setContent("reported-instance");
      return aggregateRepository.save(aggregate).getId();
    });

    final List<String> reported = new ArrayList<>();
    final var instanceId = transactionTemplate.execute(status -> {
      PhaseOperations
          .phaseTwoOfAStart(
              camunda7ProcessService, PhaseOperation.START_WORKFLOW, MODULE_ID, BPMN_PROCESS_ID, aggregateId,
              Map.of(), reported::add);
      // the job of the first service task waits for the commit, so the workflow still runs
      // here and the second dispatch finds it
      PhaseOperations
          .phaseTwoOfAStart(
              camunda7ProcessService, PhaseOperation.START_WORKFLOW, MODULE_ID, BPMN_PROCESS_ID, aggregateId,
              Map.of(), reported::add);
      return runtimeService
          .createProcessInstanceQuery()
          .processInstanceBusinessKey(String.valueOf(aggregateId))
          .tenantIdIn(MODULE_ID)
          .singleResult()
          .getProcessInstanceId();
    });

    assertEquals(List.of(instanceId), reported);

  }

}
