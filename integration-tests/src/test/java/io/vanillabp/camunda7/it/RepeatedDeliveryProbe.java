package io.vanillabp.camunda7.it;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

/**
 * What the repeated-delivery scenario observes and steers. It counts how often the
 * <code>&#64;WorkflowTask</code> method was entered, and it says whether the next job is to
 * fail after the method ran. Both live outside the database on purpose. The rollback which
 * causes the redelivery would also take back a counter stored with the aggregate.
 */
@Component
public class RepeatedDeliveryProbe {

  private final AtomicInteger handlerInvocations = new AtomicInteger();

  private final AtomicBoolean failTheNextJob = new AtomicBoolean();

  public void reset() {

    handlerInvocations.set(0);
    failTheNextJob.set(false);

  }

  public void countHandlerInvocation() {

    handlerInvocations.incrementAndGet();

  }

  public int handlerInvocations() {

    return handlerInvocations.get();

  }

  public void failTheNextJob() {

    failTheNextJob.set(true);

  }

  /**
   * @return Whether this job is the one to fail, answered once
   */
  public boolean thisJobIsTheOneToFail() {

    return failTheNextJob.getAndSet(false);

  }

}
