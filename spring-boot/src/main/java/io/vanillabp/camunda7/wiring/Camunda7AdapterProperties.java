package io.vanillabp.camunda7.wiring;

import io.vanillabp.camunda7.Camunda7AdapterConfiguration;
import io.vanillabp.springboot.utils.WorkflowAndModule;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "vanillabp.adapters." + Camunda7AdapterConfiguration.ADAPTER_ID)
public class Camunda7AdapterProperties {

    private boolean useBpmnAsyncDefinitions = false;
    
    private List<WorkflowAndModule> bpmnAsyncDefinitions = List.of();

    public boolean isUseBpmnAsyncDefinitions() {
        return useBpmnAsyncDefinitions;
    }

    public void setUseBpmnAsyncDefinitions(boolean useBpmnAsyncDefinitions) {
        this.useBpmnAsyncDefinitions = useBpmnAsyncDefinitions;
    }

    public List<WorkflowAndModule> getBpmnAsyncDefinitions() {
        return bpmnAsyncDefinitions;
    }

    public void setBpmnAsyncDefinitions(List<WorkflowAndModule> bpmnAsyncDefinitions) {
        this.bpmnAsyncDefinitions = bpmnAsyncDefinitions;
    };
    
}
