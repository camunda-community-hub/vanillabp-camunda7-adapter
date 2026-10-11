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
 * Two configured {@code camunda7} adapter ids side by side, the scenario of a migration
 * with two engines next to each other. {@code c7} shares the datasource of the application.
 * {@code c7b} runs on a NAMED datasource bean which the application provides
 * ({@code vanillabp.adapters.c7b.data-source-name}). Setting up datasources is the concern of the
 * application, and VanillaBP never builds its own pool.
 * <ul>
 *   <li>Two engines exist, named after the adapter ids. The BPMN of the workflow module is
 *       deployed to BOTH, and each engine has its own schema.</li>
 *   <li>A workflow started through the VanillaBP API lands only in the engine of the
 *       FIRST prioritized adapter.</li>
 *   <li>The id with the separate datasource starts workflows in two phases. Phase one
 *       does nothing against the engine. Phase two creates the instance, and doing it
 *       twice has the same effect as doing it once (the dispatch is at-least-once).</li>
 * </ul>
 */
@SpringBootTest(classes = {
    TestApplication.class, Camunda7TwoEnginesIT.NamedDataSourceConfiguration.class
}, properties = {
    // own database. Test contexts are cached and live in parallel. The engine of another
    // context on the same H2 database would interfere.
    "spring.datasource.url=jdbc:h2:mem:c7-two-engines-it;DB_CLOSE_DELAY=-1", "vanillabp.prioritized-adapters=c7,c7b", "vanillabp.adapters.c7b.type=camunda7", "vanillabp.adapters.c7b.name-clash-avoidance=by-adapter", "vanillabp.adapters.c7b.data-source-name=c7bDataSource", "vanillabp.workflow-modules.c7-it.adapters.c7b.resources-location=classpath*:c7-it/processes"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done. This IT has a database of its own, and so a context of its
// own. Spring would keep every context until the JVM exits. An engine which outlives its test
// keeps its job executor running against a database which the next classes work on.
@DirtiesContext
public class Camunda7TwoEnginesIT {

  /**
   * The datasource bean which the application provides, and which the {@code c7b} engine
   * runs on. {@code defaultCandidate = false} keeps it out of injection by type. It also
   * lets the default-datasource auto-configuration of Spring Boot stay active. This is the
   * standard pattern for additional datasources of an application.
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

    // the deployment pipeline deployed the BPMN of the module to EVERY prioritized adapter.
    // Each engine holds it in its own schema.
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
    // the phase-two outbox creates the instance right after the commit. It may have ended
    // by the time the test looks, and the history answers both cases.
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

    // pause the job processing of the c7b engine. So the started instance does not complete
    // asynchronously while the test asserts that phase two is idempotent.
    separateDataSourceEngine.stopWorkflowProcessing(MODULE_ID);

    final var aggregate = new TestAggregate();
    aggregate.setContent("two-phase-c7b");
    final var saved = aggregateRepository.save(aggregate);
    final var businessKey = String.valueOf(saved.getId());

    // phase one must not create the instance. The own transaction of the engine would
    // commit it, even if the transaction of the caller rolled back afterwards.
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

    // phase two runs after the commit, dispatched through the outbox. It creates the
    // instance...
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

    // ...and a phase two delivered again (at-least-once) is skipped
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

    // resume the job processing, because other tests reuse the shared application context
    separateDataSourceEngine.startWorkflowProcessing(MODULE_ID);

  }

}
