package org.camunda.community.vanillabp.c7.wiring;

import org.camunda.bpm.engine.impl.javax.el.CompositeELResolver;
import org.camunda.bpm.engine.impl.javax.el.ELResolver;
import org.camunda.bpm.engine.spring.SpringExpressionManager;
import org.camunda.community.vanillabp.c7.service.Camunda7ProcessService;
import org.springframework.context.ApplicationContext;

import java.util.Collection;
import java.util.HashMap;

/*
 * Custom expression manager to resolve process entities and @WorkflowTask annotated methods
 */
public class ProcessEntityAwareExpressionManager extends SpringExpressionManager {

    private volatile ProcessEntityELResolver processEntityELResolver;
    
    private final HashMap<Camunda7Connectable, Camunda7TaskHandler> toBeConnected = new HashMap<>();
    
    private final Collection<Camunda7ProcessService<?>> connectableServices;
    
    public ProcessEntityAwareExpressionManager(
            final ApplicationContext applicationContext,
            final Collection<Camunda7ProcessService<?>> connectableServices) {

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
