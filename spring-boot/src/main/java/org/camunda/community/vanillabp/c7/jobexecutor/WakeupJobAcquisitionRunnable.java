package org.camunda.community.vanillabp.c7.jobexecutor;

import org.camunda.bpm.engine.impl.jobexecutor.JobAcquisitionStrategy;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.camunda.bpm.engine.impl.jobexecutor.SequentialJobAcquisitionRunnable;

public class WakeupJobAcquisitionRunnable extends SequentialJobAcquisitionRunnable {

    public WakeupJobAcquisitionRunnable(
            final JobExecutor jobExecutor) {
        
        super(jobExecutor);
        
    }

    @Override
    protected JobAcquisitionStrategy initializeAcquisitionStrategy() {
        
        return new WakeupBackoffJobAcquisitionStrategy(jobExecutor);
        
    }
    
}
