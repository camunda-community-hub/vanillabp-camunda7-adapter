package io.vanillabp.camunda7.quarkus;

import java.util.List;

import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.camunda7.quarkus.runtime.VanillaBpCamunda7Properties;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Where the Quarkus overlay reads <code>allow-listeners</code>, and what it says about a value
 * written at a level it does not read.
 * <p>
 * The same three levels and the same most-specific-wins rule the Spring Boot module reads, and
 * the same the Camunda 8 adapter reads for the very same key. The module this boot deploys
 * carries no listener, so the boot says the switch is on and nothing uses it, which is the case
 * worth having in a boot test as well.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7AllowListenersOverlayTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("listeners/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  private VanillaBpCamunda7Properties overlay() {

    return ConfigProvider
        .getConfig()
        .unwrap(SmallRyeConfig.class)
        .getConfigMapping(VanillaBpCamunda7Properties.class);

  }

  @Test
  public void allowListenersResolvesThroughItsThreeLevels() {

    final var overlay = overlay();

    // the workflow serves its listeners where its module does not
    final var perWorkflow = overlay.allowListenersFor("c7", "c7-test", "TestProcess");
    Assertions.assertTrue(perWorkflow.allowed());
    Assertions.assertEquals(
        "vanillabp.workflow-modules.c7-test.workflows.TestProcess.adapters.c7.allow-listeners",
        perWorkflow.propertyKey(),
        "the report has to name the line the reader can find in their configuration");

    // the module switches OFF what the adapter switched on, which version 1 could not
    final var perModule = overlay.allowListenersFor("c7", "c7-test", "OtherProcess");
    Assertions.assertFalse(perModule.allowed());
    Assertions.assertEquals(
        "vanillabp.workflow-modules.c7-test.adapters.c7.allow-listeners",
        perModule.propertyKey());

    // and everything the module says nothing about follows the adapter
    final var perAdapter = overlay.allowListenersFor("c7", "unknown-module", "SomeProcess");
    Assertions.assertTrue(perAdapter.allowed());
    Assertions.assertEquals("vanillabp.adapters.c7.allow-listeners", perAdapter.propertyKey());

    final var nothingConfigured = overlay.allowListenersFor("unknown-adapter", "c7-test", "TestProcess");
    Assertions.assertFalse(nothingConfigured.allowed(), "the default serves no modelled listener");
    Assertions.assertNull(nothingConfigured.propertyKey());

  }

  @Test
  public void aValueAtTaskLevelIsFoundAndChangesNoAnswer() {

    final var overlay = overlay();

    Assertions.assertEquals(
        List
            .of(
                "vanillabp.workflow-modules.c7-test.workflows.TestProcess.tasks.archiveTheOrder.adapters.c7.allow-listeners"),
        overlay.allowListenersKeysAtTaskLevel("c7"),
        "the boot names the key it cannot honour instead of ignoring it silently");
    Assertions.assertTrue(
        overlay.allowListenersFor("c7", "c7-test", "TestProcess").allowed(),
        "and the answer still comes from the workflow level");

  }

}
