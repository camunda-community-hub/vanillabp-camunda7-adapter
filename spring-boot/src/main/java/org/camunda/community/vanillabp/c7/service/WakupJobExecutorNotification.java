package org.camunda.community.vanillabp.c7.service;

import org.springframework.context.ApplicationEvent;

import java.time.Clock;

public class WakupJobExecutorNotification extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    public WakupJobExecutorNotification(Object source, Clock clock) {
        super(source, clock);
    }

    public WakupJobExecutorNotification(Object source) {
        super(source);
    }

}
