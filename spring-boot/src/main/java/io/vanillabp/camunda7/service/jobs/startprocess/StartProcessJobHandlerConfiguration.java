package io.vanillabp.camunda7.service.jobs.startprocess;

import org.camunda.bpm.engine.impl.jobexecutor.JobHandlerConfiguration;

public class StartProcessJobHandlerConfiguration implements JobHandlerConfiguration {

    private final String businessKey;

    private final String workflowModuleId;

    private final String bpmnProcessId;

    public StartProcessJobHandlerConfiguration(
            final String workflowModuleId,
            final String bpmnProcessId,
            final String businessKey) {

        this.businessKey = businessKey;
        this.workflowModuleId = workflowModuleId;
        this.bpmnProcessId = bpmnProcessId;

    }

    @Override
    public String toCanonicalString() {

        return workflowModuleId + "\n" + bpmnProcessId + "\n" + businessKey;

    }

    public String getWorkflowModuleId() {
        return workflowModuleId;
    }

    public String getBpmnProcessId() {
        return bpmnProcessId;
    }

    public String getBusinessKey() {
        return businessKey;
    }

}
