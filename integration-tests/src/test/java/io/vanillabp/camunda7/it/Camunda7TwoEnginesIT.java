package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * Two configured {@code camunda7} adapter ids side by side (the engine-side-by-side
 * migration scenario): {@code c7} shares the application's
 * datasource, {@code c7b} runs on an application-provided NAMED datasource bean
 * ({@code vanillabp.adapters.c7b.data-source-name} - setting up datasources is the
 * application's concern, VanillaBP never builds its own pool).
 * <ul>
 *   <li>two engines exist, named after the adapter ids, and the workflow module's
 *       BPMN is deployed to BOTH (each engine has its own schema);</li>
 *   <li>starting a workflow via the VanillaBP API lands in the FIRST prioritized
 *       adapter's engine only;</li>
 *   <li>the separate-datasource id starts workflows via the two-phase pattern:
 *       phase one does nothing against the engine, phase two creates the instance
 *       idempotently (at-least-once dispatch).</li>
 * </ul>
 */
@SpringBootTest(classes = {
    TestApplication.class, Camunda7TwoEnginesIT.NamedDataSourceConfiguration.class
}, properties = {
    // own database: test contexts are cached and live in parallel - another
    // context's engine on the same H2 database would interfere
    "spring.datasource.url=jdbc:h2:mem:c7-two-engines-it;DB_CLOSE_DELAY=-1", "vanillabp.prioritized-adapters=c7,c7b", "vanillabp.adapters.c7b.type=camunda7", "vanillabp.adapters.c7b.name-clash-avoidance=by-adapter", "vanillabp.adapters.c7b.data-source-name=c7bDataSource", "vanillabp.workflow-modules.c7-it.adapters.c7b.resources-location=classpath*:c7-it/processes"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, Spring would keep every context until the JVM exits, and an engine outliving its
// test keeps its job executor running against a database the next classes work on
@DirtiesContext
public class Camunda7TwoEnginesIT {

  /**
   * The application-provided datasource bean the {@code c7b} engine runs on.
   * {@code defaultCandidate = false} keeps it out of by-type injection and lets
   * Spring Boot's default-datasource auto-configuration stay active (the standard
   * pattern for additional application datasources).
   */
  @org.springframework.boot.test.context.TestConfiguration
  public static class NamedDataSourceConfiguration {

    @org.springframework.context.annotation.Bean(defaultCandidate = false)
    public javax.sql.DataSource c7bDataSource() {

      return new org.springframework.jdbc.datasource.SimpleDriverDataSource(
          new org.h2.Driver(), "jdbc:h2:mem:c7b-engine;DB_CLOSE_DELAY=-1");

    }

  }

  private static final String MODULE_ID = "c7-it";

  private static final String BPMN_PROCESS_ID = "TestProcess";

  @Autowired
  @Qualifier("Camunda7_Engine_c7")
  private Camunda7EngineHolder sharedDataSourceEngine;

  @Autowired
  @Qualifier("Camunda7_Engine_c7b")
  private Camunda7EngineHolder separateDataSourceEngine;

  @SuppressWarnings("rawtypes")
  @Autowired
  @Qualifier("Camunda7_ProcessService_c7b")
  private Camunda7ProcessService separateDataSourceProcessService;

  @Autowired
  private AggregateRepository aggregateRepository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private ProcessService<TestAggregate> processService;

  @Test
  @DisplayName("Two engines exist (one per adapter id) and both received the module's deployment")
  public void twoEnginesWithDeployments() {

    assertEquals("vanillabp-camunda7-c7", sharedDataSourceEngine.getProcessEngine().getName());
    assertEquals("vanillabp-camunda7-c7b", separateDataSourceEngine.getProcessEngine().getName());
    assertFalse(sharedDataSourceEngine.usesSeparateDataSource());
    assertTrue(separateDataSourceEngine.usesSeparateDataSource());

    // the deployment pipeline deployed the module's BPMN to EVERY prioritized
    // adapter - each engine holds it in its own schema
    for (final var engine : new Camunda7EngineHolder[]{
        sharedDataSourceEngine, separateDataSourceEngine
    }) {
      assertEquals(
          1,
          engine
              .getRepositoryService()
              .createProcessDefinitionQuery()
              .processDefinitionKey(BPMN_PROCESS_ID)
              .tenantIdIn(MODULE_ID)
              .count(),
          "process definition deployed to engine '%s'".formatted(engine.getAdapterId()));
    }

  }

  @Test
  @DisplayName("Starting via the VanillaBP API lands in the first prioritized adapter's engine only")
  public void startLandsInFirstPrioritizedEngine() {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TestAggregate();
      aggregate.setContent("two-engines-start");
      return processService.startWorkflow(aggregate).getId();
    });
    assertNotNull(aggregateId);

    final var businessKey = String.valueOf(aggregateId);
    // the instance is created by the phase-two outbox right after the commit, and it
    // may have ended by the time the test looks - the history answers both
    AwaitPhaseTwo.until(
        () -> sharedDataSourceEngine
            .getHistoryService()
            .createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey)
            .tenantIdIn(MODULE_ID)
            .count() == 1,
        "the instance to appear in the first prioritized adapter's engine");
    assertEquals(
        0,
        separateDataSourceEngine
            .getHistoryService()
            .createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey)
            .tenantIdIn(MODULE_ID)
            .count(),
        "the second engine must not receive the instance");

  }

  @Test
  @DisplayName("A separate-datasource id starts two-phase: phase one no-op, phase two idempotent")
  @SuppressWarnings("unchecked")
  public void separateDataSourceIdStartsTwoPhase() {

    // pause job processing of the c7b engine so the started instance does not
    // complete asynchronously while the idempotency of phase two is asserted
    separateDataSourceEngine.stopWorkflowProcessing(MODULE_ID);

    final var aggregate = new TestAggregate();
    aggregate.setContent("two-phase-c7b");
    final var saved = aggregateRepository.save(aggregate);
    final var businessKey = String.valueOf(saved.getId());

    // phase one must not create the instance (the engine's own transaction would
    // commit it even if the caller's transaction rolled back afterwards)
    PhaseOperations
        .phaseOne(
            separateDataSourceProcessService,
            io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW,
            MODULE_ID,
            BPMN_PROCESS_ID,
            null,
            saved,
            java.util.Map.of());
    assertEquals(
        0,
        separateDataSourceEngine
            .getRuntimeService()
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey)
            .tenantIdIn(MODULE_ID)
            .count(),
        "phase one must not touch the engine");

    // phase two (after commit, dispatched via the outbox) creates the instance...
    PhaseOperations
        .phaseTwo(
            separateDataSourceProcessService,
            io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW,
            MODULE_ID,
            BPMN_PROCESS_ID,
            null,
            saved.getId(),
            java.util.Map.of());
    assertEquals(
        1,
        separateDataSourceEngine
            .getRuntimeService()
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey)
            .tenantIdIn(MODULE_ID)
            .count());

    // ...and a redelivered phase two (at-least-once) is skipped
    PhaseOperations
        .phaseTwo(
            separateDataSourceProcessService,
            io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW,
            MODULE_ID,
            BPMN_PROCESS_ID,
            null,
            saved.getId(),
            java.util.Map.of());
    assertEquals(
        1,
        separateDataSourceEngine
            .getRuntimeService()
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey)
            .tenantIdIn(MODULE_ID)
            .count(),
        "a redelivered phase-two start must be skipped (idempotency)");

    // resume job processing (the shared application context is reused by other tests)
    separateDataSourceEngine.startWorkflowProcessing(MODULE_ID);

  }

}
