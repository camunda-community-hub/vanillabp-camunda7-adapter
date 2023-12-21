package io.vanillabp.camunda7.service.jobs.startprocess;

import org.camunda.bpm.engine.impl.jobexecutor.JobHandlerConfiguration;

public class StartProcessJobHandlerConfiguration implements JobHandlerConfiguration {

    private final String businessKey;

    private final String tenantId;

    private final String bpmnProcessId;

    public StartProcessJobHandlerConfiguration(
            final String tenantId,
            final String bpmnProcessId,
            final String businessKey) {

        this.businessKey = businessKey;
        this.tenantId = tenantId;
        this.bpmnProcessId = bpmnProcessId;

    }

    @Override
    public String toCanonicalString() {

        return tenantId + "\n" + bpmnProcessId + "\n" + businessKey;

    }

    public String getTenantId() {
        return tenantId;
    }

    public String getBpmnProcessId() {
        return bpmnProcessId;
    }

    public String getBusinessKey() {
        return businessKey;
    }

}
