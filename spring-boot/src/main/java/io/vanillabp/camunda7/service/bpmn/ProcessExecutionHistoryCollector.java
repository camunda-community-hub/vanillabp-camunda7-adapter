package io.vanillabp.camunda7.service.bpmn;

import io.vanillabp.spi.process.WorkflowElementHistory;
import io.vanillabp.spi.process.WorkflowElementType;
import io.vanillabp.spi.process.WorkflowHistory;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.camunda.bpm.engine.ActivityTypes;
import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.history.HistoricActivityInstance;
import org.camunda.bpm.engine.history.HistoricIncident;
import org.camunda.bpm.engine.history.HistoricProcessInstance;

public class ProcessExecutionHistoryCollector {

    private final HistoryService historyService;

    public ProcessExecutionHistoryCollector(ProcessEngine processEngine) {
        this.historyService = processEngine.getHistoryService();
    }

    public WorkflowHistory collectHistory(String processInstanceId,
                                          String tenantId) {

        HistoricProcessInstance hpi = loadProcessInstance(
                processInstanceId, tenantId);

        List<HistoricActivityInstance> activities = loadActivities(
                processInstanceId, tenantId);

        Map<String, String> errorByActivityInstanceId =
                collectErrorMessages(processInstanceId, tenantId);

        Map<String, String> callActivityChildMap =
                buildCallActivityChildMap(processInstanceId, tenantId);

        List<WorkflowElementHistory> elements = activities.stream()
                .map(activity -> toElementHistory(
                        activity,
                        errorByActivityInstanceId,
                        callActivityChildMap))
                .collect(Collectors.toList());

        return new WorkflowHistory(
                hpi.getProcessDefinitionId(),
                toOffsetDateTime(hpi.getStartTime()),
                toOffsetDateTime(hpi.getEndTime()),
                elements
        );
    }

