package io.vanillabp.camunda7.jobexecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.vanillabp.camunda7.cockpit.WakeupFilter;
import io.vanillabp.camunda7.service.WakupJobExecutorService;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.camunda.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

/**
 * Regression net for {@link WakeupJobExecutorConfiguration} - the servlet-facing part of the Camunda 7
 * adapter, and the only place in this repository that registers servlet filters.
 *
 * <p>This is the primary coverage for the feature, not a supplement: the wakeup job executor is opt-in
 * through {@code camunda.bpm.job-execution.wakeup=true}, and neither blueprint enables it. So the manual
 * end-to-end test in T20 does not exercise this code at all unless the property is switched on there.
 *
 * <p>What can break silently:
 * <ul>
 * <li>The class is registered as an auto-configuration and carries
 * {@code @AutoConfigureOrder(HIGHEST_PRECEDENCE)}, which is only honoured for auto-configurations. It was
 * a plain {@code @Configuration} until T13, so the ordering had no effect.</li>
 * <li>{@code FilterRegistrationBean} still lives in {@code org.springframework.boot.web.servlet} in
 * Spring Boot 4 - verified against the 4.1.0 jar, contrary to several migration guides. If that ever
 * changes, this test fails at compile time rather than at runtime.</li>
 * <li>The filters are injected an {@code Optional<TaskScheduler>}. Spring Boot only auto-configures a
 * scheduler under certain conditions, and the configuration deliberately fails fast with a pointing
 * message when none is present. That message is part of the contract.</li>
 * </ul>
 *
 * <p>Camunda's own beans are mocked: bringing up a real process engine would need a database and belongs
 * to the blueprint test, whereas the wiring is what this test is about.
 */
class WakeupJobExecutorConfigurationTest {

    private static final String WAKEUP_ENABLED = "camunda.bpm.job-execution.wakeup=true";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(WakeupJobExecutorConfiguration.class));

    /** What Camunda's own auto-configuration would normally provide. */
    @Configuration
    static class CamundaInfrastructure {

        @Bean("camundaTaskExecutor")
        TaskExecutor camundaTaskExecutor() {
            return new SimpleAsyncTaskExecutor();
        }

        @Bean
        CamundaBpmProperties camundaBpmProperties() {
            return new CamundaBpmProperties();
        }

        @Bean
        ProcessEngine processEngine() {
            return mock(ProcessEngine.class);
        }

    }

    @Configuration
    static class WithTaskScheduler {

        @Bean
        TaskScheduler taskScheduler() {
            return new SimpleAsyncTaskScheduler();
        }

    }

    @Test
    void withoutTheWakeupPropertyNothingIsContributed() {

        // the feature is opt-in; an application that does not ask for it must not get a job executor
        // replaced behind its back
        runner
                .withUserConfiguration(CamundaInfrastructure.class, WithTaskScheduler.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(JobExecutor.class);
                    assertThat(context).doesNotHaveBean(WakupJobExecutorService.class);
                    assertThat(context.getBeansOfType(FilterRegistrationBean.class)).isEmpty();
                });

    }

    @Test
    void withTheWakeupPropertyTheJobExecutorIsReplaced() {

        runner
                .withPropertyValues(WAKEUP_ENABLED)
                .withUserConfiguration(CamundaInfrastructure.class, WithTaskScheduler.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JobExecutor.class);
                    assertThat(context.getBean(JobExecutor.class)).isInstanceOf(WakeupJobExecutor.class);
                    assertThat(context).hasSingleBean(WakupJobExecutorService.class);
                });

    }

    @Test
    void bothWakeupFiltersAreRegistered() {

        runner
                .withPropertyValues(WAKEUP_ENABLED)
                .withUserConfiguration(CamundaInfrastructure.class, WithTaskScheduler.class)
                .run(context -> {
                    assertThat(context.getBeansOfType(FilterRegistrationBean.class))
                            .containsOnlyKeys("wakeupFilterForCockpit", "wakeupFilterForRestApi");
                });

    }

    @Test
    void theFiltersCoverTheCamundaWebappAndTheRestApi() {

        runner
                .withPropertyValues(WAKEUP_ENABLED)
                .withUserConfiguration(CamundaInfrastructure.class, WithTaskScheduler.class)
                .run(context -> {
                    // "Cockpit" here is Camunda's own operations tool under /camunda, not the VanillaBP
                    // Business Cockpit
                    final var forWebapp = context.getBean(
                            "wakeupFilterForCockpit", FilterRegistrationBean.class);
                    final var forRestApi = context.getBean(
                            "wakeupFilterForRestApi", FilterRegistrationBean.class);

                    assertThat(forWebapp.getUrlPatterns()).containsExactly("/camunda/api/*");
                    assertThat(forRestApi.getUrlPatterns()).containsExactly("/engine-rest/*");
                    assertThat(forWebapp.getFilter()).isInstanceOf(WakeupFilter.class);
                    assertThat(forRestApi.getFilter()).isInstanceOf(WakeupFilter.class);
                    // both run before anything else, so the engine is woken up before the request is
                    // served
                    assertThat(forWebapp.getOrder()).isEqualTo(-1);
                    assertThat(forRestApi.getOrder()).isEqualTo(-1);

                });

    }

    @Test
    void theWebappPathFollowsTheCamundaApplicationPathProperty() {

        runner
                .withPropertyValues(WAKEUP_ENABLED, "camunda.bpm.webapp.application-path=/operations")
                .withUserConfiguration(CamundaInfrastructure.class, WithTaskScheduler.class)
                .run(context -> assertThat(context
                        .getBean("wakeupFilterForCockpit", FilterRegistrationBean.class)
                        .getUrlPatterns())
                        .containsExactly("/operations/api/*"));

    }

    @Test
    void withoutATaskSchedulerTheStartupFailsWithAPointingMessage() {

        // deliberate fail-fast: without a scheduler the engine would never be woken up, and a silent
        // fallback would only show up as latency in production
        runner
                .withPropertyValues(WAKEUP_ENABLED)
                .withUserConfiguration(CamundaInfrastructure.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining("you have to provide Spring Boot task scheduler");
                });

    }

}
