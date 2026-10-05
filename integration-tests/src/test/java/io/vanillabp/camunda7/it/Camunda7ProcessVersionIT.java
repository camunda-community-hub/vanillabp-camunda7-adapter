package io.vanillabp.camunda7.it;

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
 * End-to-end test of <code>&#64;WorkflowTask(version = ...)</code> against a
 * real embedded Camunda 7 engine with TWO deployed versions of one process: the
 * workflow started while only version 1 exists is served by the method of version 1,
 * and a workflow started after a second version was deployed is served by the method
 * naming that version's <code>camunda:versionTag</code>.
 * <p>
 * The second version is deployed while the application runs, which is what a rolling
 * deployment does from another cluster node - so this also proves the on-demand lookup
 * of a version this node never deployed.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: contexts are cached and live in parallel - a foreign engine
    // (and job executor) on the same H2 database would compete for this test's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-versions-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, Spring would keep every context until the JVM exits, and an engine outliving its
// test keeps its job executor running against a database the next classes work on
@DirtiesContext
public class Camunda7ProcessVersionIT {

  @Autowired
  private VersionedTestWorkflowService workflowService;

  @Autowired
  private VersionedTestRepository repository;

  @Autowired
  private RepositoryService repositoryService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  /**
   * The task of the model runs on the engine's job executor (VanillaBP makes tasks
   * asynchronous), so the aggregate carries the answer a moment after the start.
   */
  private void awaitServedBy(
      final Long aggregateId,
      final String expected,
      final String description) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (!expected.equals(repository.findById(aggregateId).orElseThrow().getServedBy())) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("timed out waiting for "
            + description
            + " - the aggregate says '"
            + repository.findById(aggregateId).orElseThrow().getServedBy()
            + "'");
      }
      Thread.sleep(100);
    }

  }

  @Test
  @DisplayName("the version of the deployed process definition decides which method serves the task")
  public void theVersionDecidesWhichMethodRuns() throws Exception {

    // the application deployed version 1 while booting
    final var firstId = transactionTemplate.execute(status -> workflowService.startWorkflow().getId());
    awaitServedBy(
        firstId,
        "firstVersion",
        "version 1 to be served by the method specifying version '1'");

    // a second version, deployed while the application runs and tagged
    // 'release-2' - the way another node of a rolling deployment would
    repositoryService
        .createDeployment()
        .name("c7-it")
        .tenantId("c7-it")
        .addClasspathResource("c7-it/versioned/versioned-process-v2.bpmn")
        .deploy();

    final var secondId = transactionTemplate.execute(status -> workflowService.startWorkflow().getId());
    awaitServedBy(
        secondId,
        "taggedVersion",
        "version 2 to be served by the method naming its version tag - the engine is asked "
            + "about it, since this application never deployed that version");

  }

}
