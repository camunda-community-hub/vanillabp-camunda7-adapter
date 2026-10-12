package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.fork.CibsevenAdapterDouble;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A fork of Camunda 7 uses the same tables. A <code>camunda7</code> id and an id of a fork
 * adapter on the same datasource without prefixes would be two engines on one set of tables,
 * and each job executor would run the jobs of the other one. The check of one adapter type
 * cannot see that, so the platform compares the tables of every adapter id across types. The
 * tables of <code>c7</code> come from the Quarkus configuration, the ones of the fork from a
 * double.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7ForkOnTheSameTablesTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addClass(CibsevenAdapterDouble.class)
          .addAsResource("fork-same-tables/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        final var messages = Camunda7BootFailure.messagesOf(throwable);
        Assertions.assertTrue(
            messages.contains("The adapters 'c7' (type 'camunda7'), 'cib' (type 'cibseven') work on the SAME "
                + "system: datasource <the application's default datasource>, table prefix <no table prefix>."),
            () -> messages);
        Assertions.assertTrue(messages.contains("for type 'camunda7', 'cibseven':"), () -> messages);
        Assertions.assertTrue(messages.contains("'vanillabp.adapters.<id>.data-source-name'"), () -> messages);
        Assertions.assertTrue(messages.contains("'vanillabp.adapters.<id>.table-prefix'"), () -> messages);
      });

  @Test
  public void aCamunda7IdAndAForkIdOnTheSameTablesFailTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
