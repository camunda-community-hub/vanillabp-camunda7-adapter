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
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import jakarta.inject.Inject;

/**
 * Discovery/boot smoke test of the Camunda 7 adapter on Quarkus: the
 * embedded plain engine boots on the shared Agroal datasource (schema created via
 * the JTA schema-ops interceptor - Agroal has no deferred enlistment), the runtime
 * deployment pipeline deploys the workflow module's BPMN with the module id as the
 * Camunda tenant id, and the per-adapter-id beans exist. No Docker, no network.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7AdapterDiscoveryTest {

  private static final String MODULE_ID = "c7-test";

  private static final String BPMN_PROCESS_ID = "TestProcess";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  @SuppressWarnings("CdiUnsatisfiedInjection")
  ProcessService<TestAggregate> processService;

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  // the per-adapter-id shape: ONE List bean with one instance per configured id
  @Inject
  List<MigratableProcessService<Object>> migratableProcessServices;

  @Inject
  List<AdapterDeploymentService<Object, Object>> deploymentServices;

  @Test
  public void adapterIsDiscoveredAndModuleDeployed() {

    Assertions.assertNotNull(processService);

    // one engine per adapter id, named after the id, schema created (querying
    // works), job executor started by the deployment pipeline
    final var engine = engineRegistry.engineFor("c7");
    Assertions.assertEquals("vanillabp-camunda7-c7", engine.getProcessEngine().getName());
    Assertions.assertFalse(engine.usesSeparateDataSource());
    Assertions.assertTrue(
        engine.isJobExecutorActive(),
        "the deployment pipeline started workflow processing at boot");

    // the runtime deployment pipeline deployed the module's BPMN with
    // the workflow module id as the Camunda tenant id
    Assertions.assertEquals(
        1,
        engine
            .getRepositoryService()
            .createProcessDefinitionQuery()
            .processDefinitionKey(BPMN_PROCESS_ID)
            .tenantIdIn(MODULE_ID)
            .count(),
        "the BPMN process is deployed for tenant = workflow module id");

    // per-adapter-id beans: process service and deployment service
    Assertions.assertEquals(1, migratableProcessServices.size());
    Assertions.assertEquals("c7", migratableProcessServices.getFirst().getAdapterId());
    Assertions.assertEquals(1, deploymentServices.size());
    Assertions.assertEquals("c7", deploymentServices.getFirst().getAdapterId());
    Assertions.assertEquals("camunda7", deploymentServices.getFirst().getAdapterType());

  }

}
