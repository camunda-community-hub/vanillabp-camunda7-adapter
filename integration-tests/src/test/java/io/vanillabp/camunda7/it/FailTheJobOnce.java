package io.vanillabp.camunda7.it;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.ExecutionListener;
import org.springframework.stereotype.Component;

/**
 * Fails the engine job of the repeated-delivery scenario AFTER the
 * <code>&#64;WorkflowTask</code> method ran, by throwing from the task's end listener.
 * The engine rolls its job back and hands the same job out again, which is the
 * redelivery the test is about: whatever the handler committed in a transaction of its
 * own survives that rollback, and whatever rode the engine's transaction does not.
 */
@Component("failTheJobOnce")
public class FailTheJobOnce implements ExecutionListener {

  private final RepeatedDeliveryProbe probe;

  public FailTheJobOnce(
      final RepeatedDeliveryProbe probe) {

    this.probe = probe;

  }

  @Override
  public void notify(
      final DelegateExecution execution) {

    if (probe.thisJobIsTheOneToFail()) {
      throw new IllegalStateException("failing the job of '%s' on purpose".formatted(execution.getCurrentActivityId()));
    }

  }

}
