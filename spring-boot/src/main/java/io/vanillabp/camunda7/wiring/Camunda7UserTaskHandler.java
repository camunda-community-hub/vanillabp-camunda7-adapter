package io.vanillabp.camunda7.wiring;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.repository.CrudRepository;

import io.vanillabp.camunda7.service.Camunda7ProcessService;
import io.vanillabp.spi.service.TaskEvent;
import io.vanillabp.spi.service.TaskEvent.Event;
import io.vanillabp.springboot.adapter.MultiInstance;
import io.vanillabp.springboot.adapter.TaskHandlerBase;
import io.vanillabp.springboot.adapter.wiring.WorkflowAggregateCache;
import io.vanillabp.springboot.parameters.MethodParameter;
import io.vanillabp.springboot.parameters.TaskEventMethodParameter;

public class Camunda7UserTaskHandler extends TaskHandlerBase implements TaskListener {

    private static final Logger logger = LoggerFactory.getLogger(Camunda7UserTaskHandler.class);

    private final Camunda7ProcessService<?> processService;
    
    private final String bpmnProcessId;

    public Camunda7UserTaskHandler(
            final String bpmnProcessId,
            final CrudRepository<Object, Object> workflowAggregateRepository,
            final Object bean,
            final Method method,
            final List<MethodParameter> parameters,
            final Camunda7ProcessService<?> processService) {
        
        super(workflowAggregateRepository, bean, method, parameters);
        this.bpmnProcessId = bpmnProcessId;
        this.processService = processService;
        
    }

    @Override
    protected Logger getLogger() {

        return logger;

    }

    @SuppressWarnings("unchecked")
    @Override
    public void notify(final DelegateTask delegateTask) {

        final var multiInstanceCache = new Map[] { null };

        try {

            logger.trace("Will handle user-task '{}' of workflow '{}' ('{}') by execution '{}'",
                    delegateTask.getBpmnModelElementInstance().getId(),
                    delegateTask.getProcessInstanceId(),
                    bpmnProcessId,
                    delegateTask.getExecutionId());
            
            final var execution = delegateTask.getExecution();
            
            final Function<String, Object> multiInstanceSupplier = multiInstanceActivity -> {
                if (multiInstanceCache[0] == null) {
                    multiInstanceCache[0] = Camunda7TaskHandler.getMultiInstanceContext(execution);
                }
                return multiInstanceCache[0].get(multiInstanceActivity);
            };
            
            final var workflowAggregateId = processService
                    .getWorkflowAggregateIdFromBusinessKey(execution.getBusinessKey());
            
            final var workflowAggregateCache = new WorkflowAggregateCache();
            
            super.execute(
                    workflowAggregateCache,
                    workflowAggregateId,
                    true,
                    (args, param) -> processTaskParameter(
                            args,
                            param,
                            taskParameter -> execution.getVariableLocal(taskParameter)),
                    (args, param) -> processTaskIdParameter(
                            args,
                            param,
                            () -> delegateTask.getId()),
                    (args, param) -> processTaskEventParameter(
                            args,
                            param,
                            () -> Event.CREATED),
                    (args, param) -> processMultiInstanceIndexParameter(
                            args,
                            param,
                            multiInstanceSupplier),
                    (args, param) -> processMultiInstanceTotalParameter(
                            args,
                            param,
                            multiInstanceSupplier),
                    (args, param) -> processMultiInstanceElementParameter(
                            args,
                            param,
                            multiInstanceSupplier),
                    (args, param) -> processMultiInstanceResolverParameter(
                            args,
                            param,
                            () -> {
                                if (workflowAggregateCache.workflowAggregate == null) {
                                    workflowAggregateCache.workflowAggregate = workflowAggregateRepository
                                            .findById(workflowAggregateId)
                                            .orElseThrow();
                                }
                                return workflowAggregateCache.workflowAggregate;
                            }, multiInstanceSupplier));

        } catch (RuntimeException e) {

            throw e;

        } catch (Exception e) {

            throw new RuntimeException(e);

        }

    }
    
    public boolean eventApplies(
            final String eventName) {
        
        final var event = getTaskEvent(eventName);
        if (event == null) {
            return false;
        }
        
        return this.parameters
                .stream()
                .filter(parameter -> parameter instanceof TaskEventMethodParameter)
                .map(parameter -> ((TaskEventMethodParameter) parameter).getEvents())
                .findFirst()
                .orElse(Set.of(TaskEvent.Event.CREATED))
                .contains(event);
        
    }
    
    protected TaskEvent.Event getTaskEvent(
            final String eventName) {
        
        switch (eventName) {
        case org.camunda.bpm.engine.delegate.TaskListener.EVENTNAME_DELETE:
            return TaskEvent.Event.CANCELED;
        case org.camunda.bpm.engine.delegate.TaskListener.EVENTNAME_CREATE:
            return TaskEvent.Event.CREATED;
        default:
            return null;
        }
        
    }
    
    @SuppressWarnings("unchecked")
    @Override
    protected MultiInstance<Object> getMultiInstance(
            final String name,
            final Function<String, Object> multiInstanceSupplier) {
        
        return (MultiInstance<Object>) multiInstanceSupplier.apply(name);
        
    }
    
    @SuppressWarnings("unchecked")
    @Override
    protected Object getMultiInstanceElement(
            final String name,
            final Function<String, Object> multiInstanceSupplier) {
        
        return ((MultiInstance<Object>) multiInstanceSupplier.apply(name)).getElement();
        
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Integer getMultiInstanceTotal(
            final String name,
            final Function<String, Object> multiInstanceSupplier) {
        
        return ((MultiInstance<Object>) multiInstanceSupplier.apply(name)).getTotal();
        
    }

    @SuppressWarnings("unchecked")
    @Override
    protected Integer getMultiInstanceIndex(
            final String name,
            final Function<String, Object> multiInstanceSupplier) {
        
        return ((MultiInstance<Object>) multiInstanceSupplier.apply(name)).getIndex();
        
    }

}
