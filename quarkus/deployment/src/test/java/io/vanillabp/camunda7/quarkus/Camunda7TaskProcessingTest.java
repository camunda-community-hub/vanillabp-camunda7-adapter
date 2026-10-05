package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import io.vanillabp.camunda7.quarkus.task.QTaskAggregate;
import io.vanillabp.camunda7.quarkus.task.QTaskPersistence;
import io.vanillabp.camunda7.quarkus.task.QTaskWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.UserTransaction;

/**
 * End-to-end test of {@code @WorkflowTask} processing on Quarkus - a
 * real embedded engine on Agroal/Narayana: handlers run inside the engine's JTA
 * job transaction with the CDI request context active. The three outcomes:
 * <ul>
 * <li>normal return - aggregate mutation committed, task completed;</li>
 * <li>{@code TaskException} - error-boundary routing WITH committed aggregate
 * changes (the V1 contract);</li>
 * <li>technical exception - the job's JTA transaction rolls back (aggregate
 * unchanged), the job is retried (retry counter decremented).</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7TaskProcessingTest {

  private static final String MODULE_ID = "c7-test";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(QTaskAggregate.class)
          .addClass(QTaskPersistence.class)
          .addClass(QTaskWorkflowService.class)
          .addAsResource("application.yaml")
          .addAsResource("c7-task/processes/task-matrix.bpmn", "c7-test/processes/task-matrix.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // own database: the module's shared H2 URL (DB_CLOSE_DELAY=-1) would leak
      // residual instances between test classes (the failing job's instance of
      // this test stays active on purpose)
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:c7-task-test;DB_CLOSE_DELAY=-1");

  @Inject
  QTaskWorkflowService workflowService;

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  @Inject
  EntityManager entityManager;

  @Inject
  UserTransaction userTransaction;

  private Long start(
      final String bpmnProcessId) throws Exception {

    userTransaction.begin();
    try {
      final Long id;
      if ("QTaskProcess".equals(bpmnProcessId)) {
        id = workflowService.startWorkflow().getId();
      } else {
        final var aggregate = new QTaskAggregate();
        entityManager.persist(aggregate);
        entityManager.flush();
        engineRegistry
            .engineFor("c7")
            .getRuntimeService()
            .createProcessInstanceByKey(bpmnProcessId)
            .processDefinitionTenantId(MODULE_ID)
            .businessKey(String.valueOf(aggregate.getId()))
            .execute();
        id = aggregate.getId();
      }
      userTransaction.commit();
      return id;
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

  }

  private String resultsOf(
      final Long aggregateId) throws Exception {

    userTransaction.begin();
    try {
      final var aggregate = entityManager.find(QTaskAggregate.class, aggregateId);
      return aggregate != null
          ? aggregate.getResults()
          : null;
    } finally {
      userTransaction.rollback();
    }

  }

  private long countInstances(
      final Long aggregateId) {

    return engineRegistry
        .engineFor("c7")
        .getRuntimeService()
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .tenantIdIn(MODULE_ID)
        .count();

  }

  @Test
  @DisplayName("Happy path and TaskException boundary routing with committed aggregate changes")
  public void happyPathAndErrorBoundary() throws Exception {

    final var aggregateId = start("QTaskProcess");

    // The instance is created AFTER the commit, so waiting for it to
    // disappear would end before it ever existed - the results are the signal
    final var deadline = System.currentTimeMillis() + 15000;
    while (!"happy|error-raised|handled".equals(resultsOf(aggregateId))) {
      Assertions.assertTrue(
          System.currentTimeMillis() < deadline,
          "QTaskProcess did not run through in time; results so far: "
              + resultsOf(aggregateId));
      Thread.sleep(100);
    }

    // 'happy' committed by the first task, 'error-raised' committed ALTHOUGH the
    // handler threw (TaskException = BPMN error, no rollback), 'handled' via the
    // error boundary
    Assertions.assertEquals("happy|error-raised|handled", resultsOf(aggregateId));

  }

  @Test
  @DisplayName("completeTask resumes the parked async task after the caller's transaction committed")
  public void completeTaskResumesProcess() throws Exception {

    final var aggregateId = start("QAsyncProcess");

    // wait for the async handler (CREATED) to run and commit the task id
    final var deadline = System.currentTimeMillis() + 15000;
    String taskId = null;
    while (taskId == null) {
      Assertions.assertTrue(
          System.currentTimeMillis() < deadline,
          "the async handler did not commit the task id in time");
      userTransaction.begin();
      try {
        final var aggregate = entityManager.find(QTaskAggregate.class, aggregateId);
        taskId = aggregate != null
            ? aggregate.getTaskId()
            : null;
      } finally {
        userTransaction.rollback();
      }
      Thread.sleep(100);
    }

    userTransaction.begin();
    try {
      final var aggregate = entityManager.find(QTaskAggregate.class, aggregateId);
      aggregate.appendResult("completing");
      workflowService.completeAsyncTask(aggregate, taskId);
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    final var endDeadline = System.currentTimeMillis() + 15000;
    while (countInstances(aggregateId) > 0) {
      Assertions.assertTrue(
          System.currentTimeMillis() < endDeadline,
          "QAsyncProcess did not end after completeTask; results: "
              + resultsOf(aggregateId));
      Thread.sleep(100);
    }
    Assertions.assertEquals("async-open|completing", resultsOf(aggregateId));

  }

  @Test
  @DisplayName("The workflow is progressed AFTER the commit, not inside the caller's transaction")
  public void completeTaskProgressesAfterTheCommit() throws Exception {

    final var aggregateId = start("QAsyncProcess");
    final var taskId = awaitTaskId(aggregateId);

    userTransaction.begin();
    try {
      final var aggregate = entityManager.find(QTaskAggregate.class, aggregateId);
      workflowService.completeAsyncTask(aggregate, taskId);

      // still inside the caller's transaction: the engine has NOT been touched -
      // that is what makes the operation repeatable when it loses a conflict
      Assertions.assertTrue(
          parkedExecutionExists(taskId),
          "the task was resumed inside the caller's transaction");
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    // after the commit the outbox dispatches it
    final var deadline = System.currentTimeMillis() + 15000;
    while (parkedExecutionExists(taskId)) {
      Assertions.assertTrue(
          System.currentTimeMillis() < deadline,
          "the task was not resumed after the commit");
      Thread.sleep(100);
    }

  }

  /**
   * Whether the parked execution of an asynchronous task is still there.
   */
  private boolean parkedExecutionExists(
      final String taskId) {

    return engineRegistry
        .engineFor("c7")
        .getRuntimeService()
        .createExecutionQuery()
        .executionId(taskId)
        .count() > 0;

  }

  /**
   * Waits until the async handler committed the task id into the aggregate.
   */
  private String awaitTaskId(
      final Long aggregateId) throws Exception {

    final var deadline = System.currentTimeMillis() + 15000;
    String taskId = null;
    while (taskId == null) {
      Assertions.assertTrue(
          System.currentTimeMillis() < deadline,
          "the async handler did not commit the task id in time");
      userTransaction.begin();
      try {
        final var aggregate = entityManager.find(QTaskAggregate.class, aggregateId);
        taskId = aggregate != null
            ? aggregate.getTaskId()
            : null;
      } finally {
        userTransaction.rollback();
      }
      Thread.sleep(100);
    }
    return taskId;

  }

  @Test
  @DisplayName("A technical exception rolls back the job's JTA transaction and decrements the retries")
  public void technicalExceptionRollsBackAndRetries() throws Exception {

    final var aggregateId = start("QFailProcess");

    final var managementService = engineRegistry
        .engineFor("c7")
        .getProcessEngine()
        .getManagementService();

    final var instance = engineRegistry
        .engineFor("c7")
        .getRuntimeService()
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .tenantIdIn(MODULE_ID)
        .singleResult();
    Assertions.assertNotNull(instance);

    final var deadline = System.currentTimeMillis() + 15000;
    while (managementService
        .createJobQuery()
        .processInstanceId(instance.getId())
        .list()
        .stream()
        .noneMatch(job -> job.getRetries() < 3)) {
      Assertions.assertTrue(
          System.currentTimeMillis() < deadline,
          "the failing job's retries were not decremented in time");
      Thread.sleep(100);
    }

    // the instance stays (task not completed) and the handler's mutation was
    // rolled back with the job's JTA transaction
    Assertions.assertEquals(1, countInstances(aggregateId));
    Assertions.assertNull(resultsOf(aggregateId));

  }

}
