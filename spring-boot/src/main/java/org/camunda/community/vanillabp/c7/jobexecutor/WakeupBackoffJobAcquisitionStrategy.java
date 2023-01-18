package org.camunda.community.vanillabp.c7.jobexecutor;

import org.camunda.bpm.engine.impl.jobexecutor.BackoffJobAcquisitionStrategy;
import org.camunda.bpm.engine.impl.jobexecutor.JobAcquisitionContext;
import org.camunda.bpm.engine.impl.jobexecutor.JobExecutor;
import org.camunda.bpm.engine.impl.util.ClassLoaderUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

public class WakeupBackoffJobAcquisitionStrategy extends BackoffJobAcquisitionStrategy {

    private static final Logger logger = LoggerFactory.getLogger(WakeupBackoffJobAcquisitionStrategy.class);
    
    public static long DEFAULT_EXECUTION_SATURATION_WAIT_TIME = 100;
    
    private final JobExecutor jobExecutor;
    
    private long waitTime;
    
    public WakeupBackoffJobAcquisitionStrategy(final JobExecutor jobExecutor) {

        super(jobExecutor);
        this.jobExecutor = jobExecutor;

    }
    
    @Override
    public long getWaitTime() {
        
        return waitTime;
        
    }
    
    @Override
    public void reconfigure(
            final JobAcquisitionContext context) {
        
        super.reconfigure(context);

        waitTime = super.getWaitTime();
        if ((waitTime == 0)
                || ((waitTime != maxIdleWaitTime)
                        && (waitTime != maxBackoffWaitTime))) {
            return;
        }
        
        final var now = new Date();
        var earliestDueDate = new Date(Long.MAX_VALUE);
        
        final var engineIterator = jobExecutor.engineIterator();
        final var classLoaderBeforeExecution = ClassLoaderUtil.switchToProcessEngineClassloader();
        try {
            
            while (engineIterator.hasNext()) {
                
                final var currentProcessEngine = engineIterator.next();
                if (!jobExecutor.hasRegisteredEngine(currentProcessEngine)) {
                    // if engine has been unregistered meanwhile
                    continue;
                }

                final var jobs = currentProcessEngine
                        .getManagementService()
                        .createJobQuery()
                        .active()
                        .withRetriesLeft()
                        .duedateHigherThan(now)
                        .orderByJobDuedate()
                        .asc()
                        .list();
                if (jobs.isEmpty()) {
                    continue;
                }
                
                final var earliestJob = jobs.get(0);
                if (earliestJob.getDuedate() == null) {
                    earliestDueDate = now;
                } else if (earliestJob.getDuedate().before(earliestDueDate)) {
                    earliestDueDate = earliestJob.getDuedate();
                }

            }
            
            waitTime = earliestDueDate.getTime() - now.getTime();
            if (earliestDueDate.getTime() == Long.MAX_VALUE) {
                logger.debug("No Job found having due-date set, JobExecutor will wait until external interaction");
            } else {
                logger.debug("Job with due-date set found, will wait until {}", earliestDueDate);
            }
            
        } catch (Exception e) {
            
            logger.warn("Error on trying to figure out if next job is timer-based", e);

        } finally {
            
            ClassLoaderUtil.setContextClassloader(classLoaderBeforeExecution);
            
        }
        
    }

}
