package io.vanillabp.camunda7.wiring;

import java.beans.FeatureDescriptor;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.Supplier;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.impl.juel.jakarta.el.ELContext;
import org.camunda.bpm.impl.juel.jakarta.el.ELResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.camunda7.service.Camunda7ProcessService;
import io.vanillabp.camunda7.utils.CaseUtils;

/*
 * Custom expression language resolver to resolve process entities
 * by using correspondingly named spring data repositories.
 */
public class ProcessEntityELResolver extends ELResolver {
    
    private static final Logger logger = LoggerFactory.getLogger(ProcessEntityELResolver.class);
    
    private final Map<Camunda7Connectable, Camunda7TaskHandler> taskHandlers = new HashMap<>();

    private final Supplier<Collection<Camunda7ProcessService<?>>> connectableServices;

    public ProcessEntityELResolver(
            final Supplier<Collection<Camunda7ProcessService<?>>> connectableServices) {

        super();
        this.connectableServices = connectableServices;

    }

    public void addTaskHandler(
            final Camunda7Connectable connectable,
            final Camunda7TaskHandler taskHandler) {
        
        taskHandlers.put(connectable, taskHandler);
        
    }

    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        return Object.class;
    }

    @Override
    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
        return null;
    }

    @Override
    public Class<?> getType(ELContext context, Object base, Object property) {
        return Object.class;
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {

        // if this is a lookup for attributes then use subsequent EL-resolvers
        if (base != null) {
            return null;
        }

        final var execution = (ExecutionEntity) context
                .getELResolver()
                .getValue(context, null, "execution");
        
        final var bpmnProcessId = execution
                .getProcessDefinition()
                .getKey();
        
        final var result = taskHandlers
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
                            property.toString());
                })
                .findFirst()
                // found handler-reference
                .map(handler -> executeHandler(execution, handler.getKey(), handler.getValue()))
                // otherwise it will be a workflow-aggregate property reference
                .orElseGet(() -> {
                    final var processServiceFound = connectableServices
                            .get()
                            .stream()
                            .filter(service -> service.getBpmnProcessIds().contains(bpmnProcessId))
                            .findFirst();
                    if (processServiceFound.isEmpty()) {
                        return null;
                    }
                    final var processService = processServiceFound.get();
                    if (execution.getBusinessKey() == null) {
                        return null;
                    }

                    final var id = processService
                            .getWorkflowAggregateIdFromBusinessKey(execution.getBusinessKey());
                    final var workflowAggregateFound = processService
                            .getWorkflowAggregateRepository()
                            .findById(id);
                    if (workflowAggregateFound.isEmpty()) {
                        return null;
                    }
                    final var workflowAggregate = workflowAggregateFound.get();
                    
                    final var workflowAggregateClass = processService
                            .getWorkflowAggregateClass();
                    
                    // use getter
                    final var getterName = "get"
                                + CaseUtils.firstCharacterToUpperCase(property.toString());
                    try {
                        return workflowAggregateClass
                                .getMethod(getterName)
                                .invoke(workflowAggregate);
                    } catch (NoSuchMethodException e) {
                        /* ignored */
                    } catch (Exception e) {
                        logger.warn("Could not access '{}#{}'",
                                workflowAggregateClass.getName(), getterName, e);
                        return null;
                    }

                    // use getter for booleans
                    final var isGetterName = "is"
                            + CaseUtils.firstCharacterToUpperCase(property.toString());
                    try {
                        return workflowAggregateClass
                                .getMethod(isGetterName)
                                .invoke(workflowAggregate);
                    } catch (NoSuchMethodException e) {
                        /* ignored */
                    } catch (Exception e) {
                        logger.warn("Could not access '{}#{}'",
                                workflowAggregateClass.getName(), isGetterName, e);
                        return null;
                    }
                    
                    // use property
                    try {
                        final var field = workflowAggregateClass
                                .getDeclaredField(property.toString());
                        field.setAccessible(true);
                        return field.get(workflowAggregate);
                    } catch (NoSuchFieldException e) {
                        /* ignored */
                    } catch (Exception e) {
                        logger.warn("Could not access property '{}' in class '{}'",
                                property.toString(), workflowAggregateClass.getName(), e);
                        return null;
                    }
                    
                    return null;
                });
        
        return result;

    }
    
    private Object executeHandler(
            final ExecutionEntity execution,
            final Camunda7Connectable connectable,
            final Camunda7TaskHandler taskHandler) {
        
        if (connectable.getType() == Camunda7Connectable.Type.EXPRESSION) {
            
            try {
                taskHandler.execute(execution);
                return taskHandler.getResult();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Could not execute handler", e);
            }
            
        } else if (connectable.getType() == Camunda7Connectable.Type.DELEGATE_EXPRESSION) {
            
            return taskHandler;
            
        }
        
        return null;
        
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) {
        return true;
    }

    @Override
    public void setValue(ELContext context, Object base, Object property, Object value) {

        if (base == null && getValue(context, null, property) != null) {
            throw new ProcessEngineException("Cannot set value of '" + property +
                "', it resolves to a process entity bound to the process instance.");
        }

    }

}
