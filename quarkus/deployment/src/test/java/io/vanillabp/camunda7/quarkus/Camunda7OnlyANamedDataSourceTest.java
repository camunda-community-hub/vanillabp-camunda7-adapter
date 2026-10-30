package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.onlynamed.OutboxOfTheTest;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * The application's only datasource is a named one, without XA. The engine of {@code c7}
 * runs on it, and so does Hibernate ORM. Each transaction has this one datasource only, so
 * nothing needs XA. Such an application keeps its outbox outside the database, for example
 * on MongoDB. The boot goes on, and a workflow runs to its end.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7OnlyANamedDataSourceTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addClass(Camunda7WorkflowEnd.class)
          .addClass(OutboxOfTheTest.class)
          .addAsResource("only-a-named-datasource/application.yaml", "application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn", "c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  @Inject
  EntityManager entityManager;

  @Inject
  UserTransaction userTransaction;

  @Test
  public void theWorkflowEnds() throws Exception {

    Camunda7WorkflowEnd.runsToTheEnd(engineRegistry.engineFor("c7"), entityManager, userTransaction);

  }

}
