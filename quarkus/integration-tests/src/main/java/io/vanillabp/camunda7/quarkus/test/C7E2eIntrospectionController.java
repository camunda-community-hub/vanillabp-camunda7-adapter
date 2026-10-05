package io.vanillabp.camunda7.quarkus.test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.jobexecutor.SequentialJobAcquisitionRunnable;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;

import io.vanillabp.camunda7.engine.Camunda7SleepingAcquisition;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.UserTransaction;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * What the Quarkus end-to-end tests can see of the running application: a handful of
 * <code>introspect/...</code> endpoints reporting the aggregates and the embedded
 * engine's state, and triggering the {@code ProcessService} operations the tests want
 * to observe.
 * <p>
 * A prod-mode test runs the application in a forked JVM, so nothing of it can be
 * injected into the test - everything travels through these endpoints. The ones
 * driving VanillaBP open their transaction themselves
 * ({@link jakarta.transaction.UserTransaction}), because what happens inside the
 * caller's transaction and what only after its commit is half of what the tests are
 * about.
 */
@Path("/introspect")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class C7E2eIntrospectionController {

  private static final String ADAPTER_ID = "c7";

  private static final String MODULE_ID = "c7-e2e";

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  @Inject
  C7E2eWorkflowService workflowService;

  @Inject
  C7PushWorkflowService pushWorkflowService;

  @Inject
  EntityManager entityManager;

  @Inject
  UserTransaction userTransaction;

  /**
   * The count of connections taken from the application's pool - the one number which says
   * whether a sleeping engine really left the database alone.
   */
  @Inject
  C7ConnectionsTaken connectionsTaken;

  // --- the application's own state ---

  @GET
  @Path("/workflow-module")
  @Produces(MediaType.TEXT_PLAIN)
  public String workflowModule() {

    return workflowService.getWorkflowModuleId();

  }

  @GET
  @Path("/aggregates/{id}")
  @Transactional
  public Map<String, Object> aggregate(
      @PathParam("id") final Long id) {

    final var aggregate = entityManager.find(C7E2eAggregate.class, id);
    final var state = new LinkedHashMap<String, Object>();
    state.put("exists", aggregate != null);
    if (aggregate != null) {
      state.put("results", aggregate.getResults());
      state.put("taskId", aggregate.getTaskId());
      state.put("approved", aggregate.isApproved());
    }
    return state;

  }

  @GET
  @Path("/push-aggregates/{id}")
  @Transactional
  public Map<String, Object> pushAggregate(
      @PathParam("id") final Long id) {

    final var aggregate = entityManager.find(C7PushAggregate.class, id);
    final var state = new LinkedHashMap<String, Object>();
    state.put("exists", aggregate != null);
    if (aggregate != null) {
      state.put("processedBy", aggregate.getProcessedBy());
      state.put("taskIds", aggregate.getTaskIds());
      state.put("escalatedItems", aggregate.getEscalatedItems());
    }
    return state;

  }

  /**
   * The aggregates of the workflows the ENGINE started on its own - the
   * application never creates one of them.
   *
   * @return One "id|processedBy|endedAs" per aggregate
   */
  @GET
  @Path("/timer-aggregates")
  @Transactional
  public List<String> timerAggregates() {

    return entityManager
        .createQuery("select a from C7TimerAggregate a", C7TimerAggregate.class)
        .getResultList()
        .stream()
        .map(aggregate -> "%s|%s|%s".formatted(aggregate.getId(), aggregate.getProcessedBy(), aggregate.getEndedAs()))
        .toList();

  }

  /**
   * Which job executor the engine of the adapter id runs, by class name. It is how a test
   * sees whether the property asking for the due-date sleep reached the engine.
   *
   * @return The simple class name of the engine's job executor
   */
  @GET
  @Path("/engine/job-executor")
  @Produces(MediaType.TEXT_PLAIN)
  public String jobExecutor() {

    return ((ProcessEngineConfigurationImpl) engineRegistry
        .engineFor(ADAPTER_ID)
        .getProcessEngine()
        .getProcessEngineConfiguration())
        .getJobExecutor()
        .getClass()
        .getSimpleName();

  }

  /**
   * How many connections were taken from the application's datasource since it was created.
   * A connection is what anything in the application needs before it can say a word to the
   * database, so a count which does not move is a database nobody spoke to - which is what
   * the due-date sleep is worth measuring.
   *
   * @return How many connections were taken since the application started
   */
  @GET
  @Path("/datasource/connections-taken")
  @Produces(MediaType.TEXT_PLAIN)
  public String connectionsTaken() {

    return Long.toString(connectionsTaken.count());

  }

  /**
   * What the engine's job acquisition is doing, and what it has cost the database so far.
   * Nothing here touches the database, which is the point: a measurement of silence cannot
   * ask its question through the thing it measures, and every engine command wakes the
   * acquisition it is asking about.
   * <p>
   * The two values a measurement leans on are <code>lastCycleAt</code> and
   * <code>foundNothing</code>. Together they say that the acquisition ran a cycle which
   * found nothing to do and is therefore waiting for a due date rather than backing off
   * after work. A cycle which runs later moves <code>lastCycleAt</code>, so a sleep which
   * was interrupted cannot look like a sleep which held.
   * <p>
   * The engine's own rule has a fourth condition this cannot see, the executing threads
   * being full. That case makes the acquisition busy rather than idle, so it shows up as
   * <code>foundNothing</code> being false anyway.
   *
   * Whether that thread is parked is reported as well. The cycle which found nothing is
   * still running for a moment after it says so, and the connection it gives back at the end
   * of it would land inside a window opened too early.
   *
   * @return The acquisition's thread and whether it is parked, the moment of its last cycle,
   *         whether that cycle found nothing, and the connections taken in total and by that
   *         thread
   */
  @GET
  @Path("/engine/acquisition")
  public Map<String, Object> acquisition() {

    final var runnable = (SequentialJobAcquisitionRunnable) ((ProcessEngineConfigurationImpl) engine()
        .getProcessEngine()
        .getProcessEngineConfiguration())
        .getJobExecutor()
        .getAcquireJobsRunnable();
    final var context = runnable.getAcquisitionContext();
    final var acquisitionThread = threadRunningTheAcquisition(runnable);

    final var reported = new LinkedHashMap<String, Object>();
    reported.put("thread", Long.valueOf(acquisitionThread));
    reported.put("parked", Boolean.valueOf(isParked(acquisitionThread)));
    reported.put("lastCycleAt", Long.valueOf(context.getAcquisitionTime()));
    reported
        .put(
            "foundNothing",
            Boolean
                .valueOf(context.areAllEnginesIdle() && !context
                    .hasJobAcquisitionLockFailureOccurred() && (context.getAcquisitionException() == null)));
    reported.put("connectionsTaken", Long.valueOf(connectionsTaken.count()));
    reported
        .put(
            "takenByTheAcquisition",
            connectionsTaken
                .diary()
                .stream()
                .filter(take -> take.threadId() == acquisitionThread)
                .map(take -> "%d|%s".formatted(Long.valueOf(take.at()), take.threadName()))
                .toList());
    return reported;

  }

  /**
   * Whether the thread with this id waits right now. The acquisition thread sleeps between
   * two cycles, and a test which expects the engine to be idle asks for exactly that.
   *
   * @param threadId The id of the thread to look at
   * @return Whether that thread waits or waits with a timeout
   */
  private boolean isParked(
      final long threadId) {

    return Thread
        .getAllStackTraces()
        .keySet()
        .stream()
        .filter(thread -> thread.threadId() == threadId)
        .anyMatch(
            thread -> (thread.getState() == Thread.State.WAITING) || (thread.getState() == Thread.State.TIMED_WAITING));

  }

  /**
   * Which thread runs the acquisition cycles right now. The adapter answers that question
   * itself - {@code Camunda7WakeupAfterCommit} asks it about every committing thread - so
   * the threads of the application are held against that answer instead of against a name
   * somebody guessed.
   *
   * @param runnable The acquisition loop of the engine
   * @return The thread's id, or -1 while no thread runs the loop
   */
  private long threadRunningTheAcquisition(
      final SequentialJobAcquisitionRunnable runnable) {

    if (!(runnable instanceof Camunda7SleepingAcquisition acquisition)) {
      return -1;
    }
    return Thread
        .getAllStackTraces()
        .keySet()
        .stream()
        .filter(acquisition::runsTheAcquisition)
        .mapToLong(Thread::threadId)
        .findFirst()
        .orElse(-1);

  }

  /**
   * When the engine's next job falls due, which is the moment a sleeping acquisition wakes
   * up at. The same question the sleep itself asks: the earliest due date of an active job
   * which still has retries left.
   * <p>
   * Asking it costs a connection and wakes the acquisition, because every committed engine
   * command does. So a measurement asks this BEFORE it starts looking and again after it
   * stopped, never in between.
   *
   * @return The due date in epoch milliseconds and how many workflows are still running,
   *         with an empty due date where the engine holds no job at all. A job the engine
   *         gave no due date is due now, and is reported as the start of the epoch rather
   *         than as nothing
   */
  @GET
  @Path("/engine/next-due-date")
  public Map<String, Object> nextDueDate() {

    final var dueJobs = engine()
        .getProcessEngine()
        .getManagementService()
        .createJobQuery()
        .active()
        .withRetriesLeft()
        .orderByJobDuedate()
        .asc()
        .listPage(0, 1);
    final var reported = new LinkedHashMap<String, Object>();
    final var dueDate = dueJobs.isEmpty()
        ? null
        : dueJobs
            .get(0)
            .getDuedate();
    reported
        .put(
            "dueAt",
            dueJobs.isEmpty()
                ? ""
                : Long.toString(dueDate == null ? 0L : dueDate.getTime()));
    reported
        .put(
            "runningWorkflows",
            Long.valueOf(runtimeService()
                .createProcessInstanceQuery()
                .count()));
    return reported;

  }

  // --- starting workflows ---

  /**
   * Starts the primary process through the VanillaBP user API and reports what was
   * visible while the caller's transaction was still open: the workflow is created
   * by the phase-two outbox, so nothing may exist yet.
   *
   * @param approved What the gateway of {@code TaskProcess} branches on
   * @return The aggregate's id and what phase one left behind
   */
  @POST
  @Path("/workflows/{approved}")
  public Map<String, Object> startWorkflow(
      @PathParam("approved") final boolean approved) throws Exception {

    final var reported = new LinkedHashMap<String, Object>();
    userTransaction.begin();
    try {
      final var aggregate = new C7E2eAggregate();
      aggregate.setApproved(approved);
      final var started = workflowService.startWorkflow(aggregate);
      reported.put("id", String.valueOf(started.getId()));
      reported.put("instancesInsideTransaction", instanceCount(started.getId()));
    } catch (final Exception e) {
      userTransaction.rollback();
      return failure(e);
    }
    userTransaction.commit();
    return reported;

  }

  /**
   * Starts a workflow and rolls the transaction back - neither the aggregate nor the
   * workflow may survive.
   *
   * @param approved What the gateway branches on
   * @return The aggregate's id
   */
  @POST
  @Path("/workflows/{approved}/rollback")
  public Map<String, Object> startWorkflowAndRollback(
      @PathParam("approved") final boolean approved) throws Exception {

    userTransaction.begin();
    final var aggregate = new C7E2eAggregate();
    aggregate.setApproved(approved);
    final var started = workflowService.startWorkflow(aggregate);
    final var id = String.valueOf(started.getId());
    userTransaction.rollback();
    return Map.of("id", id);

  }

  /**
   * Saves an aggregate and starts one of the workflow service's SECONDARY processes
   * against the engine: the injectable process service starts the primary process
   * only. The business key IS the aggregate's id, which is how VanillaBP finds it
   * again.
   *
   * @param bpmnProcessId The BPMN process to start
   * @param approved What the gateway branches on
   * @param items The multi-instance collection, comma-separated, or <code>null</code>
   * @return The aggregate's id
   */
  @POST
  @Path("/processes/{bpmnProcessId}/{approved}")
  @Transactional
  public Map<String, Object> startSecondaryProcess(
      @PathParam("bpmnProcessId") final String bpmnProcessId,
      @PathParam("approved") final boolean approved,
      @QueryParam("items") final String items) {

    final var aggregate = new C7E2eAggregate();
    aggregate.setApproved(approved);
    if (items != null) {
      aggregate.setItems(List.of(items.split(",")));
    }
    entityManager.persist(aggregate);
    entityManager.flush();
    runtimeService()
        .createProcessInstanceByKey(bpmnProcessId)
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregate.getId()))
        .execute();
    return Map.of("id", String.valueOf(aggregate.getId()));

  }

  /**
   * Starts a process the way a system past VanillaBP does it: through the engine's API,
   * with a business key of its own choosing. VanillaBP names a workflow and nobody else,
   * so the start is refused and the message comes back for the test to read.
   *
   * @param businessKey The key the starter chose
   * @return What the refusal said, or the key where the engine let the start through
   */
  @POST
  @Path("/started-past-vanillabp/{businessKey}")
  public Map<String, Object> startPastVanillaBp(
      @PathParam("businessKey") final String businessKey) {

    // no transaction of this method's own: the engine's command opens one, and a start
    // which fails must not take this response with it
    try {
      runtimeService()
          .createProcessInstanceByKey("TimerStartProcess")
          .processDefinitionTenantId(MODULE_ID)
          .businessKey(businessKey)
          .execute();
      return Map.of("businessKey", businessKey);
    } catch (final RuntimeException e) {
      final var message = new StringBuilder();
      Throwable cause = e;
      while (cause != null) {
        message.append(cause.getMessage());
        cause = cause.getCause();
      }
      return Map.of("refused", message.toString());
    }

  }

  /**
   * Starts the same process past VanillaBP WITHOUT a name. Nothing names this workflow,
   * so the application's <code>&#64;WorkflowStartedByBpms</code> method does, and the
   * engine holds that name as the business key from then on.
   *
   * @return Nothing but an acknowledgement - the name is the application's to give
   */
  @POST
  @Path("/started-past-vanillabp-unnamed")
  @Transactional
  public Map<String, Object> startPastVanillaBpWithoutAName() {

    runtimeService()
        .createProcessInstanceByKey("TimerStartProcess")
        .processDefinitionTenantId(MODULE_ID)
        .execute();
    return Map.of("started", Boolean.TRUE);

  }

  // --- what the application asks of VanillaBP ---

  @POST
  @Path("/tasks/{taskId}/complete/{aggregateId}")
  public Map<String, Object> completeTask(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final Long aggregateId) throws Exception {

    return inTransaction(aggregateId, aggregate -> workflowService.completeTask(aggregate, taskId), taskId, true);

  }

  @POST
  @Path("/tasks/{taskId}/complete-and-rollback/{aggregateId}")
  public Map<String, Object> completeTaskAndRollback(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final Long aggregateId) throws Exception {

    return inTransaction(aggregateId, aggregate -> workflowService.completeTask(aggregate, taskId), taskId, false);

  }

  @POST
  @Path("/tasks/{taskId}/cancel/{aggregateId}/{errorCode}")
  public Map<String, Object> cancelTask(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final Long aggregateId,
      @PathParam("errorCode") final String errorCode) throws Exception {

    return inTransaction(
        aggregateId,
        aggregate -> workflowService.cancelTask(aggregate, taskId, errorCode),
        taskId,
        true);

  }

  @POST
  @Path("/user-tasks/{taskId}/complete/{aggregateId}")
  public Map<String, Object> completeUserTask(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final Long aggregateId) throws Exception {

    return inTransaction(aggregateId, aggregate -> workflowService.completeUserTask(aggregate, taskId), null, true);

  }

  @POST
  @Path("/user-tasks/{taskId}/complete-and-rollback/{aggregateId}")
  public Map<String, Object> completeUserTaskAndRollback(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final Long aggregateId) throws Exception {

    return inTransaction(aggregateId, aggregate -> workflowService.completeUserTask(aggregate, taskId), null, false);

  }

  @POST
  @Path("/user-tasks/{taskId}/cancel/{aggregateId}/{errorCode}")
  public Map<String, Object> cancelUserTask(
      @PathParam("taskId") final String taskId,
      @PathParam("aggregateId") final Long aggregateId,
      @PathParam("errorCode") final String errorCode) throws Exception {

    return inTransaction(
        aggregateId,
        aggregate -> workflowService.cancelUserTask(aggregate, taskId, errorCode),
        null,
        true);

  }

  @POST
  @Path("/messages/{messageName}/correlate/{aggregateId}")
  public Map<String, Object> correlateMessage(
      @PathParam("messageName") final String messageName,
      @PathParam("aggregateId") final Long aggregateId) throws Exception {

    return inTransaction(aggregateId, aggregate -> workflowService.correlateMessage(aggregate, messageName), null,
        true);

  }

  @POST
  @Path("/messages/{messageName}/correlate/{aggregateId}/{correlationId}")
  public Map<String, Object> correlateMessage(
      @PathParam("messageName") final String messageName,
      @PathParam("aggregateId") final Long aggregateId,
      @PathParam("correlationId") final String correlationId) throws Exception {

    return inTransaction(
        aggregateId,
        aggregate -> workflowService.correlateMessage(aggregate, messageName, correlationId),
        null,
        true);

  }

  @POST
  @Path("/messages/{messageName}/correlate-and-rollback/{aggregateId}")
  public Map<String, Object> correlateMessageAndRollback(
      @PathParam("messageName") final String messageName,
      @PathParam("aggregateId") final Long aggregateId) throws Exception {

    return inTransaction(aggregateId, aggregate -> workflowService.correlateMessage(aggregate, messageName), null,
        false);

  }

  @POST
  @Path("/messages/{messageName}/start")
  @Transactional
  public Map<String, Object> startWorkflowByMessage(
      @PathParam("messageName") final String messageName) {

    final var aggregate = new C7E2eAggregate();
    entityManager.persist(aggregate);
    entityManager.flush();
    workflowService.startWorkflowByMessage(aggregate, messageName);
    return Map.of("id", String.valueOf(aggregate.getId()));

  }

  /**
   * Starts the workflow of an existing aggregate by a message, and reports a refusal
   * instead of failing the request.
   *
   * @param messageName The message which is to start the workflow
   * @param aggregateId The aggregate whose workflow is to be started
   * @return Nothing where the start was accepted, otherwise the exception raised
   * @throws Exception If the transaction cannot be handled
   */
  @POST
  @Path("/messages/{messageName}/start/{aggregateId}")
  public Map<String, Object> startWorkflowByMessage(
      @PathParam("messageName") final String messageName,
      @PathParam("aggregateId") final Long aggregateId) throws Exception {

    return inTransaction(
        aggregateId,
        aggregate -> workflowService.startWorkflowByMessage(aggregate, messageName),
        null,
        true);

  }

  @POST
  @Path("/signals/{signalName}")
  @Transactional
  public void sendSignal(
      @PathParam("signalName") final String signalName) {

    workflowService.sendSignal(signalName);

  }

  /**
   * Broadcasts a signal and rolls the transaction back - the embedded engine shares
   * that transaction, so nothing may have happened.
   *
   * @param signalName The signal's name
   */
  @POST
  @Path("/signals/{signalName}/rollback")
  public void sendSignalAndRollback(
      @PathParam("signalName") final String signalName) throws Exception {

    userTransaction.begin();
    workflowService.sendSignal(signalName);
    userTransaction.rollback();

  }

  // --- pushing a changed aggregate ---

  @POST
  @Path("/push/workflows")
  @Transactional
  public Map<String, Object> startPushWorkflow() {

    final var started = pushWorkflowService.startWorkflow(new C7PushAggregate());
    return Map.of("id", String.valueOf(started.getId()));

  }

  @POST
  @Path("/push/processes/{bpmnProcessId}")
  @Transactional
  public Map<String, Object> startPushSecondaryProcess(
      @PathParam("bpmnProcessId") final String bpmnProcessId) {

    final var aggregate = new C7PushAggregate();
    entityManager.persist(aggregate);
    entityManager.flush();
    runtimeService()
        .createProcessInstanceByKey(bpmnProcessId)
        .processDefinitionTenantId(MODULE_ID)
        .businessKey(String.valueOf(aggregate.getId()))
        .execute();
    return Map.of("id", String.valueOf(aggregate.getId()));

  }

  @POST
  @Path("/push/{aggregateId}/ready")
  @Transactional
  public void becomeReady(
      @PathParam("aggregateId") final Long aggregateId) {

    final var aggregate = entityManager.find(C7PushAggregate.class, aggregateId);
    aggregate.setReadyToGo(true);
    pushWorkflowService.aggregateChanged(aggregate);

  }

  @POST
  @Path("/push/{aggregateId}/escalate")
  @Transactional
  public void pushGlobally(
      @PathParam("aggregateId") final Long aggregateId) {

    final var aggregate = entityManager.find(C7PushAggregate.class, aggregateId);
    aggregate.setEscalate(true);
    pushWorkflowService.aggregateChanged(aggregate);

  }

  @POST
  @Path("/push/{aggregateId}/escalate/{taskId}")
  @Transactional
  public void pushAtTask(
      @PathParam("aggregateId") final Long aggregateId,
      @PathParam("taskId") final String taskId) {

    final var aggregate = entityManager.find(C7PushAggregate.class, aggregateId);
    aggregate.setEscalate(true);
    pushWorkflowService.aggregateChanged(aggregate, taskId);

  }

  // --- what the engine holds ---

  /**
   * What the boot deployed, as "processDefinitionKey|tenantId|version|versionTag".
   *
   * @return One entry per deployed process definition
   */
  @GET
  @Path("/engine/definitions")
  public List<String> definitions() {

    return engine()
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .list()
        .stream()
        .map(definition -> "%s|%s|%d|%s".formatted(
            definition.getKey(),
            definition.getTenantId(),
            definition.getVersion(),
            definition.getVersionTag()))
        .sorted()
        .toList();

  }

  @GET
  @Path("/engine/instances/{aggregateId}")
  @Produces(MediaType.TEXT_PLAIN)
  public long instances(
      @PathParam("aggregateId") final String aggregateId) {

    return instanceCount(aggregateId);

  }

  @GET
  @Path("/engine/ended/{aggregateId}")
  @Produces(MediaType.TEXT_PLAIN)
  public boolean ended(
      @PathParam("aggregateId") final String aggregateId) {

    return engine()
        .getHistoryService()
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(aggregateId)
        .finished()
        .count() > 0;

  }

  @GET
  @Path("/engine/executions/{executionId}")
  @Produces(MediaType.TEXT_PLAIN)
  public boolean executionExists(
      @PathParam("executionId") final String executionId) {

    return runtimeService()
        .createExecutionQuery()
        .executionId(executionId)
        .count() > 0;

  }

  /**
   * The local variables of one execution - where a pushed aggregate landed is read
   * from exactly this.
   *
   * @param executionId The execution
   * @return The names of its local variables
   */
  @GET
  @Path("/engine/local-variables/{executionId}")
  public Map<String, Object> localVariables(
      @PathParam("executionId") final String executionId) {

    final var reported = new LinkedHashMap<String, Object>();
    runtimeService()
        .getVariablesLocal(executionId)
        .forEach((
            name,
            value) -> reported.put(name, String.valueOf(value)));
    return reported;

  }

  @GET
  @Path("/engine/instance-id/{aggregateId}")
  @Produces(MediaType.TEXT_PLAIN)
  public String instanceId(
      @PathParam("aggregateId") final String aggregateId) {

    final var instance = runtimeService()
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(aggregateId)
        .singleResult();
    return instance == null
        ? ""
        : instance.getId();

  }

  /**
   * The lowest retry count of the jobs of a workflow - a technical exception in a
   * handler is visible here and nowhere else.
   *
   * @param aggregateId The aggregate's id (the business key)
   * @return The lowest retry count, or -1 if the workflow has no jobs
   */
  @GET
  @Path("/engine/retries/{aggregateId}")
  @Produces(MediaType.TEXT_PLAIN)
  public int retries(
      @PathParam("aggregateId") final String aggregateId) {

    final var instance = runtimeService()
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(aggregateId)
        .singleResult();
    if (instance == null) {
      return -1;
    }
    return engine()
        .getProcessEngine()
        .getManagementService()
        .createJobQuery()
        .processInstanceId(instance.getId())
        .list()
        .stream()
        .mapToInt(job -> job.getRetries())
        .min()
        .orElse(-1);

  }

  /**
   * The message of the job which failed - a technical exception of a handler ends up
   * here, and VanillaBP's guiding message with it.
   *
   * @param aggregateId The aggregate's id (the business key)
   * @return The job's exception message, or an empty string
   */
  @GET
  @Path("/engine/job-failure/{aggregateId}")
  @Produces(MediaType.TEXT_PLAIN)
  public String jobFailure(
      @PathParam("aggregateId") final String aggregateId) {

    final var instance = runtimeService()
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(aggregateId)
        .singleResult();
    if (instance == null) {
      return "";
    }
    return engine()
        .getProcessEngine()
        .getManagementService()
        .createJobQuery()
        .processInstanceId(instance.getId())
        .list()
        .stream()
        .map(job -> job.getExceptionMessage())
        .filter(message -> message != null)
        .findFirst()
        .orElse("");

  }

  /**
   * The execution of a workflow waiting for a message.
   *
   * @param aggregateId The aggregate's id (the business key)
   * @param messageName The message the execution waits for
   * @return The execution's id, or an empty string
   */
  @GET
  @Path("/engine/message-executions/{aggregateId}/{messageName}")
  @Produces(MediaType.TEXT_PLAIN)
  public String messageExecution(
      @PathParam("aggregateId") final String aggregateId,
      @PathParam("messageName") final String messageName) {

    final var execution = runtimeService()
        .createExecutionQuery()
        .messageEventSubscriptionName(messageName)
        .processInstanceBusinessKey(aggregateId)
        .singleResult();
    return execution == null
        ? ""
        : execution.getId();

  }

  /**
   * Writes the correlation id a waiting subscription expects, following VanillaBP's
   * local-variable convention. The convention uses the PRIMARY BPMN process id of the
   * process service, which is what makes this endpoint worth having: the test would
   * otherwise have to reimplement the naming.
   *
   * @param executionId The waiting execution
   * @param messageName The message
   * @param correlationId What the subscription expects
   */
  @POST
  @Path("/engine/message-executions/{executionId}/correlation-id/{messageName}/{correlationId}")
  @Transactional
  public void expectCorrelationId(
      @PathParam("executionId") final String executionId,
      @PathParam("messageName") final String messageName,
      @PathParam("correlationId") final String correlationId) {

    runtimeService()
        .setVariableLocal(
            executionId,
            io.vanillabp.camunda7.processservice.Camunda7ProcessService
                .correlationIdVariableName("TaskProcess", messageName),
            correlationId);

  }

  /**
   * Saves an aggregate WITHOUT starting a workflow - what an operation addressing a
   * workflow nobody started has to answer about.
   *
   * @return The aggregate's id
   */
  @POST
  @Path("/aggregates")
  @Transactional
  public Map<String, Object> seedAggregate() {

    final var aggregate = new C7E2eAggregate();
    entityManager.persist(aggregate);
    entityManager.flush();
    return Map.of("id", String.valueOf(aggregate.getId()));

  }

  @GET
  @Path("/engine/user-tasks/{aggregateId}")
  public List<String> userTasks(
      @PathParam("aggregateId") final String aggregateId) {

    return engine()
        .getTaskService()
        .createTaskQuery()
        .processInstanceBusinessKey(aggregateId)
        .list()
        .stream()
        .map(task -> task.getId())
        .toList();

  }

  /**
   * Completes a user task the way an operator's UI would - the path a user task
   * WITHOUT a <code>&#64;WorkflowTask</code> method has to keep working on.
   *
   * @param taskId The engine's task id
   */
  @POST
  @Path("/engine/user-tasks/{taskId}/complete")
  @Transactional
  public void completeUserTaskInTheEngine(
      @PathParam("taskId") final String taskId) {

    engine()
        .getTaskService()
        .complete(taskId);

  }

  @POST
  @Path("/engine/instances/{aggregateId}/terminate")
  @Transactional
  public void terminate(
      @PathParam("aggregateId") final String aggregateId) {

    final var instance = runtimeService()
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(aggregateId)
        .singleResult();
    runtimeService().deleteProcessInstance(instance.getId(), "terminated by the test");

  }

  /**
   * Deploys the second version of {@code VersionedProcess} while the application runs
   * - the way another node of a rolling deployment would.
   */
  @POST
  @Path("/engine/deploy-version-two")
  public void deployVersionTwo() {

    engine()
        .getRepositoryService()
        .createDeployment()
        .name(MODULE_ID)
        .tenantId(MODULE_ID)
        .addClasspathResource("c7-e2e/versioned/versioned-process-v2.bpmn")
        .deploy();

  }

  /**
   * The asynchronous continuations the parse listener forced onto the activities of a
   * process, as "activityId|asyncBefore|asyncAfter".
   *
   * @param bpmnProcessId The BPMN process
   * @return One entry per activity
   */
  @GET
  @Path("/engine/async-flags/{bpmnProcessId}")
  public List<String> asyncFlags(
      @PathParam("bpmnProcessId") final String bpmnProcessId) {

    final var definition = engine()
        .getRepositoryService()
        .createProcessDefinitionQuery()
        .processDefinitionKey(bpmnProcessId)
        .tenantIdIn(MODULE_ID)
        .latestVersion()
        .singleResult();
    final var parsed = (ProcessDefinitionEntity) engine()
        .getRepositoryService()
        .getProcessDefinition(definition.getId());
    return parsed
        .getActivities()
        .stream()
        .map(activity -> "%s|%s|%s".formatted(
            activity.getId(),
            activity.isAsyncBefore(),
            activity.isAsyncAfter()))
        .sorted()
        .toList();

  }

  // --- plumbing ---

  private org.camunda.bpm.engine.RuntimeService runtimeService() {

    return engine().getRuntimeService();

  }

  private io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineHolder engine() {

    return engineRegistry.engineFor(ADAPTER_ID);

  }

  private long instanceCount(
      final Object aggregateId) {

    return runtimeService()
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(aggregateId))
        .count();

  }

  /**
   * Runs one {@code ProcessService} call in a transaction of its own and reports what
   * was visible while it was still open - the engine may not have been touched at
   * that point.
   *
   * @param aggregateId The aggregate the operation works on
   * @param operation What to call
   * @param parkedExecutionId The execution the operation resumes, or
   *          <code>null</code>
   * @param commit Whether to commit or to roll back
   * @return What was visible inside the transaction, or the exception raised
   */
  private Map<String, Object> inTransaction(
      final Long aggregateId,
      final Consumer<C7E2eAggregate> operation,
      final String parkedExecutionId,
      final boolean commit) throws Exception {

    final var reported = new LinkedHashMap<String, Object>();
    userTransaction.begin();
    try {
      operation.accept(entityManager.find(C7E2eAggregate.class, aggregateId));
      if (parkedExecutionId != null) {
        reported.put("parkedInsideTransaction", executionExists(parkedExecutionId));
      }
    } catch (final Exception e) {
      userTransaction.rollback();
      return failure(e);
    }
    if (commit) {
      userTransaction.commit();
    } else {
      userTransaction.rollback();
    }
    return reported;

  }

  private Map<String, Object> failure(
      final Exception e) {

    var cause = (Throwable) e;
    while ((cause.getCause() != null) && (cause.getCause() != cause)) {
      cause = cause.getCause();
    }
    return Map
        .of(
            "exception",
            e
                .getClass()
                .getSimpleName(),
            "message",
            String.valueOf(e.getMessage()),
            "rootException",
            cause
                .getClass()
                .getSimpleName(),
            "rootMessage",
            String.valueOf(cause.getMessage()));

  }

}
