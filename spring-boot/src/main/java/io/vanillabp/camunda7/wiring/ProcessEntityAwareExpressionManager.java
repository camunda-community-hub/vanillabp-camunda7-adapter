package io.vanillabp.camunda7.wiring;

import io.vanillabp.camunda7.service.Camunda7ProcessService;
import org.camunda.bpm.engine.spring.SpringExpressionManager;
import org.camunda.bpm.impl.juel.jakarta.el.CompositeELResolver;
import org.camunda.bpm.impl.juel.jakarta.el.ELResolver;
import org.springframework.context.ApplicationContext;

import java.util.Collection;
import java.util.HashMap;
import java.util.function.Supplier;

/*
 * Custom expression manager to resolve process entities and @WorkflowTask annotated methods
 */
public class ProcessEntityAwareExpressionManager extends SpringExpressionManager {

    private volatile ProcessEntityELResolver processEntityELResolver;
    
    private final HashMap<Camunda7Connectable, Camunda7TaskHandler> toBeConnected = new HashMap<>();
    
    private final Supplier<Collection<Camunda7ProcessService<?>>> connectableServices;
    
    public ProcessEntityAwareExpressionManager(
            final ApplicationContext applicationContext,
            final Supplier<Collection<Camunda7ProcessService<?>>> connectableServices) {

        super(applicationContext, null);
        this.connectableServices = connectableServices;

    }

    @Override
    protected ELResolver createElResolver() {

        synchronized (this) {

            processEntityELResolver = new ProcessEntityELResolver(
                    connectableServices);
            
            toBeConnected
                    .entrySet()
                    .stream()
                    .forEach(entry -> processEntityELResolver
                            .addTaskHandler(entry.getKey(), entry.getValue()));
            toBeConnected.clear();
            
        }

        var compositeElResolver = (CompositeELResolver) super.createElResolver();
        compositeElResolver.add(processEntityELResolver);
        return compositeElResolver;

    }

    public void addTaskHandler(
            final Camunda7Connectable connectable,
            final Camunda7TaskHandler taskHandler) {
        
        synchronized (this) {
            
            if (processEntityELResolver == null) {
                toBeConnected.put(connectable, taskHandler);
            } else {
                processEntityELResolver.addTaskHandler(connectable, taskHandler);
            }
            
        }
        
    }
    
}
