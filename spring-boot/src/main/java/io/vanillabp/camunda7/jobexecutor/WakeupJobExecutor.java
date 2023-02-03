package io.vanillabp.camunda7.jobexecutor;

import org.camunda.bpm.engine.spring.components.jobexecutor.SpringJobExecutor;

public class WakeupJobExecutor extends SpringJobExecutor {

    @Override
    protected void ensureInitialization() {

        super.ensureInitialization();
        acquireJobsRunnable = new WakeupJobAcquisitionRunnable(this);
        
    }
    
}
