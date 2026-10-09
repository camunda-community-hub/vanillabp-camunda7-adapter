package io.vanillabp.camunda7.processservice;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.RuntimeService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.model.bpmn.instance.CallActivity;

import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.WorkflowElementHistory;
import io.vanillabp.spi.process.WorkflowElementType;
import io.vanillabp.spi.process.WorkflowHistory;
import lombok.extern.slf4j.Slf4j;

/**
 * The Camunda 7 part of VanillaBP's viewer/history API: process
 * definitions, BPMN XML and the execution history of a workflow.
 * <p>
 * Camunda 7 is embedded and authoritative for both: the
 * {@link RepositoryService} holds every deployed version including its BPMN XML,
 * the {@link HistoryService} the instance timeline. Both are cheap local queries -
 * unlike remote BPMS there is neither eventual consistency nor a
 * &quot;deployed by a previous application version&quot; boundary.
 * <p>
 * Addressing follows the adapter's conventions: the workflow-aggregate ID is the
 * BUSINESS KEY, the workflow module ID the TENANT ID.
 * <p>
 * The <b>history context</b> of this adapter is the (historic) process instance ID
 * of a call activity's called instance - stable, and resolvable to the exact
 * version that instance ran on.
 * <p>
 * The definition ids returned here are ADAPTER-NATIVE (Camunda's process
 * definition ids like {@code demo:1:8a9c...}); the core namespaces them with the
 * adapter id.
 */
@Slf4j
public class Camunda7WorkflowViewer {

  private final String adapterId;

  private final RepositoryService repositoryService;

  private final HistoryService historyService;

  private final RuntimeService runtimeService;

  /**
   * Reads one engine. Everything is asked when somebody asks for it, so building the
   * viewer costs nothing.
   *
   * @param adapterId The adapter id the definition ids are namespaced with
   * @param repositoryService The engine's repository, which holds the definitions
   * @param historyService The engine's history, which is where a finished workflow is read
   * @param runtimeService The engine's runtime, which is where a running one is read
   */
  public Camunda7WorkflowViewer(
      final String adapterId,
      final RepositoryService repositoryService,
      final HistoryService historyService,
      final RuntimeService runtimeService) {

    this.adapterId = adapterId;
    this.repositoryService = repositoryService;
    this.historyService = historyService;
    this.runtimeService = runtimeService;

  }

