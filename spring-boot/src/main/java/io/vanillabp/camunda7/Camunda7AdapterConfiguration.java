package io.vanillabp.camunda7;

import io.vanillabp.camunda7.deployment.Camunda7DeploymentAdapter;
import io.vanillabp.camunda7.service.Camunda7ProcessService;
import io.vanillabp.camunda7.wiring.Camunda7AdapterProperties;
import io.vanillabp.camunda7.wiring.Camunda7TaskWiring;
import io.vanillabp.camunda7.wiring.Camunda7TaskWiringPlugin;
import io.vanillabp.camunda7.wiring.Camunda7UserTaskEventHandler;
import io.vanillabp.camunda7.wiring.ProcessEntityAwareExpressionManager;
import io.vanillabp.camunda7.wiring.TaskWiringBpmnParseListener;
import io.vanillabp.springboot.adapter.AdapterConfigurationBase;
import io.vanillabp.springboot.adapter.SpringDataUtil;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.spring.application.SpringProcessApplication;
import org.camunda.bpm.spring.boot.starter.annotation.EnableProcessApplication;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Scope;
import org.springframework.data.repository.CrudRepository;

@AutoConfigurationPackage(basePackageClasses = Camunda7AdapterConfiguration.class)
@EnableProcessApplication("org.camunda.bpm.spring.boot.starter.SpringBootProcessApplication")
public class Camunda7AdapterConfiguration extends AdapterConfigurationBase<Camunda7ProcessService<?>> {

    @Value("${workerId}")
    private String workerId;
    
    @Autowired
    private ApplicationContext applicationContext;

    @Bean
    public Camunda7AdapterProperties camunda7AdapterProperties() {
        
        return new Camunda7AdapterProperties();
        
    }
    
    @Bean
    public Camunda7DeploymentAdapter camunda7DeploymentAdapter(
            final SpringProcessApplication processApplication,
            final ProcessEngine processEngine,
            final Camunda7TaskWiring taskWiring) {

        return new Camunda7DeploymentAdapter(
                processApplication,
                taskWiring,
                processEngine);

    }
    
    @Bean
    public Camunda7UserTaskEventHandler userTaskEventHandler() {
        
        return new Camunda7UserTaskEventHandler();
        
    }
    
    @Bean
    public Camunda7TaskWiring taskWiring(
            final ProcessEntityAwareExpressionManager processEntityAwareExpressionManager,
            final Camunda7UserTaskEventHandler userTaskEventHandler) {
        
        return new Camunda7TaskWiring(
                applicationContext,
                processEntityAwareExpressionManager,
                userTaskEventHandler,
                getConnectableServices());
        
    }
    
    @Bean
    public TaskWiringBpmnParseListener taskWiringBpmnParseListener(
            final Camunda7TaskWiring taskWiring,
            final Camunda7UserTaskEventHandler userTaskEventHandler,
            final Camunda7AdapterProperties properties) {
        
        return new TaskWiringBpmnParseListener(
                taskWiring,
                userTaskEventHandler,
                properties.isUseBpmnAsyncDefinitions(),
                properties.getBpmnAsyncDefinitions());
        
    }
    
    @Bean
    public ProcessEntityAwareExpressionManager processEntityAwareExpressionManager() {

        return new ProcessEntityAwareExpressionManager(
                applicationContext,
                getConnectableServices());

    }

    @Bean
    public Camunda7TaskWiringPlugin taskWiringCamundaPlugin(
            final ProcessEntityAwareExpressionManager processEntityAwareExpressionManager,
            final TaskWiringBpmnParseListener taskWiringBpmnParseListener) {
        
        return new Camunda7TaskWiringPlugin(
                processEntityAwareExpressionManager,
                taskWiringBpmnParseListener);
        
    }
    
    @SuppressWarnings("unchecked")
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public <DE> Camunda7ProcessService<?> camundaProcessService(
            final ApplicationEventPublisher applicationEventPublisher,
            @Lazy final ProcessEngine processEngine, // lazy to avoid circular dependency on bean creation
            final SpringDataUtil springDataUtil,
            final InjectionPoint injectionPoint) throws Exception {

        return registerProcessService(
                springDataUtil,
                injectionPoint,
                (workflowAggregateRepository, workflowAggregateClass) ->
                new Camunda7ProcessService<DE>(
                        applicationEventPublisher,
                        processEngine,
                        workflowAggregate -> springDataUtil.getId(workflowAggregate),
                        (CrudRepository<DE, String>) workflowAggregateRepository,
                        (Class<DE>) workflowAggregateClass)
            );

    }
   
}
