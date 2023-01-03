package io.vanillabp.camunda7.wiring;

import java.util.HashMap;
import java.util.Map;

import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.model.bpmn.instance.UserTask;

public class Camunda7UserTaskEventHandler implements TaskListener {

    private final Map<Camunda7Connectable, Camunda7UserTaskHandler> taskHandlers = new HashMap<>();
    
    public void addTaskHandler(
            final Camunda7Connectable connectable,
            final Camunda7UserTaskHandler taskHandler) {
        
        taskHandlers.put(connectable, taskHandler);
        
    }

    @Override
    public void notify(
            final DelegateTask delegateTask) {
        
        final var execution = (ExecutionEntity) delegateTask.getExecution();
        
        final var bpmnProcessId = execution
                .getProcessDefinition()
                .getKey();
        
        final var handler = taskHandlers
                .entrySet()
                .stream()
                .filter(entry -> {
                    final var connectable = entry.getKey();
                    
                    if (!connectable.getBpmnProcessId().equals(bpmnProcessId)) {
                        return false;
                    }
                    
                    final var element = execution.getBpmnModelElementInstance();
                    if (element == null) {
                        return false;
                    }
                    
                    return connectable.applies(
                            element.getId(),
                            ((UserTask) element).getCamundaFormKey());
                })
                .filter(entry -> entry.getValue().eventApplies(delegateTask.getEventName()))
                // found handler-reference
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "The is no method annotated by '@WorkflowTask(id = \""
                        + delegateTask.getTaskDefinitionKey()
                        + "\") in any class annotated by @WorkflowService(bpmnProcess = @BpmnProcess(bpmnProcessId = \""
                        + bpmnProcessId
                        + "\"))!"));
        
        handler.getValue().notify(delegateTask);
        
    }
    
}
