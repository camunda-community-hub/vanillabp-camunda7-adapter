package io.vanillabp.camunda7.quarkus;

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
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Deferred job-executor activation on Quarkus, with the same semantics as on Spring
 * Boot: asynchronous continuations ({@code asyncBefore} jobs) are executed
 * ONLY while workflow processing is started. The test pauses processing via the
 * engine holder, creates an instance whose async-before job therefore stays
 * pending, and resumes processing - the job executor picks the job up (executing it
 * in its OWN JTA transaction: without the engine's JTA command interceptor the job
 * would fail on Agroal) and the instance completes.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7JobExecutorLifecycleTest {

  private static final String MODULE_ID = "c7-test";

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
  TestWorkflowService workflowService;

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  @Inject
  UserTransaction userTransaction;

  /**
   * How long the engine is watched while its job executor is stopped, before a job which
   * was not executed counts as one which will not be.
   * <p>
   * One second, against the moment a running job executor needs: this engine is told about
   * a new job by the transaction which wrote it, so it acquires the job at the commit
   * rather than on the next sweep of its own.
   * <p>
   * A guard and not a budget anybody has to be faster than: what is asserted afterwards is
   * an instance which is still there, and a machine which leaves this JVM without a turn
   * only makes the silence longer.
   */
  private static final long UNTIL_A_RUNNING_EXECUTOR_WOULD_HAVE_TAKEN_THE_JOB = 1000;

  private long countInstances(
      final String businessKey) {

    return engineRegistry
        .engineFor("c7")
        .getRuntimeService()
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(businessKey)
        .tenantIdIn(MODULE_ID)
        .count();

  }

  @Test
  public void jobsRunOnlyWhileProcessingIsStarted() throws Exception {

    final var engine = engineRegistry.engineFor("c7");

    // the deployment pipeline started processing at boot
    Assertions.assertTrue(engine.isJobExecutorActive(), "processing was started at boot");

    // pause processing: the only module stops -> the engine-global executor stops
    engine.stopWorkflowProcessing(MODULE_ID);
    Assertions.assertFalse(engine.isJobExecutorActive());

    // an instance started now parks in its async-before job...
    userTransaction.begin();
    final TestAggregate aggregate;
    try {
      aggregate = workflowService.startWorkflow("job-executor-lifecycle");
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }
    userTransaction.commit();
    final var businessKey = String.valueOf(aggregate.getId());

    // ...and stays pending while the executor is stopped (completing would end
    // the instance - the trivial ${true} service task is the only wait state)
    Thread.sleep(UNTIL_A_RUNNING_EXECUTOR_WOULD_HAVE_TAKEN_THE_JOB);
    Assertions.assertEquals(
        1,
        countInstances(businessKey),
        "the async-before job must not be executed while processing is stopped");

    // resuming processing lets the job executor pick the job up: the service task
    // completes (in its own JTA transaction) and the instance ends
    engine.startWorkflowProcessing(MODULE_ID);
    Assertions.assertTrue(engine.isJobExecutorActive());

    final var deadline = System.currentTimeMillis() + 15000;
    while (countInstances(businessKey) > 0) {
      if (System.currentTimeMillis() >= deadline) {
        // failed jobs park with an exception - surface it for diagnosis
        final var failedJob = engine
            .getProcessEngine()
            .getManagementService()
            .createJobQuery()
            .noRetriesLeft()
            .singleResult();
        Assertions.fail(
            "the pending job was not executed after processing was started"
                + (failedJob != null
                    ? "; job exception: "
                        + engine
                            .getProcessEngine()
                            .getManagementService()
                            .getJobExceptionStacktrace(failedJob.getId())
                    : ""));
      }
      Thread.sleep(100);
    }

  }

}
