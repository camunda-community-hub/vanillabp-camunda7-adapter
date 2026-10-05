package io.vanillabp.camunda7.quarkus;

import java.util.List;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.api.Camunda7EngineFacts;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * What this adapter hands an extension on Quarkus: one {@link Camunda7EngineFacts} per
 * configured adapter id, answering what the adapter itself acts on.
 * <p>
 * The pin this class was written for is the transaction answer. An extension of version 1
 * assumed that an engine on Quarkus always joins the application's transaction, because the
 * engine is built on the container's transaction manager. That is true of the transaction
 * manager and false of the commit: an adapter id given a data source of its own writes to a
 * resource the application's persistence does not take part in, so the two commit separately
 * and this adapter treats such an engine as separate everywhere else. The answer is the
 * adapter's, and here it is measured on the platform where the assumption was made.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7EngineFactsOnQuarkusTest {

  private static final String MODULE_ID = "c7-test";

  private static final String BPMN_PROCESS_ID = "TestProcess";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("engine-facts/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  List<Camunda7EngineFacts> engineFacts;

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  private Camunda7EngineFacts factsOf(
      final String adapterId) {

    return engineFacts
        .stream()
        .filter(facts -> adapterId.equals(facts.adapterId()))
        .findFirst()
        .orElseThrow();

  }

  @Test
  @DisplayName("One Camunda7EngineFacts exists per configured adapter id")
  public void oneFactsPerConfiguredAdapterId() {

    Assertions
        .assertEquals(
            List.of("c7", "c7b"),
            engineFacts
                .stream()
                .map(Camunda7EngineFacts::adapterId)
                .sorted()
                .toList());

  }

  @Test
  @DisplayName("An engine on a named data source does not join the application transaction")
  public void anEngineOnANamedDataSourceDoesNotJoin() {

    Assertions
        .assertTrue(
            factsOf("c7").joinsTheApplicationTransaction(),
            "the id on the application's default data source commits with it");
    Assertions
        .assertFalse(
            factsOf("c7b").joinsTheApplicationTransaction(),
            "the id on 'quarkus.datasource.c7b' writes to a resource the application does not join, "
                + "whatever the transaction manager enlists");

    // and it is the adapter's own answer, not a second reading: the same engine says it
    // delivers repeatable tasks for exactly the same reason
    Assertions
        .assertTrue(
            engineRegistry
                .engineFor("c7b")
                .usesSeparateDataSource());
    Assertions
        .assertFalse(
            engineRegistry
                .engineFor("c7")
                .usesSeparateDataSource());

  }

  @Test
  @DisplayName("The tenant is the one the deployment used")
  public void theTenantIsTheOneTheDeploymentUsed() {

    Assertions.assertEquals(MODULE_ID, factsOf("c7").tenantIdOf(MODULE_ID));
    Assertions.assertEquals(MODULE_ID, factsOf("c7b").tenantIdOf(MODULE_ID));

  }

  @Test
  @DisplayName("The way back from the engine's identifiers is answered once the pipeline ran")
  public void theWayBackIsAnsweredOnceThePipelineRan() {

    final var facts = factsOf("c7");
    final var resolved = facts
        .taskRegistry()
        .resolve(MODULE_ID, BPMN_PROCESS_ID);

    Assertions.assertTrue(resolved.isPresent(), "the deployment pipeline wired this process");
    Assertions.assertEquals(MODULE_ID, resolved.get().workflowModuleId());
    Assertions.assertEquals(BPMN_PROCESS_ID, resolved.get().bpmnProcessId());
    Assertions
        .assertTrue(
            facts
                .taskRegistry()
                .resolve(MODULE_ID, "AProcessNobodyDeployed")
                .isEmpty(),
            "a definition of another application on the same database is not this application's");

  }

  @Test
  @DisplayName("The deployed version is answered from the adapter's cache")
  public void theDeployedVersionIsAnsweredFromTheCache() {

    final var definitionId = engineRegistry
        .engineFor("c7")
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(BPMN_PROCESS_ID)
        .tenantIdIn(MODULE_ID)
        .singleResult()
        .getId();

    final var deployed = factsOf("c7").definitionOf(definitionId);

    Assertions.assertNotNull(deployed, "the adapter deployed this definition, so it knows its version");
    Assertions.assertEquals("1", deployed.version());
    // how an operator reads it is written by the platform, so every BPMS spells it alike
    Assertions.assertEquals("1", deployed.displayVersion());
    Assertions
        .assertNull(
            factsOf("c7").definitionOf("a-definition-which-never-existed"),
            "a definition the engine does not know has no version rather than a failure");

  }

}
