package org.camunda.community.vanillabp.c7.jobexecutor;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.camunda.bpm.engine.impl.jobexecutor.NotifyAcquisitionRejectedJobsHandler;
import org.camunda.bpm.engine.spring.components.jobexecutor.SpringJobExecutor;
import org.camunda.bpm.spring.boot.starter.configuration.impl.DefaultJobConfiguration.JobConfiguration;
import org.camunda.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.camunda.bpm.spring.boot.starter.property.JobExecutionProperty;
import org.camunda.community.vanillabp.c7.cockpit.WakeupFilter;
import org.camunda.community.vanillabp.c7.service.WakupJobExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;

import java.util.Optional;

@Configuration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(
        prefix = "camunda.bpm.job-execution",
        name = "wakeup",
        havingValue = "true",
        matchIfMissing = false)
public class WakeupJobExecutorConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(WakeupJobExecutorConfiguration.class);
    
    @Value("${camunda.bpm.webapp.application-path:/camunda}")
    private String camundaWebAppBaseUrl;

    @Bean
    @Order(-1)
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
    public WakupJobExecutorService wakupJobExecutorService(
            final ProcessEngine processEngine) {
        
        return new WakupJobExecutorService(processEngine);
        
    }

    @Bean
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
            matchIfMissing = false)
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
