package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * The job executor starts late, tested end to end. Asynchronous continuations
 * ({@code asyncBefore} jobs) run ONLY while workflow processing is started. The test pauses
 * processing through the engine holder. {@code stopWorkflowProcessing} of the only module
 * stops the executor, which the whole engine shares. Then the test creates an instance, so
 * its async-before job stays pending. Then it resumes processing, the job runs, and the
 * instance completes. This proves both directions of the lifecycle which the deployment
 * pipeline wires.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database. Test contexts are cached and live in parallel. The engine and job
    // executor of another context on the same H2 database would run the jobs which this
    // test keeps pending on purpose.
    "spring.datasource.url=jdbc:h2:mem:c7-lifecycle-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done. This IT has a database of its own, and so a context of its
// own. Spring would keep every context until the JVM exits. An engine which outlives its test
// keeps its job executor running against a database which the next classes work on.
@DirtiesContext
public class Camunda7JobExecutorLifecycleIT {

  private static final String MODULE_ID = "c7-it";

  @Autowired
  private Camunda7EngineHolder engineHolder;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private ProcessService<TestAggregate> processService;

  /**
   * How long the engine is watched while its job executor is stopped. After that, a job
   * which did not run counts as one which will not run.
   * <p>
   * One second, compared with the moment a running job executor needs. The transaction
   * which wrote a new job tells this engine about it. So the engine acquires the job at the
   * commit, not on its own next sweep.
   * <p>
   * This is a guard, not a time limit anybody has to beat. The test asserts afterwards that
   * the instance is still there. A machine which gives this JVM no turn only makes the
   * silence longer.
   */
  private static final long UNTIL_A_RUNNING_EXECUTOR_WOULD_HAVE_TAKEN_THE_JOB = 1000;

  private long countInstances(
      final String businessKey) {

    return engineHolder
        .getRuntimeService()
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(businessKey)
        .tenantIdIn(MODULE_ID)
        .count();

  }

  @Test
  @DisplayName("async jobs run only while workflow processing is started")
  public void jobsRunOnlyWhileProcessingIsStarted() throws Exception {

    // the deployment pipeline started processing at boot
    assertTrue(engineHolder.isJobExecutorActive(), "processing was started at boot");

    // pause processing. The only module stops, so the executor of the whole engine stops.
    engineHolder.stopWorkflowProcessing(MODULE_ID);
    assertFalse(engineHolder.isJobExecutorActive());

    // an instance started now parks in its async-before job...
    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new TestAggregate();
      aggregate.setContent("job-executor-lifecycle");
      return processService.startWorkflow(aggregate).getId();
    });
    assertNotNull(aggregateId);
    final var businessKey = String.valueOf(aggregateId);

    // ...and stays pending while the executor is stopped (completing would end the
    // instance - the trivial ${true} service task is the only wait state)
    Thread.sleep(UNTIL_A_RUNNING_EXECUTOR_WOULD_HAVE_TAKEN_THE_JOB);
    assertEquals(
        1,
        countInstances(businessKey),
        "the async-before job must not be executed while processing is stopped");

    // resuming processing lets the job executor pick the job up. The service task
    // completes, and the instance ends.
    engineHolder.startWorkflowProcessing(MODULE_ID);
    assertTrue(engineHolder.isJobExecutorActive());

    final var deadline = System.currentTimeMillis() + 15000;
    while (countInstances(businessKey) > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the pending job was not executed after processing was started");
      Thread.sleep(100);
    }

  }

}
