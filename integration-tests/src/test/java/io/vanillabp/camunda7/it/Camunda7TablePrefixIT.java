package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * Two <code>camunda7</code> adapter ids on ONE datasource, kept apart by a table prefix.
 * This is the setup for a migration side by side on a single database.
 * <p>
 * The prefix is what Camunda calls <code>databaseTablePrefix</code>. Camunda does not
 * create prefixed tables. The initializer below applies the own statements of the engine
 * with the prefix, which is what an application has to do. The adapter id says so with
 * <code>database-schema-update: false</code>. An id whose tables are missing does not get
 * here at all. The adapter ends the boot with a message which names them, see
 * {@code Camunda7AdapterBootTest}.
 * <p>
 * What this proves: both engines deploy the workflow module, each into its own set of
 * tables. And each starts workflows which stay in its own engine.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database. Test contexts are cached and live in parallel. The engine of another
    // context on the same H2 database would interfere.
    "spring.datasource.url=jdbc:h2:mem:c7-table-prefix-it;DB_CLOSE_DELAY=-1", "vanillabp.prioritized-adapters=c7,c7p", "vanillabp.adapters.c7p.type=camunda7", "vanillabp.adapters.c7p.name-clash-avoidance=by-adapter", "vanillabp.adapters.c7p.table-prefix=NEW_", "vanillabp.adapters.c7p.database-schema-update=false", "vanillabp.workflow-modules.c7-it.adapters.c7p.resources-location=classpath*:c7-it/processes"
})
@ContextConfiguration(initializers = Camunda7TablePrefixIT.PrepareThePrefixedTables.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done. This IT has a database of its own, and so a context of its
// own. Spring would keep every context until the JVM exits. An engine which outlives its test
// keeps its job executor running against a database which the next classes work on.
@DirtiesContext
public class Camunda7TablePrefixIT {

  private static final String JDBC_URL = "jdbc:h2:mem:c7-table-prefix-it;DB_CLOSE_DELAY=-1";

  private static final String PREFIX = "NEW_";

  private static final String MODULE_ID = "c7-it";

  private static final String BPMN_PROCESS_ID = "TestProcess";

  /**
   * Creates the prefixed tables before any bean is built. At this point in the boot, the
   * Liquibase or Flyway of an application would have done it.
   */
  public static class PrepareThePrefixedTables implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(
        final ConfigurableApplicationContext applicationContext) {

      PrefixedEngineSchema.create(JDBC_URL, PREFIX);

    }

  }

  @Autowired
  @Qualifier("Camunda7_Engine_c7")
  private Camunda7EngineHolder unprefixedEngine;

  @Autowired
  @Qualifier("Camunda7_Engine_c7p")
  private Camunda7EngineHolder prefixedEngine;

  @SuppressWarnings("rawtypes")
  @Autowired
  @Qualifier("Camunda7_ProcessService_c7p")
  private Camunda7ProcessService prefixedProcessService;

  @Autowired
  private AggregateRepository aggregateRepository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private ProcessService<TestAggregate> processService;

  private static long countIn(
      final String table) {

    try (var connection = DriverManager.getConnection(JDBC_URL, "sa", "")) {
      try (var statement = connection.createStatement()) {
        try (var result = statement.executeQuery("select count(*) from "
            + table)) {
          result.next();
          return result.getLong(1);
        }
      }
    } catch (final SQLException e) {
      throw new IllegalStateException(e);
    }

  }

  @Test
  @DisplayName("Two engines on one datasource, each deploying into its own tables")
  public void twoEnginesOnOneDatasource() {

    assertEquals("vanillabp-camunda7-c7", unprefixedEngine.getProcessEngine().getName());
    assertEquals("vanillabp-camunda7-c7p", prefixedEngine.getProcessEngine().getName());

    for (final var engine : new Camunda7EngineHolder[]{
        unprefixedEngine, prefixedEngine
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

    // and the two deployments live in two sets of tables: one database, two engines
    assertTrue(countIn("ACT_RE_PROCDEF") > 0, "the unprefixed engine writes ACT_*");
    assertTrue(countIn(PREFIX
        + "ACT_RE_PROCDEF") > 0, "the prefixed engine writes NEW_ACT_*");

  }

  @Test
  @DisplayName("Starting via the VanillaBP API lands in the first prioritized adapter's engine only")
  public void startLandsInFirstPrioritizedEngine() {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TestAggregate();
      aggregate.setContent("table-prefix-start");
      return processService.startWorkflow(aggregate).getId();
    });
    assertNotNull(aggregateId);

    final var businessKey = String.valueOf(aggregateId);
    // the phase-two outbox creates the instance right after the commit. It may have ended
    // by the time the test looks, and the history answers both cases.
    AwaitPhaseTwo.until(
        () -> unprefixedEngine
            .getHistoryService()
            .createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey)
            .tenantIdIn(MODULE_ID)
            .count() == 1,
        "the instance to appear in the first prioritized adapter's engine");
    assertEquals(
        0,
        prefixedEngine
            .getHistoryService()
            .createHistoricProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey)
            .tenantIdIn(MODULE_ID)
            .count(),
        "the prefixed engine must not receive the instance");

  }

  @Test
  @DisplayName("The prefixed engine runs workflows of its own")
  @SuppressWarnings("unchecked")
  public void thePrefixedEngineRunsItsOwnWorkflows() {

    // pause the job processing of the prefixed engine. So the started instance does not
    // complete asynchronously while the test asserts the runtime state.
    prefixedEngine.stopWorkflowProcessing(MODULE_ID);

    final var aggregate = new TestAggregate();
    aggregate.setContent("table-prefix-c7p");
    final var saved = aggregateRepository.save(aggregate);
    final var businessKey = String.valueOf(saved.getId());

    PhaseOperations
        .phaseOne(
            prefixedProcessService,
            io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW,
            MODULE_ID,
            BPMN_PROCESS_ID,
            null,
            saved,
            java.util.Map.of());
    PhaseOperations
        .phaseTwo(
            prefixedProcessService,
            io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW,
            MODULE_ID,
            BPMN_PROCESS_ID,
            null,
            saved.getId(),
            java.util.Map.of());

    assertEquals(
        1,
        prefixedEngine
            .getRuntimeService()
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey)
            .tenantIdIn(MODULE_ID)
            .count(),
        "the prefixed engine holds the instance");
    assertEquals(
        0,
        unprefixedEngine
            .getRuntimeService()
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(businessKey)
            .tenantIdIn(MODULE_ID)
            .count(),
        "the other engine of the same datasource knows nothing about it");

    // resume the job processing, because other tests reuse the shared application context
    prefixedEngine.startWorkflowProcessing(MODULE_ID);

  }

}
