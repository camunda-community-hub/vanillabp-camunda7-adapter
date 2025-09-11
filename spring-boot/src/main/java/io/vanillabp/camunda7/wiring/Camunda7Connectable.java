package io.vanillabp.camunda7.wiring;

import io.vanillabp.springboot.adapter.Connectable;
import org.camunda.bpm.engine.repository.ProcessDefinition;

public class Camunda7Connectable implements Connectable {

    public static enum Type {
        EXPRESSION, DELEGATE_EXPRESSION, EXTERNAL_TASK, USERTASK
    };
    
    private final Type type;
    private final String bpmnProcessId;
    private final String versionInfo;
    private final String elementId;
    private final String taskDefinition;
    
    public Camunda7Connectable(
            final ProcessDefinition processDefinition,
            final String elementId,
            final String taskDefinition,
            final Type type) {

        this.bpmnProcessId = processDefinition.getKey();
        this.versionInfo = processDefinition.getVersionTag() != null
                ? "%s (%d)".formatted(processDefinition.getVersionTag(), processDefinition.getVersion())
                : Integer.toString(processDefinition.getVersion());
        this.elementId = elementId;
        this.taskDefinition = taskDefinition;
        this.type = type;

    }

    public boolean applies(
            final String elementId,
            final String taskDefinition) {

        return getElementId().equals(elementId)
                || getTaskDefinition().equals(taskDefinition);
        
    }

    @Override
    public String getVersionInfo() {

        return versionInfo;

    }

    @Override
    public boolean isExecutableProcess() {
        
        return true;
        
    }
    
    public Type getType() {

        return type;

    }
    
    @Override
    public String getElementId() {
        
        return elementId;
        
    }
    
    @Override
    public String getBpmnProcessId() {
        
        return bpmnProcessId;
        
    }

    @Override
    public String getTaskDefinition() {
        
        return taskDefinition;
        
    }
    
}