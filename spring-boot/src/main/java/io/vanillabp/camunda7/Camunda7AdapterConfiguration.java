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
import io.vanillabp.springboot.adapter.VanillaBpProperties;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.spring.application.SpringProcessApplication;
import org.camunda.bpm.spring.boot.starter.CamundaBpmAutoConfiguration;
import org.camunda.bpm.spring.boot.starter.annotation.EnableProcessApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.repository.CrudRepository;

import javax.annotation.PostConstruct;

@AutoConfigurationPackage(basePackageClasses = Camunda7AdapterConfiguration.class)
@AutoConfigureBefore(CamundaBpmAutoConfiguration.class)
@EnableProcessApplication("org.camunda.bpm.spring.boot.starter.SpringBootProcessApplication")
public class Camunda7AdapterConfiguration extends AdapterConfigurationBase<Camunda7ProcessService<?>> {

    private static final Logger logger = LoggerFactory.getLogger(Camunda7AdapterConfiguration.class);
    
    public static final String ADAPTER_ID = "camunda7";
    
    @Value("${workerId}")
    private String workerId;
    
    @Autowired
    private SpringDataUtil springDataUtil; // ensure persistence is up and running
    
    @Autowired
    private ApplicationContext applicationContext;

    @Lazy
    @Autowired
    private ProcessEngine processEngine; // lazy to avoid circular dependency on bean creation

    @Autowired
    private ApplicationEventPublisher applicationEventPublisher;

    @PostConstruct
    public void init() {
        
        logger.debug("Will use SpringDataUtil class '{}'",
                AopProxyUtils.ultimateTargetClass(springDataUtil.getClass()));
        
    }
    
    @Override
    public String getAdapterId() {
        
        return ADAPTER_ID;
        
    }
    
    @Bean
    public Camunda7AdapterProperties camunda7AdapterProperties() {
        
        return new Camunda7AdapterProperties();
        
    }
    
    @Bean
    public Camunda7DeploymentAdapter camunda7DeploymentAdapter(
            final VanillaBpProperties properties,
            final SpringProcessApplication processApplication,
            final ProcessEngine processEngine,
            final Camunda7TaskWiring taskWiring) {

        return new Camunda7DeploymentAdapter(
                properties,
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
    
    @Override
    public <DE> Camunda7ProcessService<?> newProcessServiceImplementation(
            final SpringDataUtil springDataUtil,
            final Class<DE> workflowAggregateClass,
            final CrudRepository<DE, String> workflowAggregateRepository) {
        
        final var result = new Camunda7ProcessService<DE>(
                applicationEventPublisher,
                processEngine,
                workflowAggregate ->
                        !springDataUtil.isPersistedEntity(workflowAggregateClass, workflowAggregate),
                workflowAggregate ->
                        springDataUtil.getId(workflowAggregate),
                workflowAggregateRepository,
                workflowAggregateClass);
        
        putConnectableService(workflowAggregateClass, result);
        
        return result;
        
    }
   
}
