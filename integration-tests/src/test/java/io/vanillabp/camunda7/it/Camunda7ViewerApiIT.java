package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessDefinitionNotFoundException;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.WorkflowElementType;
import io.vanillabp.spi.process.WorkflowNotFoundException;

/**
 * Integration test of the viewer/history API against a <b>real embedded
 * engine on H2</b>: process definitions incl. the call activity's called process,
 * the deployed BPMN XML and the instance timeline - for a RUNNING and for an
 * ENDED workflow.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // a database of its own: the phase-two outbox of a test class Spring keeps
    // cached would otherwise dispatch the entries of the next one
    "spring.datasource.url=jdbc:h2:mem:c7-viewer-api-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7ViewerApiIT {

  @Autowired
  private ProcessService<ViewerTestAggregate> viewerProcessService;

  @Autowired
  private ViewerAggregateRepository viewerAggregateRepository;

  @Autowired
  private org.camunda.bpm.engine.RuntimeService runtimeService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  private ViewerTestAggregate startViewedWorkflow() {

    final var aggregate = transactionTemplate.execute(status -> {
      final var newAggregate = new ViewerTestAggregate();
      newAggregate.setContent("viewer-test");
      return viewerProcessService.startWorkflow(viewerAggregateRepository.save(newAggregate));
    });

    // the instance is created by the phase-two outbox right after the commit, and
    // there is nothing to view before it exists
    AwaitPhaseTwo.until(
        () -> runtimeService
            .createProcessInstanceQuery()
            .processInstanceBusinessKey(String.valueOf(aggregate.getId()))
            .processDefinitionKey("ViewerParentProcess")
            .count() == 1,
        "the workflow of aggregate '%s' to be started".formatted(aggregate.getId()));
    return aggregate;

  }

  @Test
  @DisplayName("The definitions of a running workflow include the called process in the version executed next")
  public void processDefinitionsIncludeCalledProcesses() throws IOException {

    final var aggregate = startViewedWorkflow();

    final var definitions = viewerProcessService.getProcessDefinitions(aggregate, null);

    assertEquals(2, definitions.size(), () -> "expected the process and its called process but got: "
        + definitions);

    final var parent = definitions.getFirst();
    assertTrue(
        parent
            .id()
            .startsWith("c7#"),
        () -> "process definition ids are namespaced per adapter id but got: "
            + parent.id());
    assertEquals("ViewerParentProcess", parent.bpmnProcessId());
    assertEquals("1", parent.version());
    assertNull(parent.usedByElements(), "the workflow's own definition has no usedByElements");

    final var sub = definitions.get(1);
    assertEquals("ViewerSubProcess", sub.bpmnProcessId());
    assertEquals(List.of("TheCallActivity"), sub.usedByElements());

    // the BPMN XML is what the engine has DEPLOYED - a viewer has to be able to
    // render it (Camunda 7 stores the model VanillaBP's deployment pipeline handed
    // over, so the XML is semantically the resource, serialized by the engine)
    final String deployedXml;
    try (var xml = viewerProcessService.getBpmnXml(parent.id())) {
      deployedXml = new String(xml.readAllBytes(), StandardCharsets.UTF_8);
    }
    final var deployedModel = org.camunda.bpm.model.bpmn.Bpmn.readModelFromStream(
        new java.io.ByteArrayInputStream(deployedXml.getBytes(StandardCharsets.UTF_8)));
    assertNotNull(
        deployedModel.getModelElementById("ViewerParentProcess"),
        () -> "the BPMN XML has to contain the process but got: "
            + deployedXml);
    assertNotNull(
        deployedModel.getModelElementById("TheCallActivity"),
        () -> "the BPMN XML has to contain the call activity but got: "
            + deployedXml);

    // the called process' XML is a DIFFERENT definition - the ids stay resolvable
    try (var subXml = viewerProcessService.getBpmnXml(sub.id())) {
      final var subModel = org.camunda.bpm.model.bpmn.Bpmn.readModelFromStream(subXml);
      assertNotNull(subModel.getModelElementById("ViewerSubProcess"));
    }

  }

  @Test
  @DisplayName("The history of a running workflow reflects its execution and offers the call activity's context")
  public void historyReflectsExecutionAndOffersSecondaryContext() throws IOException {

    final var aggregate = startViewedWorkflow();

    final var history = viewerProcessService.getWorkflowHistory(aggregate, null);

    assertNotNull(history.startTime(), "a running workflow has a start time");
    assertNull(history.endTime(), "a running workflow has no end time");
    assertNotNull(history.elementsHistory(), "Camunda 7 records an element history");

    final var startEvent = history
        .elementsHistory()
        .stream()
        .filter(element -> "TheStartEvent".equals(element.elementId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no history of the start event: "
            + history.elementsHistory()));
    assertEquals(WorkflowElementType.START_EVENT, startEvent.elementType());
    assertNotNull(startEvent.endTime(), "the start event was passed");
    assertFalse(startEvent.isCanceled());

    final var callActivity = history
        .elementsHistory()
        .stream()
        .filter(element -> "TheCallActivity".equals(element.elementId()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no history of the call activity: "
            + history.elementsHistory()));
    assertEquals(WorkflowElementType.CALL_ACTIVITY, callActivity.elementType());
    assertNull(callActivity.endTime(), "the call activity is still running");
    final var secondaryContext = callActivity.secondaryWorkflowHistoryContext();
    assertNotNull(secondaryContext, "an executed call activity offers its called instance's context");

    // digging into the called process: its definition and its own history
    final var subDefinitions = viewerProcessService.getProcessDefinitions(aggregate, secondaryContext);
    assertEquals(1, subDefinitions.size());
    assertEquals("ViewerSubProcess", subDefinitions
        .getFirst()
        .bpmnProcessId());
    assertNull(subDefinitions
        .getFirst()
        .usedByElements());

    final var subHistory = viewerProcessService.getWorkflowHistory(aggregate, secondaryContext);
    assertNotNull(subHistory.startTime());
    assertTrue(
        subHistory
            .elementsHistory()
            .stream()
            .anyMatch(element -> "TheWait".equals(element.elementId())),
        () -> "the called instance waits in the timer event but got: "
            + subHistory.elementsHistory());
    assertTrue(
        subHistory
            .processDefinitionId()
            .startsWith("c7#"),
        "the definition id inside the history is namespaced, too");

  }

  @Test
  @DisplayName("An ENDED workflow is still viewable (its history is what viewers show)")
  public void endedWorkflowsStayViewable() {

    final var aggregate = startViewedWorkflow();

    // end the workflow deterministically instead of waiting for the job executor
    // (other integration tests of this module steer it, so its state is nothing
    // this test may rely on)
    final var instanceId = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregate.getId()))
        .processDefinitionKey("ViewerParentProcess")
        .singleResult()
        .getId();
    transactionTemplate.executeWithoutResult(
        status -> runtimeService.deleteProcessInstance(instanceId, "ended by the viewer test"));

    final var history = viewerProcessService.getWorkflowHistory(aggregate, null);

    assertNotNull(history.endTime(), "the workflow ended - the history has to report it");
    assertTrue(
        history
            .elementsHistory()
            .stream()
            .anyMatch(element -> "TheCallActivity".equals(element.elementId()) && element.isCanceled()),
        () -> "the canceled call activity has to show up in the history but got: "
            + history.elementsHistory());
    assertFalse(
        viewerProcessService
            .getProcessDefinitions(aggregate, null)
            .isEmpty(),
        "the definitions of an ended workflow are still resolvable");

  }

  @Test
  @DisplayName("Unknown workflows and unknown process definitions raise the SPI's guiding exceptions")
  public void unknownSubjectsRaiseGuidingErrors() {

    // an ID no workflow ever used - deliberately NOT persisted: the viewing API
    // only reads the aggregate's ID, and a generated ID could collide with the
    // business key of a workflow of another test
    final var neverStarted = new ViewerTestAggregate();
    neverStarted.setId(987654321L);
    neverStarted.setContent("never-started");

    final var workflowNotFound = assertThrows(
        WorkflowNotFoundException.class,
        () -> viewerProcessService.getWorkflowHistory(neverStarted, null));
    assertTrue(
        workflowNotFound
            .getMessage()
            .contains(String.valueOf(neverStarted.getId())),
        () -> "expected a guiding message but got: "
            + workflowNotFound.getMessage());

    final var definitionNotFound = assertThrows(
        ProcessDefinitionNotFoundException.class,
        () -> viewerProcessService.getBpmnXml("c7#ViewerParentProcess:99:does-not-exist"));
    assertTrue(
        definitionNotFound
            .getMessage()
            .contains("does-not-exist"),
        () -> "expected a guiding message but got: "
            + definitionNotFound.getMessage());

  }

}
