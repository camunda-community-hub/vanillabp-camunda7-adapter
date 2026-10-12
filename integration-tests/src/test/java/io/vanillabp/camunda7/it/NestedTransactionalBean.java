package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.service.TaskException;

/**
 * A bean of the application with its own transaction annotation, called by a
 * {@code @WorkflowTask} handler. On Camunda 7 the handler shares its transaction with the job of
 * the engine. The interceptor of this bean marks the transaction rollback-only, and that
 * takes the transaction of the engine down as well. So the workflow does not even reach its
 * error boundary event. VanillaBP turns that into a failure which names the cause.
 */
@Service
@Transactional
public class NestedTransactionalBean {

  public void raiseTaskException() {

    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

}
