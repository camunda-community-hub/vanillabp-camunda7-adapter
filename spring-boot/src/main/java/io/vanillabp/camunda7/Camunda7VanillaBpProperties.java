package io.vanillabp.camunda7;

import io.vanillabp.springboot.adapter.VanillaBpProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.util.Map;

@ConfigurationProperties(prefix = VanillaBpProperties.PREFIX, ignoreUnknownFields = true)
public class Camunda7VanillaBpProperties {

    private static final boolean DEFAULT_USEBPMNASYNCDEFINITIONS = false;

    private static final boolean DEFAULT_USETENANT = true;

    private Map<String, WorkflowModuleAdapterProperties> workflowModules = Map.of();

    public Map<String, WorkflowModuleAdapterProperties> getWorkflowModules() {
        return workflowModules;
    }

    public void setWorkflowModules(Map<String, WorkflowModuleAdapterProperties> workflowModules) {

        this.workflowModules = workflowModules;
        workflowModules.forEach((workflowModuleId, properties) -> {
            properties.workflowModuleId = workflowModuleId;
        });

    }

    private static final WorkflowModuleAdapterProperties defaultProperties = new WorkflowModuleAdapterProperties();
    private static final AdapterConfiguration defaultAdapterProperties = new AdapterConfiguration();

    public String getTenantId(
            final String workflowModuleId) {

        final var configuration = workflowModules
                .getOrDefault(workflowModuleId, defaultProperties)
                .getAdapters()
                .getOrDefault(Camunda7AdapterConfiguration.ADAPTER_ID, defaultAdapterProperties);
        if (!configuration.isUseTenants()) {
            return null;
        }
        if (StringUtils.hasText(configuration.getTenantId())) {
            return configuration.getTenantId();
        }
        return workflowModuleId;

    }

    public boolean useBpmnAsyncDefinitions(
            final String workflowModuleId,
            final String bpmnProcessId) {

        boolean result = DEFAULT_USEBPMNASYNCDEFINITIONS;
        final var workflowModule = workflowModules.get(workflowModuleId);
        if (workflowModule == null) {
            return result;
        }
        final var workflowModuleAdapter = workflowModule.getAdapters().get(Camunda7AdapterConfiguration.ADAPTER_ID);
        if (workflowModuleAdapter != null) {
            result = workflowModuleAdapter.isUseBpmnAsyncDefinitions();
        }
        final var workflow = workflowModule.getWorkflows().get(bpmnProcessId);
        if (workflow == null) {
            return result;
        }
        final var workflowAdapter = workflow.getAdapters().get(Camunda7AdapterConfiguration.ADAPTER_ID);
        if (workflowAdapter == null) {
            return result;
        }
        return workflowAdapter.isUseBpmnAsyncDefinitions();

    }

    public static class AdapterConfiguration extends AsyncProperties {

        private boolean useTenants = DEFAULT_USETENANT;

        private String tenantId;

        public boolean isUseTenants() {
            return useTenants;
        }

        public void setUseTenants(boolean useTenants) {
            this.useTenants = useTenants;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

    }

    public static class AsyncProperties {

        private boolean useBpmnAsyncDefinitions = DEFAULT_USEBPMNASYNCDEFINITIONS;

        public boolean isUseBpmnAsyncDefinitions() {
            return useBpmnAsyncDefinitions;
        }

        public void setUseBpmnAsyncDefinitions(boolean useBpmnAsyncDefinitions) {
            this.useBpmnAsyncDefinitions = useBpmnAsyncDefinitions;
        }

    }

    public static class WorkflowModuleAdapterProperties {

        String workflowModuleId;

        private Map<String, AdapterConfiguration> adapters = Map.of();

        private Map<String, WorkflowAdapterProperties> workflows = Map.of();

        public Map<String, AdapterConfiguration> getAdapters() {
            return adapters;
        }

        public void setAdapters(Map<String, AdapterConfiguration> adapters) {
            this.adapters = adapters;
        }

        public Map<String, WorkflowAdapterProperties> getWorkflows() { return workflows; }

        public void setWorkflows(Map<String, WorkflowAdapterProperties> workflows) {

            this.workflows = workflows;
            workflows.forEach((bpmnProcessId, properties) -> {
                properties.bpmnProcessId = bpmnProcessId;
                properties.workflowModule = this;
            });

        }

    }

    public static class WorkflowAdapterProperties {

        String bpmnProcessId;

        WorkflowModuleAdapterProperties workflowModule;

        private Map<String, AsyncProperties> adapters = Map.of();

        public WorkflowModuleAdapterProperties getWorkflowModule() {
            return workflowModule;
        }

        public String getBpmnProcessId() {
            return bpmnProcessId;
        }

        public Map<String, AsyncProperties> getAdapters() {
            return adapters;
        }

        public void setAdapters(Map<String, AsyncProperties> adapters) {
            this.adapters = adapters;
        }

    }

}
