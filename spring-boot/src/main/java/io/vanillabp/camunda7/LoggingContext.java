package io.vanillabp.camunda7;

import org.slf4j.MDC;

public class LoggingContext extends io.vanillabp.springboot.adapter.LoggingContext {

    /**
     * The current workflow's tenant. Per default the same value as the workflow module's ID
     * but may be overwritten by using Spring Boot properties.
     *
     * see <a href="https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/tree/main/spring-boot#using-camunda-multi-tenancy">Multi-tenancy</a>
     */
    public static final String WORKFLOW_TENANT_ID = "workflowTenantId";

    public static void clearContext() {

        io.vanillabp.springboot.adapter.LoggingContext.clearContext();

        MDC.remove(WORKFLOW_ADAPTER_ID);
        MDC.remove(WORKFLOW_AGGREGATE_ID);
        MDC.remove(WORKFLOW_BPM_ID);
        MDC.remove(WORKFLOW_BPMN_ID);
        MDC.remove(WORKFLOW_TASK_NODE);
        MDC.remove(WORKFLOW_TASK_ID);
        MDC.remove(WORKFLOW_TASK_NODE_ID);
        MDC.remove(WORKFLOW_MODULE_ID);
        MDC.remove(WORKFLOW_TENANT_ID);

    }

    public static void setLoggingContext(
            final String adapterId,
            final String tenantId,
            final String workflowModuleId,
            final String aggregateId,
            final String bpmnId,
            final String taskId,
            final String bpmId,
            final String taskNode,
            final String taskNodeId) {

        MDC.put(WORKFLOW_ADAPTER_ID, adapterId);
        MDC.put(WORKFLOW_AGGREGATE_ID, aggregateId);
        MDC.put(WORKFLOW_BPM_ID, bpmId);
        MDC.put(WORKFLOW_BPMN_ID, bpmnId);
        MDC.put(WORKFLOW_TASK_NODE, taskNode);
        MDC.put(WORKFLOW_TASK_ID, taskId);
        MDC.put(WORKFLOW_TASK_NODE_ID, taskNodeId);
        MDC.put(WORKFLOW_MODULE_ID, workflowModuleId);
        MDC.put(WORKFLOW_TENANT_ID, tenantId);

        final var context = io.vanillabp.springboot.adapter.LoggingContext.getWriteableContext();
        context.put(WORKFLOW_TENANT_ID, tenantId);
        context.put(WORKFLOW_ADAPTER_ID, adapterId);
        context.put(WORKFLOW_AGGREGATE_ID, aggregateId);
        context.put(WORKFLOW_BPM_ID, bpmId);
        context.put(WORKFLOW_BPMN_ID, bpmnId);
        context.put(WORKFLOW_TASK_NODE, taskNode);
        context.put(WORKFLOW_TASK_ID, taskId);
        context.put(WORKFLOW_TASK_NODE_ID, taskNodeId);
        context.put(WORKFLOW_MODULE_ID, workflowModuleId);

    }

}