  /**
   * The process definitions of the addressed (sub-)workflow: the definition the
   * instance runs/ran on first (its {@code usedByElements} is <code>null</code>),
   * followed by the definitions its call activities called ({@code usedByElements}
   * naming the call-activity elements). A call activity which called nothing yet reports
   * the version it WOULD call next, the latest deployed one.
   *
   * @param workflowModuleId The workflow module the workflow belongs to. The tenant is a
   *          parameter of its own: a module has one only under {@code by-adapter}
   * @param bpmnProcessId The BPMN process ID of the primary process
   * @param tenantId The Camunda tenant the module is deployed under, or
   *          <code>null</code> where this application uses none
   * @param workflowAggregateId The workflow aggregate ID (the business key)
   * @param historyContext <code>null</code> or a called instance's ID
   * @return The definitions or an EMPTY list if the workflow is unknown here
   */
  public List<ProcessDefinition> getProcessDefinitions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String tenantId,
      final Object workflowAggregateId,
      final String historyContext) {

    final var addressed = resolveAddressedInstance(
        workflowModuleId, bpmnProcessId, tenantId, workflowAggregateId, historyContext);
    if (addressed == null) {
      return List.of();
    }
    final var processDefinitionId = addressed.processDefinitionId();

    final var processDefinition = repositoryService
        .getProcessDefinition(processDefinitionId);

    final var definitions = new ArrayList<ProcessDefinition>();
    definitions.add(
        new ProcessDefinition(
            processDefinition.getId(), processDefinition.getKey(), String
                .valueOf(processDefinition.getVersion()), null));
    definitions.addAll(
        calledDefinitions(workflowModuleId, tenantId, processDefinitionId, addressed));
    return definitions;

  }

  /**
   * The BPMN XML of a Camunda 7 process definition.
   *
   * @param processDefinitionId The Camunda process definition ID
   * @return The XML or <code>null</code> if the engine does not know the definition
   */
  public InputStream getBpmnXml(
      final String processDefinitionId) {

    try {
      return repositoryService
          .getProcessModel(processDefinitionId);
    } catch (final ProcessEngineException e) {
      log.debug(
          "Camunda7[{}]: no BPMN XML for process definition '{}'",
          adapterId,
          processDefinitionId,
          e);
      return null;
    }

  }

  /**
   * The execution history of the addressed (sub-)workflow.
   *
   * @param workflowModuleId The workflow module the workflow belongs to. The tenant is a
   *          parameter of its own: a module has one only under {@code by-adapter}
   * @param bpmnProcessId The BPMN process ID of the primary process
   * @param tenantId The Camunda tenant the module is deployed under, or
   *          <code>null</code> where this application uses none
   * @param workflowAggregateId The workflow aggregate ID (the business key)
   * @param historyContext <code>null</code> or a called instance's ID
   * @return The history or <code>null</code> if the workflow is unknown here
   */
  public WorkflowHistory getWorkflowHistory(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String tenantId,
      final Object workflowAggregateId,
      final String historyContext) {

    final var historicInstance = resolveHistoricInstance(
        workflowModuleId, bpmnProcessId, tenantId, workflowAggregateId, historyContext);
    if (historicInstance == null) {
      // no history at all: either the engine runs with history level NONE or the
      // instance's history was already cleaned up (history-time-to-live). The
      // running instance still allows reporting the definition it runs on - the
      // element history is unavailable, which the SPI expresses as null.
      final var addressed = resolveAddressedInstance(
          workflowModuleId, bpmnProcessId, tenantId, workflowAggregateId, historyContext);
      if (addressed == null) {
        return null;
      }
      log.debug(
          "Camunda7[{}]: no history data for the workflow of aggregate '{}' - reporting the "
              + "definition without an element history (history level NONE or history cleaned up)",
          adapterId,
          workflowAggregateId);
      return new WorkflowHistory(addressed.processDefinitionId(), null, null, null);
    }

    final var activities = historyService
        .createHistoricActivityInstanceQuery()
        .processInstanceId(historicInstance.getId())
        .orderPartiallyByOccurrence()
        .asc()
        .list();

    final var openIncidentMessages = openIncidentMessagesByActivity(historicInstance.getId());

    final var elements = activities
        .stream()
        .map(activity -> new WorkflowElementHistory(
            toOffsetDateTime(activity.getStartTime()), toOffsetDateTime(activity.getEndTime()), activity
                .getActivityId(), elementTypeOf(activity.getActivityType()), openIncidentMessages
                    .get(activity.getActivityId()), activity.isCanceled(), activity.getCalledProcessInstanceId()))
        .toList();

    return new WorkflowHistory(
        historicInstance.getProcessDefinitionId(), toOffsetDateTime(historicInstance.getStartTime()), toOffsetDateTime(
            historicInstance.getEndTime()), elements);

  }

  /**
   * The process instance a viewer call addresses, and the definition it runs or ran on.
   *
   * @param processInstanceId The process instance
   * @param processDefinitionId The definition it runs or ran on
   * @param fromHistory Whether the history answered, rather than what is running
   */
  private record AddressedInstance(
                                   String processInstanceId,
                                   String processDefinitionId,
                                   boolean fromHistory) {
  }

  /**
   * Resolves the (sub-)workflow addressed and the process definition it runs/ran on -
   * from history if available, otherwise from the runtime state (history level
   * NONE).
   * <p>
   * The runtime state follows the rule of the history: a called instance is accepted only
   * where it belongs to the workflow asked about, which is where its root is that workflow's
   * instance. Without that check a history level of NONE would show a called instance of any
   * other workflow to whoever passes its id. See decision 45 in the repository's DECISIONS.md.
   *
   * @return The instance or <code>null</code> if this engine does not know the workflow
   */
  private AddressedInstance resolveAddressedInstance(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String tenantId,
      final Object workflowAggregateId,
      final String historyContext) {

    final var historicInstance = resolveHistoricInstance(
        workflowModuleId, bpmnProcessId, tenantId, workflowAggregateId, historyContext);
    if (historicInstance != null) {
      return new AddressedInstance(historicInstance.getId(), historicInstance.getProcessDefinitionId(), true);
    }

    // no history: the runtime state answers for RUNNING instances only
    var runningQuery = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(workflowAggregateId))
        .processDefinitionKey(bpmnProcessId);
    // a module without a tenant is the DEFAULT ('name-clash-avoidance: none'), and
    // Camunda rejects a null tenant id instead of ignoring it
    runningQuery = tenantId != null
        ? runningQuery.tenantIdIn(tenantId)
        : runningQuery.withoutTenantId();
    final var instance = runningQuery.singleResult();
    if (instance == null) {
      return null;
    }
    if (historyContext == null) {
      return new AddressedInstance(instance.getId(), instance.getProcessDefinitionId(), false);
    }
    final var calledInstance = runtimeService
        .createProcessInstanceQuery()
        .processInstanceId(historyContext)
        .singleResult();
    if (calledInstance == null) {
      return null;
    }
    final var rootInstanceId = calledInstance.getRootProcessInstanceId() == null
        ? calledInstance.getId()
        : calledInstance.getRootProcessInstanceId();
    if (!rootInstanceId.equals(instance.getId())) {
      reportAForeignHistoryContext(workflowModuleId, bpmnProcessId, tenantId, workflowAggregateId, historyContext);
      return null;
    }
    return new AddressedInstance(calledInstance.getId(), calledInstance.getProcessDefinitionId(), false);

  }

  /**
   * Resolves the historic process instance addressed: the workflow's primary
   * instance (business key + process + tenant) or, for a history context, the
   * called instance - which is accepted ONLY if it belongs to that very workflow
   * (a context of a foreign workflow must not leak its history).
   */
  private HistoricProcessInstance resolveHistoricInstance(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String tenantId,
      final Object workflowAggregateId,
      final String historyContext) {

    var historicQuery = historyService
        .createHistoricProcessInstanceQuery()
        .processInstanceBusinessKey(String.valueOf(workflowAggregateId))
        .processDefinitionKey(bpmnProcessId);
    historicQuery = tenantId != null
        ? historicQuery.tenantIdIn(tenantId)
        : historicQuery.withoutTenantId();
    final var primaryInstance = historicQuery
        .orderByProcessInstanceStartTime()
        .desc()
        .list()
        .stream()
        .findFirst()
        .orElse(null);
    if (historyContext == null) {
      return primaryInstance;
    }
    if (primaryInstance == null) {
      return null;
    }

    final var calledInstance = historyService
        .createHistoricProcessInstanceQuery()
        .processInstanceId(historyContext)
        .singleResult();
    if (calledInstance == null) {
      return null;
    }
    // the walk to the root, and the rule that an instance nobody called is its own root,
    // are stated once - see io.vanillabp.camunda7.api.Camunda7Executions
    final var rootInstanceId = io.vanillabp.camunda7.api.Camunda7Executions
        .rootProcessInstanceIdOf(calledInstance);
    if (!rootInstanceId.equals(primaryInstance.getId())) {
      reportAForeignHistoryContext(workflowModuleId, bpmnProcessId, tenantId, workflowAggregateId, historyContext);
      return null;
    }
    return calledInstance;

  }

  private void reportAForeignHistoryContext(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String tenantId,
      final Object workflowAggregateId,
      final String historyContext) {

    log.warn(
        "Camunda7[{}]: the history context '{}' does not belong to the workflow of aggregate "
            + "'{}' (BPMN process '{}' of workflow module '{}', tenant '{}') - ignoring it",
        adapterId,
        historyContext,
        workflowAggregateId,
        bpmnProcessId,
        workflowModuleId,
        tenantId != null
            ? tenantId
            : "<none>");

  }

  /**
   * The definitions called by the call activities of the given definition. A call activity
   * which called a process already reports the definition that called instance runs or ran
   * on, because that is the model a viewer has to draw for this workflow. One which has not
   * called anything yet reports the version which WOULD be executed next, the latest deployed
   * one. Where calls of one call activity ran on different versions, the most recent call
   * counts. See decision 45 in the repository's DECISIONS.md.
   * <p>
   * Call activities addressing their process by an expression are skipped: which definition
   * they call is only known at execution time.
   */
  private List<ProcessDefinition> calledDefinitions(
      final String workflowModuleId,
      final String tenantId,
      final String processDefinitionId,
      final AddressedInstance addressed) {

    final var model = repositoryService
        .getBpmnModelInstance(processDefinitionId);

    // keep the modeling order and group the elements calling the same process
    final var elementsByCalledProcess = new LinkedHashMap<String, List<String>>();
    for (final var callActivity : model.getModelElementsByType(CallActivity.class)) {
      final var calledElement = callActivity.getCalledElement();
      if ((calledElement == null) || calledElement.isBlank()) {
        continue;
      }
      if (calledElement.contains("${") || calledElement.contains("#{")) {
        log.debug(
            "Camunda7[{}]: the call activity '{}' of process definition '{}' addresses its process "
                + "by the expression '{}' - the called definition is only known at execution time "
                + "and therefore not reported",
            adapterId,
            callActivity.getId(),
            processDefinitionId,
            calledElement);
        continue;
      }
      elementsByCalledProcess
          .computeIfAbsent(calledElement, key -> new ArrayList<>())
          .add(callActivity.getId());
    }

    final var ranDefinitionIds = definitionsTheCallActivitiesRan(addressed);
    final var definitions = new ArrayList<ProcessDefinition>();
    elementsByCalledProcess.forEach((
        calledProcessId,
        elementIds) -> {
      // keep the modelling order, and group the elements calling the same definition
      final var elementsByRanDefinition = new LinkedHashMap<String, List<String>>();
      final var elementsWhichDidNotCall = new ArrayList<String>();
      elementIds.forEach(elementId -> {
        final var ranDefinitionId = ranDefinitionIds.get(elementId);
        if (ranDefinitionId == null) {
          elementsWhichDidNotCall.add(elementId);
        } else {
          elementsByRanDefinition
              .computeIfAbsent(ranDefinitionId, key -> new ArrayList<>())
              .add(elementId);
        }
      });
      elementsByRanDefinition.forEach((
          ranDefinitionId,
          elementsWhichCalled) -> {
        final var ran = repositoryService.getProcessDefinition(ranDefinitionId);
        definitions.add(
            new ProcessDefinition(
                ran.getId(), ran.getKey(), String.valueOf(ran.getVersion()), List.copyOf(elementsWhichCalled)));
      });
      if (elementsWhichDidNotCall.isEmpty()) {
        return;
      }
      var definitionQuery = repositoryService
          .createProcessDefinitionQuery()
          .processDefinitionKey(calledProcessId);
      definitionQuery = tenantId != null
          ? definitionQuery.tenantIdIn(tenantId)
          : definitionQuery.withoutTenantId();
      final var latest = definitionQuery
          .latestVersion()
          .singleResult();
      if (latest == null) {
        log.debug(
            "Camunda7[{}]: no deployed definition of the called process '{}' of workflow module "
                + "'{}' (tenant '{}') - the call activities {} are not reported",
            adapterId,
            calledProcessId,
            workflowModuleId,
            tenantId != null
                ? tenantId
                : "<none>",
            elementsWhichDidNotCall);
        return;
      }
      definitions.add(
          new ProcessDefinition(
              latest.getId(), latest.getKey(), String.valueOf(latest.getVersion()), List.copyOf(
                  elementsWhichDidNotCall)));
    });
    return definitions;

  }

  /**
   * The definition each call activity of an instance called, by the id of the call activity.
   * The history answers where there is one. Without it the called instances which still run
   * answer, each pointing back at the execution which called it.
   *
   * @param addressed The instance whose call activities are meant
   * @return The definition id of the most recent call per call activity
   */
  private java.util.Map<String, String> definitionsTheCallActivitiesRan(
      final AddressedInstance addressed) {

    final var ranDefinitionIds = new java.util.HashMap<String, String>();
    if (addressed.fromHistory()) {
      historyService
          .createHistoricActivityInstanceQuery()
          .processInstanceId(addressed.processInstanceId())
          .activityType(org.camunda.bpm.engine.ActivityTypes.CALL_ACTIVITY)
          .orderByHistoricActivityInstanceStartTime()
          .asc()
          .list()
          .stream()
          .filter(call -> call.getCalledProcessInstanceId() != null)
          .forEach(call -> {
            final var calledInstance = historyService
                .createHistoricProcessInstanceQuery()
                .processInstanceId(call.getCalledProcessInstanceId())
                .singleResult();
            if (calledInstance != null) {
              ranDefinitionIds.put(call.getActivityId(), calledInstance.getProcessDefinitionId());
            }
          });
      return ranDefinitionIds;
    }
    runtimeService
        .createProcessInstanceQuery()
        .superProcessInstanceId(addressed.processInstanceId())
        .list()
        .stream()
        .filter(org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity.class::isInstance)
        .map(org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity.class::cast)
        .forEach(calledInstance -> {
          final var callingExecution = runtimeService
              .createExecutionQuery()
              .executionId(calledInstance.getSuperExecutionId())
              .singleResult();
          if (callingExecution instanceof org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity calling) {
            ranDefinitionIds.put(calling.getActivityId(), calledInstance.getProcessDefinitionId());
          }
        });
    return ranDefinitionIds;

  }

  /**
   * The messages of the OPEN incidents per activity - the SPI's per-element
   * {@code error} attribute ("the element is currently in error").
   */
  private java.util.Map<String, String> openIncidentMessagesByActivity(
      final String processInstanceId) {

    final var messages = new java.util.HashMap<String, String>();
    historyService
        .createHistoricIncidentQuery()
        .processInstanceId(processInstanceId)
        .open()
        .list()
        .forEach(incident -> messages
            .putIfAbsent(incident.getActivityId(), incident.getIncidentMessage()));
    return messages;

  }

  private static OffsetDateTime toOffsetDateTime(
      final Date date) {

    return date == null
        ? null
        : date
            .toInstant()
            .atZone(ZoneId.systemDefault())
            .toOffsetDateTime();

  }

  /**
   * Maps Camunda 7's activity type strings onto the SPI's element types. Camunda
   * uses fine-grained names (e.g. {@code messageEndEvent},
   * {@code boundaryTimer}, {@code intermediateMessageCatch}) which the SPI groups
   * into BPMN element categories.
   *
   * @param activityType What Camunda calls the element, or <code>null</code> where it
   *          reports none
   * @return The SPI's element type, {@code UNKNOWN} for a name this mapping has no
   *         category for
   */
  public static WorkflowElementType elementTypeOf(
      final String activityType) {

    if (activityType == null) {
      return WorkflowElementType.UNKNOWN;
    }
    final var type = activityType.toLowerCase();
    if (type.startsWith("boundary")) {
      return WorkflowElementType.BOUNDARY_EVENT;
    }
    if (type.endsWith("startevent") || type.startsWith("start")) {
      return WorkflowElementType.START_EVENT;
    }
    if (type.endsWith("endevent") || type.startsWith("end")) {
      return WorkflowElementType.END_EVENT;
    }
    if (type.startsWith("intermediate")) {
      return type.contains("throw")
          ? WorkflowElementType.INTERMEDIATE_THROW_EVENT
          : WorkflowElementType.INTERMEDIATE_CATCH_EVENT;
    }
    return switch (type) {
      case "servicetask" -> WorkflowElementType.SERVICE_TASK;
      case "usertask" -> WorkflowElementType.USER_TASK;
      case "sendtask" -> WorkflowElementType.SEND_TASK;
      case "receivetask" -> WorkflowElementType.RECEIVE_TASK;
      case "businessruletask" -> WorkflowElementType.BUSINESS_RULE_TASK;
      case "scripttask" -> WorkflowElementType.SCRIPT_TASK;
      case "manualtask" -> WorkflowElementType.MANUAL_TASK;
      case "task" -> WorkflowElementType.TASK;
      case "callactivity" -> WorkflowElementType.CALL_ACTIVITY;
      case "multiinstancebody" -> WorkflowElementType.MULTI_INSTANCE;
      case "exclusivegateway" -> WorkflowElementType.EXCLUSIVE_GATEWAY;
      case "inclusivegateway" -> WorkflowElementType.INCLUSIVE_GATEWAY;
      case "parallelgateway" -> WorkflowElementType.PARALLEL_GATEWAY;
      case "eventbasedgateway" -> WorkflowElementType.EVENT_BASED_GATEWAY;
      case "eventsubprocess" -> WorkflowElementType.EVENT_SUB_PROCESS;
      case "adhocsubprocess" -> WorkflowElementType.AD_HOC_SUB_PROCESS;
      case "subprocess" -> WorkflowElementType.SUB_PROCESS;
      case "transaction" -> WorkflowElementType.TRANSACTION;
      case "processdefinition", "process" -> WorkflowElementType.PROCESS;
      case "sequenceflow" -> WorkflowElementType.SEQUENCE_FLOW;
      default -> WorkflowElementType.UNKNOWN;
    };

  }

}
