package io.vanillabp.camunda7.service;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

public class WakupJobExecutorService {
    
    private static final Logger logger = LoggerFactory.getLogger(WakupJobExecutorService.class);
    
    private final ProcessEngine processEngine;
    
    public WakupJobExecutorService(
            final ProcessEngine processEngine) {
        
        this.processEngine = processEngine;
        
    }
    
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void wakeupJobExecutor(
            final WakupJobExecutorNotification notification) {
        
        logger.debug("Wake up job-executor");
        final var jobExecutor = ((ProcessEngineConfigurationImpl) processEngine
                .getProcessEngineConfiguration())
                .getJobExecutor();
        jobExecutor.jobWasAdded();
        
    }
    
}
