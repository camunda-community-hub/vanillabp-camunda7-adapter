package io.vanillabp.camunda7.cockpit;

import io.vanillabp.camunda7.service.WakupJobExecutorNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.TaskScheduler;

import java.io.IOException;
import java.util.Date;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;

public class WakeupFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(WakeupFilter.class);
    
    private static final long DEBOUNCE_MILLIS = 500;
    
    private final ApplicationEventPublisher applicationEventPublisher;
    
    private final TaskScheduler taskScheduler;

    private static long lastWakeup;
    
    public WakeupFilter(
            final ApplicationEventPublisher applicationEventPublisher,
            final TaskScheduler taskScheduler) {
        
        this.applicationEventPublisher = applicationEventPublisher;
        this.taskScheduler = taskScheduler;
        
    }
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        
        try {
            
            chain.doFilter(request, response);
            
        } finally {
            
            lastWakeup = System.currentTimeMillis();
            taskScheduler.schedule(
                    this::wakeupJobExecutorOnActivity,
                    new Date(lastWakeup + DEBOUNCE_MILLIS));
            
        }
        
    }
    
    private void wakeupJobExecutorOnActivity() {

        final var diff = System.currentTimeMillis() - lastWakeup;
        if (diff < DEBOUNCE_MILLIS) {
            return;
        }
        
        logger.debug("Wanna wake up job-executor");
        applicationEventPublisher.publishEvent(
                new WakupJobExecutorNotification(
                        this.getClass().getName()));

    }
    
}
