package io.vanillabp.camunda7.quarkus;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.sample.TestAggregate;
import io.vanillabp.camunda7.quarkus.sample.TestWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.ProcessDefinitionNotFoundException;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.WorkflowElementType;
import io.vanillabp.spi.process.WorkflowNotFoundException;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The viewer/history API on Quarkus: the embedded engine's repository
 * and history services answer definitions, BPMN XML and the instance timeline -
 * platform parity to the Spring Boot integration test of the same feature (the
 * logic itself lives in the adapter's core).
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7ViewerApiTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(TestAggregate.class)
          .addClass(io.vanillabp.camunda7.quarkus.sample.TestAggregatePersistence.class)
          .addClass(TestWorkflowService.class)
          .addAsResource("application.yaml")
          .addAsResource("c7-test/processes/test-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // own database: the module's shared H2 URL would leak instances between test
      // classes
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:c7-viewer-test;DB_CLOSE_DELAY=-1");

  @Inject
  TestWorkflowService workflowService;

  @Inject
  ProcessService<TestAggregate> processService;

  @Inject
  UserTransaction userTransaction;

  private TestAggregate startWorkflow() throws Exception {

    userTransaction.begin();
    try {
      final var aggregate = workflowService.startWorkflow("viewer");
      userTransaction.commit();
      return aggregate;
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

  }

  private List<ProcessDefinition> awaitProcessDefinitions(
      final TestAggregate aggregate) throws Exception {

    final var deadline = System.currentTimeMillis() + 20000;
    while (true) {
      try {
        return processService.getProcessDefinitions(aggregate, null);
      } catch (final WorkflowNotFoundException e) {
        if (System.currentTimeMillis() >= deadline) {
          throw e;
        }
        Thread.sleep(100);
      }
    }

  }

  @Test
  @DisplayName("Definitions, BPMN XML and history are served by the embedded engine")
  public void viewerApiIsServedByTheEmbeddedEngine() throws Exception {

    final var aggregate = startWorkflow();

    // the instance is created by the phase-two outbox right AFTER the
    // commit, so the viewing API knows the workflow a moment later than the call
    final var definitions = awaitProcessDefinitions(aggregate);

    Assertions.assertEquals(1, definitions.size(), () -> "expected the workflow's definition but got: "
        + definitions);
    final var definition = definitions.getFirst();
    Assertions.assertTrue(
        definition
            .id()
            .startsWith("c7#"),
        () -> "process definition ids are namespaced per adapter id but got: "
            + definition.id());
    Assertions.assertEquals("TestProcess", definition.bpmnProcessId());
    Assertions.assertNull(definition.usedByElements());

    try (var xml = processService.getBpmnXml(definition.id())) {
      final var deployedXml = new String(xml.readAllBytes(), StandardCharsets.UTF_8);
      Assertions.assertTrue(
          deployedXml.contains("TestProcess"),
          () -> "the BPMN XML has to contain the process but got: "
              + deployedXml);
    }

    // the single service task runs in the job executor - poll until the workflow
    // ended, then the history reports both the execution and the end
    final var deadline = System.currentTimeMillis() + 20000;
    var history = processService.getWorkflowHistory(aggregate, null);
    while ((history.endTime() == null) && (System.currentTimeMillis() < deadline)) {
      Thread.sleep(100);
      history = processService.getWorkflowHistory(aggregate, null);
    }

    final var endedHistory = history;
    Assertions.assertNotNull(endedHistory.startTime());
    Assertions.assertNotNull(endedHistory.endTime(), "the ENDED workflow is still viewable");
    Assertions.assertTrue(
        endedHistory
            .elementsHistory()
            .stream()
            .anyMatch(element -> "ServiceTask_1"
                .equals(element.elementId()) && (element.elementType() == WorkflowElementType.SERVICE_TASK)),
        () -> "the executed service task has to show up in the history but got: "
            + endedHistory.elementsHistory());

  }

  @Test
  @DisplayName("Unknown workflows and unknown process definitions raise the SPI's guiding exceptions")
  public void unknownSubjectsRaiseGuidingErrors() {

    // an ID no workflow ever used - deliberately NOT persisted: the viewing API
    // only reads the aggregate's ID
    final var neverStarted = new TestAggregate();
    neverStarted.setId(987654321L);
    neverStarted.setContent("never-started");

    Assertions.assertThrows(
        WorkflowNotFoundException.class,
        () -> processService.getWorkflowHistory(neverStarted, null));

    Assertions.assertThrows(
        ProcessDefinitionNotFoundException.class,
        () -> processService.getBpmnXml("c7#TestProcess:99:does-not-exist"));

  }

}
