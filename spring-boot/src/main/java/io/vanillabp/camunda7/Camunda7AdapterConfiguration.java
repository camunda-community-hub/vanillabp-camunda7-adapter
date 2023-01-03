package io.vanillabp.camunda7;

import io.vanillabp.camunda7.cockpit.WakeupFilter;
import io.vanillabp.camunda7.deployment.Camunda7DeploymentAdapter;
import io.vanillabp.camunda7.jobexecutor.WakeupJobExecutor;
import io.vanillabp.camunda7.service.Camunda7ProcessService;
import io.vanillabp.camunda7.service.WakupJobExecutorService;
import io.vanillabp.camunda7.wiring.Camunda7AdapterProperties;
import io.vanillabp.camunda7.wiring.Camunda7TaskWiring;
import io.vanillabp.camunda7.wiring.Camunda7TaskWiringPlugin;
import io.vanillabp.camunda7.wiring.Camunda7UserTaskEventHandler;
import io.vanillabp.camunda7.wiring.ProcessEntityAwareExpressionManager;
import io.vanillabp.camunda7.wiring.TaskWiringBpmnParseListener;
import io.vanillabp.springboot.adapter.AdapterConfigurationBase;
import io.vanillabp.springboot.adapter.SpringDataUtil;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.camunda.bpm.engine.impl.jobexecutor.NotifyAcquisitionRejectedJobsHandler;
import org.camunda.bpm.engine.spring.application.SpringProcessApplication;
import org.camunda.bpm.engine.spring.components.jobexecutor.SpringJobExecutor;
import org.camunda.bpm.spring.boot.starter.annotation.EnableProcessApplication;
import org.camunda.bpm.spring.boot.starter.configuration.impl.DefaultJobConfiguration.JobConfiguration;
import org.camunda.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.camunda.bpm.spring.boot.starter.property.JobExecutionProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InjectionPoint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Scope;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.repository.CrudRepository;
import org.springframework.scheduling.TaskScheduler;

import java.util.Optional;

@AutoConfigurationPackage(basePackageClasses = Camunda7AdapterConfiguration.class)
@EnableProcessApplication("org.camunda.bpm.spring.boot.starter.SpringBootProcessApplication")
public class Camunda7AdapterConfiguration extends AdapterConfigurationBase<Camunda7ProcessService<?>> {

    private static final Logger logger = LoggerFactory.getLogger(Camunda7AdapterConfiguration.class);

    @Value("${workerId}")
    private String workerId;
    
    @Autowired
    private ApplicationContext applicationContext;
    
    @Value("${camunda.bpm.webapp.application-path:/camunda}")
    private String camundaWebAppBaseUrl;

    @Bean
    @Order(-1)
    @ConditionalOnProperty(
            prefix = "camunda.bpm.job-execution",
            name = "wakeup",
            havingValue = "true",
            matchIfMissing = true)
    public static JobExecutor jobExecutor(
            @Qualifier(JobConfiguration.CAMUNDA_TASK_EXECUTOR_QUALIFIER) final TaskExecutor taskExecutor,
            CamundaBpmProperties properties) {
        
        logger.info("VanillaBP's job-executor is using jobExecutorPreferTimerJobs=true and jobExecutorAcquireByDueDate=true. Please add DB-index according to https://docs.camunda.org/manual/7.6/user-guide/process-engine/the-job-executor/#the-job-order-of-job-acquisition");
        
        final SpringJobExecutor springJobExecutor = new WakeupJobExecutor();
        springJobExecutor.setTaskExecutor(taskExecutor);
        springJobExecutor.setRejectedJobsHandler(new NotifyAcquisitionRejectedJobsHandler());

        JobExecutionProperty jobExecution = properties.getJobExecution();
        Optional.ofNullable(jobExecution.getLockTimeInMillis()).ifPresent(springJobExecutor::setLockTimeInMillis);
        Optional.ofNullable(jobExecution.getMaxJobsPerAcquisition()).ifPresent(springJobExecutor::setMaxJobsPerAcquisition);
        Optional.ofNullable(jobExecution.getWaitTimeInMillis()).ifPresent(springJobExecutor::setWaitTimeInMillis);
        Optional.ofNullable(jobExecution.getMaxWait()).ifPresent(springJobExecutor::setMaxWait);
        Optional.ofNullable(jobExecution.getBackoffTimeInMillis()).ifPresent(springJobExecutor::setBackoffTimeInMillis);
        Optional.ofNullable(jobExecution.getMaxBackoff()).ifPresent(springJobExecutor::setMaxBackoff);
        Optional.ofNullable(jobExecution.getBackoffDecreaseThreshold()).ifPresent(springJobExecutor::setBackoffDecreaseThreshold);
        Optional.ofNullable(jobExecution.getWaitIncreaseFactor()).ifPresent(springJobExecutor::setWaitIncreaseFactor);

        return springJobExecutor;
        
    }

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
            final ProcessEngine processEngine,
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

    @Bean
    @ConditionalOnProperty(
            prefix = "camunda.bpm.job-execution",
            name = "wakeup",
            havingValue = "true",
            matchIfMissing = true)
    public WakupJobExecutorService wakupJobExecutorService(
            final ProcessEngine processEngine) {
        
        return new WakupJobExecutorService(processEngine);
        
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "camunda.bpm.job-execution",
            name = "wakeup",
            havingValue = "true",
            matchIfMissing = true)
    public FilterRegistrationBean<WakeupFilter> wakeupFilterForCockpit(
            final ApplicationEventPublisher applicationEventPublisher,
            final Optional<TaskScheduler> taskScheduler) {

        if (taskScheduler.isEmpty()) {
            throw new RuntimeException(
                    "To use wakeup job-executor you have to provide Spring Boot task scheduler! "
                    + "(For details see https://github.com/vanillabp/camunda7-adapter/blob/main/spring-boot/README.md#job-executor)");
        }

        final var registrationBean = new FilterRegistrationBean<WakeupFilter>();

        registrationBean.setFilter(
                new WakeupFilter(
                        applicationEventPublisher,
                        taskScheduler.get()));
        registrationBean.addUrlPatterns(camundaWebAppBaseUrl + "/api/*");
        registrationBean.setOrder(-1);

        return registrationBean;

    }

    @Bean
    @ConditionalOnProperty(
            prefix = "camunda.bpm.job-execution",
            name = "wakeup",
            havingValue = "true",
            matchIfMissing = true)
    public FilterRegistrationBean<WakeupFilter> wakeupFilterForRestApi(
            final ApplicationEventPublisher applicationEventPublisher,
            final Optional<TaskScheduler> taskScheduler) {

        if (taskScheduler.isEmpty()) {
            throw new RuntimeException(
                    "To use wakeup job-executor you have to provide Spring Boot task scheduler! "
                    + "(For details see https://github.com/Phactum/vanillabp-camunda7-adapter/blob/main/spring-boot/README.md#job-executor)");
        }
        
        final var registrationBean = new FilterRegistrationBean<WakeupFilter>();

        registrationBean.setFilter(
                new WakeupFilter(
                        applicationEventPublisher,
                        taskScheduler.get()));
        registrationBean.addUrlPatterns("/engine-rest/*");
        registrationBean.setOrder(-1);

        return registrationBean;

    }
   
}
