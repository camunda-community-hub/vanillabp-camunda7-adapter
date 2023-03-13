package io.vanillabp.camunda7.wiring;

import io.vanillabp.camunda7.service.Camunda7ProcessService;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.springboot.adapter.TaskWiringBase;
import io.vanillabp.springboot.parameters.MethodParameter;
import org.springframework.context.ApplicationContext;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

@Component
public class Camunda7TaskWiring extends TaskWiringBase<Camunda7Connectable, Camunda7ProcessService<?>> {

    private final ProcessEntityAwareExpressionManager processEntityAwareExpressionManager;

    private final Collection<Camunda7ProcessService<?>> connectableServices;
    
    private final Camunda7UserTaskEventHandler userTaskEventHandler;

    public Camunda7TaskWiring(
            final ApplicationContext applicationContext,
            final ProcessEntityAwareExpressionManager processEntityAwareExpressionManager,
            final Camunda7UserTaskEventHandler userTaskEventHandler,
            final Collection<Camunda7ProcessService<?>> connectableServices) {
        
        super(applicationContext);
        this.processEntityAwareExpressionManager = processEntityAwareExpressionManager;
        this.userTaskEventHandler = userTaskEventHandler;
        this.connectableServices = connectableServices;
        
    }
    
    @Override
    @SuppressWarnings("unchecked")
    protected void connectToBpms(
            final Camunda7ProcessService<?> processService,
            final Object bean,
            final Camunda7Connectable connectable,
            final Method method,
            final List<MethodParameter> parameters) {
        
        final var repository = processService.getWorkflowAggregateRepository();

        if (connectable.getType() == Camunda7Connectable.Type.USERTASK) {
            
            final var taskHandler = new Camunda7UserTaskHandler(
                    (CrudRepository<Object, String>) repository,
                    bean,
                    method,
                    parameters);
            userTaskEventHandler.addTaskHandler(connectable, taskHandler);
            return;
            
        }
        
        final var taskHandler = new Camunda7TaskHandler(
                connectable.getBpmnProcessId(),
                (CrudRepository<Object, String>) repository,
                bean,
                method,
                parameters);

        processEntityAwareExpressionManager.addTaskHandler(connectable, taskHandler);

    }
    
    @Override
    protected <DE> Camunda7ProcessService<?> connectToBpms(
            final String workflowModuleId,
            final Class<DE> workflowAggregateClass,
            final String bpmnProcessId,
            final boolean isPrimary,
            final Collection<String> messageBasedStartEventsMessageNames,
            final Collection<String> signalBasedStartEventsSignalNames) {
        
        final var processService = connectableServices
                .stream()
                .filter(service -> service.getWorkflowAggregateClass().equals(workflowAggregateClass))
                .findFirst()
                .orElseThrow(() -> new RuntimeException(
                        "You need to autowire '"
                        + ProcessService.class.getName()
                        + "<"
                        + workflowAggregateClass.getName()
                        + ">' in your code to be able to start workflows!"));

        processService.wire(
                workflowModuleId,
                bpmnProcessId,
                isPrimary,
                messageBasedStartEventsMessageNames,
                signalBasedStartEventsSignalNames);

        return processService;
        
    }
    
    public void validateWiring() {
        
        if (connectableServices
                .stream()
                .filter(Camunda7ProcessService::testForNotYetWired)
                .count() > 0) { 

            throw new RuntimeException(
                    "At least one ProcessService bean was not wired!"
                    + " See previous ERROR logs for details.");

        }
        
    }

}
