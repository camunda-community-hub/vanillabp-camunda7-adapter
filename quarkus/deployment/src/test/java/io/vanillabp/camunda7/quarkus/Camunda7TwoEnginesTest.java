package io.vanillabp.camunda7.quarkus;

import java.util.List;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * Two configured {@code camunda7} adapter ids side by side on Quarkus (the
 * engine-side-by-side migration scenario): {@code c7} runs on the application's
 * default Agroal datasource, {@code c7b} on the NAMED datasource declared under
 * <code>quarkus.datasource.c7b.*</code>
 * (<code>vanillabp.adapters.c7b.data-source-name</code>).
 * <ul>
 *   <li>two engines exist, named after the adapter ids, and the workflow module's
 *       BPMN is deployed to BOTH (each engine has its own schema);</li>
 *   <li>the separate-datasource id starts workflows via the two-phase pattern
 *       (mirroring the Spring Boot module's 26e decision): phase one does nothing
 *       against the engine, phase two creates the instance idempotently;</li>
 *   <li>it delivers tasks as well, in a transaction VanillaBP opens because the
 *       engine's own runs where the application's persistence cannot join - which is
 *       why that adapter id, and only that one, says its deliveries may repeat.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7TwoEnginesTest {

  private static final String MODULE_ID = "c7-test";

  private static final String BPMN_PROCESS_ID = "TestProcess";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("two-engines/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  @Inject
  List<MigratableProcessService<Object>> migratableProcessServices;

  @Inject
  EntityManager entityManager;

  @Inject
  UserTransaction userTransaction;

  @Test
  public void twoEnginesWithDeployments() {

    final var sharedEngine = engineRegistry.engineFor("c7");
    final var namedEngine = engineRegistry.engineFor("c7b");
    Assertions.assertEquals("vanillabp-camunda7-c7", sharedEngine.getProcessEngine().getName());
    Assertions.assertEquals("vanillabp-camunda7-c7b", namedEngine.getProcessEngine().getName());
    Assertions.assertFalse(sharedEngine.usesSeparateDataSource());
    Assertions.assertTrue(namedEngine.usesSeparateDataSource());

    // the deployment pipeline deployed the module's BPMN to EVERY prioritized
    // adapter - each engine holds it in its own schema
    for (final var engine : List.of(sharedEngine, namedEngine)) {
      Assertions.assertEquals(
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
  public void onlyTheSeparateDataSourceIdMayRepeatADelivery() {

    Assertions
        .assertFalse(
            processServiceOf("c7").deliversTasksAtLeastOnce(),
            "sharing the application's datasource means delivering in its transaction");
    Assertions
        .assertTrue(
            processServiceOf("c7b").deliversTasksAtLeastOnce(),
            "an own datasource means the aggregate commits before the engine does");

  }

  @Test
  public void separateDataSourceIdDeliversTasks() throws Exception {

    final var namedEngine = engineRegistry.engineFor("c7b");

    userTransaction.begin();
    final var aggregate = new TestAggregate();
    aggregate.setContent("delivery-c7b");
    entityManager.persist(aggregate);
    entityManager.flush();
    final var aggregateId = aggregate.getId();
    userTransaction.commit();

    // started in the engine directly: the VanillaBP API would start in the first
    // prioritized adapter, and this test is about what the SECOND one delivers
    namedEngine
        .getRuntimeService()
        .createProcessInstanceByKey(BPMN_PROCESS_ID)
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregateId))
        .execute();

    // the handler runs in a transaction VanillaBP opens, because the engine's own runs
    // on a datasource the application's persistence cannot join
    final var deadline = System.currentTimeMillis() + 20_000;
    while (!"task-done".equals(contentOf(aggregateId))) {
      Assertions
          .assertTrue(
              System.currentTimeMillis() < deadline,
              "the handler of the own-datasource engine did not run within 20s");
      Thread.sleep(50);
    }

  }

  private String contentOf(
      final Long aggregateId) throws Exception {

    userTransaction.begin();
    try {
      entityManager.clear();
      return entityManager
          .find(TestAggregate.class, aggregateId)
          .getContent();
    } finally {
      userTransaction.commit();
    }

  }

  private MigratableProcessService<Object> processServiceOf(
      final String adapterId) {

    return migratableProcessServices
        .stream()
        .filter(service -> adapterId.equals(service.getAdapterId()))
        .findFirst()
        .orElseThrow();

  }

  @Test
  @SuppressWarnings("unchecked")
  public void separateDataSourceIdStartsTwoPhase() throws Exception {

    final var namedEngine = engineRegistry.engineFor("c7b");
    final var processService = processServiceOf("c7b");

    // an engine on a NAMED datasource cannot join the caller's transaction anyway (the
    // default datasource is already enlisted; the engine's commands would enlist a
    // second non-XA resource), which is one reason more for the two-phase split
    Assertions.assertFalse(
        engineRegistry
            .engineFor("c7")
            .getRuntimeService() == namedEngine.getRuntimeService(),
        "each adapter id has its own engine");

    // pause job processing of the c7b engine so the started instance does not
    // complete asynchronously while the idempotency of phase two is asserted
    namedEngine.stopWorkflowProcessing(MODULE_ID);

    // the aggregate is persisted in the DEFAULT datasource's transaction
    userTransaction.begin();
    final var aggregate = new TestAggregate();
    aggregate.setContent("two-phase-c7b");
    entityManager.persist(aggregate);
    entityManager.flush();
    final var aggregateId = aggregate.getId();
    // phase one must not create the instance (the engine command would commit in
    // its own transaction even if this one rolled back afterwards)
    startWorkflowPhaseOne(processService, aggregate);
    userTransaction.commit();

    final var businessKey = String.valueOf(aggregateId);
    Assertions.assertEquals(
        0,
        countInstances(namedEngine, businessKey),
        "phase one must not touch the engine");

    // phase two (after commit, dispatched via the outbox) creates the instance in
    // its OWN JTA transaction...
    startWorkflowPhaseTwo(processService, aggregateId);
    Assertions.assertEquals(1, countInstances(namedEngine, businessKey));

    // ...and a redelivered phase two (at-least-once) is skipped
    startWorkflowPhaseTwo(processService, aggregateId);
    Assertions.assertEquals(
        1,
        countInstances(namedEngine, businessKey),
        "a redelivered phase-two start must be skipped (idempotency)");

    namedEngine.startWorkflowProcessing(MODULE_ID);

  }

  private long countInstances(
      final io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineHolder engine,
      final String businessKey) {

    return engine
        .getRuntimeService()
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(businessKey)
        .tenantIdIn(MODULE_ID)
        .count();

  }

  /**
   * Runs phase one of a start the way the core does: through the handler the adapter
   * contributes for the operation.
   */
  @SuppressWarnings("unchecked")
  private static void startWorkflowPhaseOne(
      final MigratableProcessService<?> processService,
      final Object workflowAggregate) {

    ((MigratableProcessService<Object>) processService)
        .phaseOperations()
        .get(io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW)
        .phaseOne(
            new io.vanillabp.integration.adapter.spi.PhaseOneRequest<>(
                MODULE_ID, BPMN_PROCESS_ID, null, workflowAggregate, java.util.Map.of()));

  }

  /**
   * Runs phase two of a start the way the outbox dispatch does.
   */
  @SuppressWarnings("unchecked")
  private static void startWorkflowPhaseTwo(
      final MigratableProcessService<?> processService,
      final Object workflowAggregateId) {

    ((MigratableProcessService<Object>) processService)
        .phaseOperations()
        .get(io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW)
        .phaseTwo(
            new io.vanillabp.integration.adapter.spi.PhaseTwoRequest<>(
                MODULE_ID, BPMN_PROCESS_ID, null, workflowAggregateId, java.util.Map.of()));

  }

}
