package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.function.Function;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A start by message takes the newest version of the process, like a start without a
 * message does. The newest version need not be the one the application deployed while it
 * started: here a second version arrives from outside while the application runs.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // a database of its own: the second version deployed here must not reach the
    // TaskProcess the other test classes start
    "spring.datasource.url=jdbc:h2:mem:c7-start-by-message-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: an engine outliving its test keeps its job executor
// running against a database nobody uses any more
@DirtiesContext
public class Camunda7StartByMessageIT {

  private static final String MODULE_ID = "c7-it";

  @Autowired
  private TaskTestWorkflowService workflowService;

  @Autowired
  private TaskTestRepository repository;

  @Autowired
  private RepositoryService repositoryService;

  @Autowired
  private ProcessEngine processEngine;

  @Autowired
  private TransactionTemplate transactionTemplate;

  /**
   * Starts a workflow for a new aggregate and waits for its instance. The instance is
   * created after the commit, so the lookup waits instead of reading too early.
   *
   * @param start How the workflow is started
   * @return The id of the process definition the instance runs on
   */
  private String definitionStartedBy(
      final Function<TaskTestAggregate, TaskTestAggregate> start) throws InterruptedException {

    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TaskTestAggregate();
      aggregate.setApproved(true);
      return start.apply(repository.save(aggregate)).getId();
    });
    final var deadline = System.currentTimeMillis() + 30_000;
    while (true) {
      final var instance = processEngine
          .getHistoryService()
          .createHistoricProcessInstanceQuery()
          .processInstanceBusinessKey(String.valueOf(aggregateId))
          .singleResult();
      if (instance != null) {
        return instance.getProcessDefinitionId();
      }
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for the instance of aggregate %s".formatted(aggregateId));
      }
      Thread.sleep(100);
    }

  }

  private String newestDefinitionOfTaskProcess() {

    return repositoryService
        .createProcessDefinitionQuery()
        .processDefinitionKey("TaskProcess")
        .tenantIdIn(MODULE_ID)
        .latestVersion()
        .singleResult()
        .getId();

  }

  @Test
  @DisplayName("A start by message takes a version deployed from outside after the application started")
  public void aStartByMessageTakesTheNewestVersion() throws Exception {

    final var deployedByTheApplication = newestDefinitionOfTaskProcess();
    assertEquals(
        deployedByTheApplication,
        definitionStartedBy(aggregate -> workflowService.startByMessage(aggregate, "TaskRequested")),
        "before anybody deploys a second version, the message starts the application's version");

    // another model of the same process with the same message start event, deployed the way
    // another node of a rolling deployment or an operator would deploy it
    repositoryService
        .createDeployment()
        .name(MODULE_ID)
        .tenantId(MODULE_ID)
        .addClasspathResource("c7-it/message-start/task-process-v2.bpmn")
        .deploy();
    final var deployedFromOutside = newestDefinitionOfTaskProcess();
    assertNotEquals(deployedByTheApplication, deployedFromOutside);

    assertEquals(
        deployedFromOutside,
        definitionStartedBy(aggregate -> workflowService.startByMessage(aggregate, "TaskRequested")),
        "the message starts the newest version");
    assertEquals(
        deployedFromOutside,
        definitionStartedBy(workflowService::startTaskProcess),
        "a start without a message takes the same version");

  }

}