    private HistoricProcessInstance loadProcessInstance(
            String processInstanceId, String tenantId) {

        var query = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId);
        if (tenantId != null) {
            query.tenantIdIn(tenantId);
        }
        return query.singleResult();
    }

    private List<HistoricActivityInstance> loadActivities(
            String processInstanceId, String tenantId) {

        var query = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricActivityInstanceStartTime()
                .asc()
                .orderPartiallyByOccurrence()
                .asc();
        if (tenantId != null) {
            query.tenantIdIn(tenantId);
        }
        return query.list();
    }

    private Map<String, String> collectErrorMessages(
            String processInstanceId, String tenantId) {

        var query = historyService.createHistoricIncidentQuery()
                .processInstanceId(processInstanceId)
                .open();
        if (tenantId != null) {
            query.tenantIdIn(tenantId);
        }

        Map<String, String> result = new HashMap<>();
        for (HistoricIncident incident : query.list()) {
            if (incident.getActivityId() != null) {
                result.merge(
                        incident.getActivityId(),
                        incident.getIncidentMessage() != null
                                ? incident.getIncidentMessage()
                                : "Unknown error",
                        (existing, incoming) -> incoming
                );
            }
        }
        return result;
    }

    /**
     * Builds a map having activityInstanceId as a key and the child processInstanceId as a value.
     */
    private Map<String, String> buildCallActivityChildMap(
            String processInstanceId, String tenantId) {

        var query = historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .activityType(ActivityTypes.CALL_ACTIVITY);
        if (tenantId != null) {
            query.tenantIdIn(tenantId);
        }

        Map<String, String> map = new HashMap<>();
        for (HistoricActivityInstance activity : query.list()) {
            if (activity.getCalledProcessInstanceId() != null) {
                map.put(activity.getId(), activity.getCalledProcessInstanceId());
            }
        }
        return map;
    }

    private WorkflowElementHistory toElementHistory(
            HistoricActivityInstance activity,
            Map<String, String> errorByActivityInstanceId,
            Map<String, String> callActivityChildMap) {

        String secondaryContext = ActivityTypes.CALL_ACTIVITY.equals(activity.getActivityType())
                ? callActivityChildMap.get(activity.getId())
                : null;

        return new WorkflowElementHistory(
                toOffsetDateTime(activity.getStartTime()),
                toOffsetDateTime(activity.getEndTime()),
                activity.getActivityId(),
                mapToSpi(activity.getActivityType()),
                errorByActivityInstanceId.get(activity.getActivityId()),
                activity.isCanceled(),
                secondaryContext
        );
    }

    private WorkflowElementType mapToSpi(
            final String activityType) {

        if (activityType == null) {
            return WorkflowElementType.UNKNOWN;
        }

        switch (activityType) {
            case ActivityTypes.CALL_ACTIVITY: return WorkflowElementType.CALL_ACTIVITY;
            case ActivityTypes.BOUNDARY_COMPENSATION:
            case ActivityTypes.BOUNDARY_ERROR:
            case ActivityTypes.BOUNDARY_CONDITIONAL:
            case ActivityTypes.BOUNDARY_MESSAGE:
            case ActivityTypes.BOUNDARY_ESCALATION:
            case ActivityTypes.BOUNDARY_SIGNAL:
            case ActivityTypes.BOUNDARY_TIMER:
            case ActivityTypes.BOUNDARY_CANCEL: return WorkflowElementType.BOUNDARY_EVENT;
            case ActivityTypes.END_EVENT_CANCEL:
            case ActivityTypes.END_EVENT_COMPENSATION:
            case ActivityTypes.END_EVENT_ESCALATION:
            case ActivityTypes.END_EVENT_NONE:
            case ActivityTypes.END_EVENT_MESSAGE:
            case ActivityTypes.END_EVENT_SIGNAL:
            case ActivityTypes.END_EVENT_TERMINATE:
            case ActivityTypes.END_EVENT_ERROR: return WorkflowElementType.END_EVENT;
            case ActivityTypes.GATEWAY_EVENT_BASED: return WorkflowElementType.EVENT_BASED_GATEWAY;
            case ActivityTypes.GATEWAY_EXCLUSIVE: return WorkflowElementType.EXCLUSIVE_GATEWAY;
            case ActivityTypes.GATEWAY_INCLUSIVE: return WorkflowElementType.INCLUSIVE_GATEWAY;
            case ActivityTypes.GATEWAY_PARALLEL: return WorkflowElementType.PARALLEL_GATEWAY;
            case ActivityTypes.INTERMEDIATE_EVENT_CONDITIONAL:
            case ActivityTypes.INTERMEDIATE_EVENT_LINK:
            case ActivityTypes.INTERMEDIATE_EVENT_MESSAGE:
            case ActivityTypes.INTERMEDIATE_EVENT_SIGNAL:
            case ActivityTypes.INTERMEDIATE_EVENT_TIMER:
            case ActivityTypes.INTERMEDIATE_EVENT_CATCH: return WorkflowElementType.INTERMEDIATE_CATCH_EVENT;
            case ActivityTypes.INTERMEDIATE_EVENT_COMPENSATION_THROW:
            case ActivityTypes.INTERMEDIATE_EVENT_ESCALATION_THROW:
            case ActivityTypes.INTERMEDIATE_EVENT_MESSAGE_THROW:
            case ActivityTypes.INTERMEDIATE_EVENT_SIGNAL_THROW:
            case ActivityTypes.INTERMEDIATE_EVENT_NONE_THROW:
            case ActivityTypes.INTERMEDIATE_EVENT_THROW: return WorkflowElementType.INTERMEDIATE_THROW_EVENT;
            case ActivityTypes.MULTI_INSTANCE_BODY: return WorkflowElementType.MULTI_INSTANCE;
            case ActivityTypes.START_EVENT_COMPENSATION:
            case ActivityTypes.START_EVENT_CONDITIONAL:
            case ActivityTypes.START_EVENT_ERROR:
            case ActivityTypes.START_EVENT_ESCALATION:
            case ActivityTypes.START_EVENT_SIGNAL:
            case ActivityTypes.START_EVENT_MESSAGE:
            case ActivityTypes.START_EVENT_TIMER:
            case ActivityTypes.START_EVENT: return WorkflowElementType.START_EVENT;
            case ActivityTypes.SUB_PROCESS: return WorkflowElementType.SUB_PROCESS;
            case ActivityTypes.SUB_PROCESS_AD_HOC: return WorkflowElementType.AD_HOC_SUB_PROCESS;
            case ActivityTypes.TASK: return WorkflowElementType.TASK;
            case ActivityTypes.TASK_SCRIPT: return WorkflowElementType.SCRIPT_TASK;
            case ActivityTypes.TASK_BUSINESS_RULE: return WorkflowElementType.BUSINESS_RULE_TASK;
            case ActivityTypes.TASK_SERVICE: return WorkflowElementType.SERVICE_TASK;
            case ActivityTypes.TASK_MANUAL_TASK: return WorkflowElementType.MANUAL_TASK;
            case ActivityTypes.TASK_RECEIVE_TASK: return WorkflowElementType.RECEIVE_TASK;
            case ActivityTypes.TASK_SEND_TASK: return WorkflowElementType.SEND_TASK;
            case ActivityTypes.TASK_USER_TASK: return WorkflowElementType.USER_TASK;
            default: return WorkflowElementType.UNKNOWN;
        }

    }

    private OffsetDateTime toOffsetDateTime(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant()
                .atZone(ZoneId.systemDefault())
                .toOffsetDateTime();
    }
}
