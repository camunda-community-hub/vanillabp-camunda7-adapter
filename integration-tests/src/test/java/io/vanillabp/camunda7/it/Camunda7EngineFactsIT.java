package io.vanillabp.camunda7.it;

import org.camunda.bpm.engine.RepositoryService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import io.vanillabp.camunda7.api.Camunda7EngineFacts;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What this adapter hands an extension on Spring Boot. It is measured after the deployment
 * pipeline really ran against a real embedded engine. The facts are the way back from the
 * identifiers of the engine to the workflow module and the plain BPMN process id. And they
 * are the deployed version behind a definition id, which the adapter answers out of its own
 * cache. This test checks the answers, not whether the cache or the engine gave them.
 * <p>
 * The Quarkus counterpart is {@code Camunda7EngineFactsOnQuarkusTest}. Both platforms run
 * it. An answer which only the neutral core gets right says nothing about whether a
 * platform ever builds the object which carries it.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // a database of its own. Otherwise an engine of a context which Spring keeps cached
    // would work on the database of the next class.
    "spring.datasource.url=jdbc:h2:mem:c7-engine-facts-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@DirtiesContext
public class Camunda7EngineFactsIT {

  private static final String MODULE_ID = "c7-it";

  private static final String BPMN_PROCESS_ID = "TestProcess";

  @Autowired
  private Camunda7EngineFacts engineFacts;

  @Autowired
  private RepositoryService repositoryService;

  @Test
  @DisplayName("The facts belong to the configured adapter id and its engine joins the transaction")
  public void theFactsBelongToTheConfiguredAdapterId() {

    Assertions.assertEquals("c7", engineFacts.adapterId());
    Assertions
        .assertTrue(
            engineFacts.joinsTheApplicationTransaction(),
            "this engine runs on the application's own data source");
    Assertions.assertEquals(MODULE_ID, engineFacts.tenantIdOf(MODULE_ID));

  }

  @Test
  @DisplayName("The way back from the engine's identifiers is answered once the pipeline ran")
  public void theWayBackIsAnsweredOnceThePipelineRan() {

    final var resolved = engineFacts
        .taskRegistry()
        .resolve(MODULE_ID, BPMN_PROCESS_ID);

    Assertions.assertTrue(resolved.isPresent(), "the deployment pipeline wired this process");
    Assertions.assertEquals(MODULE_ID, resolved.get().workflowModuleId());
    Assertions.assertEquals(BPMN_PROCESS_ID, resolved.get().bpmnProcessId());
    Assertions
        .assertTrue(
            engineFacts
                .taskRegistry()
                .resolve(MODULE_ID, "AProcessNobodyDeployed")
                .isEmpty(),
            "a definition of another application on the same database is not this application's");

  }

  @Test
  @DisplayName("The deployed version, including its tag, comes out of the adapter's cache")
  public void theDeployedVersionComesOutOfTheCache() {

    // what the application deployed while booting. The adapter recorded it, so no query
    // runs for it at all. The test does not count the queries
    final var deployedWhileBooting = engineFacts.definitionOf(latestDefinitionIdOf("VersionedProcess"));

    Assertions.assertNotNull(deployedWhileBooting, "the adapter deployed this definition, so it knows it");
    Assertions.assertEquals("1", deployedWhileBooting.version());
    Assertions.assertNull(deployedWhileBooting.versionTag(), "this model carries no camunda:versionTag");
    Assertions.assertEquals("1", deployedWhileBooting.displayVersion());

    // a version deployed by somebody else, which is what a rolling deployment from another
    // node looks like. The adapter asks the engine once and then answers from memory. Here
    // only the answer is checked
    repositoryService
        .createDeployment()
        .name("c7-it")
        .tenantId(MODULE_ID)
        .addClasspathResource("c7-it/versioned/versioned-process-v2.bpmn")
        .deploy();
    final var tagged = engineFacts.definitionOf(latestDefinitionIdOf("VersionedProcess"));

    Assertions.assertEquals("2", tagged.version());
    Assertions.assertEquals("release-2", tagged.versionTag());
    // the platform writes how an operator reads a version. So a cockpit, a log line and a
    // support tool spell one deployment alike.
    Assertions.assertEquals("release-2:2", tagged.displayVersion());

    Assertions
        .assertNull(
            engineFacts.definitionOf("a-definition-which-never-existed"),
            "a definition the engine does not know has no version rather than a failure");

  }

  private String latestDefinitionIdOf(
      final String bpmnProcessId) {

    return repositoryService
        .createProcessDefinitionQuery()
        .processDefinitionKey(bpmnProcessId)
        .tenantIdIn(MODULE_ID)
        .latestVersion()
        .singleResult()
        .getId();

  }

}
