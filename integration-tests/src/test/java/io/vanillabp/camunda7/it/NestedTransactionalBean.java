package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.service.TaskException;

/**
 * A bean of the application carrying its own transaction annotation, called by a
 * {@code @WorkflowTask} handler. On Camunda 7 the handler shares its transaction with
 * the engine's job, so the rollback-only mark this bean's interceptor sets takes the
 * engine's transaction down as well: the workflow does not even reach its error
 * boundary event, and VanillaBP turns that into a failure naming the cause.
 */
@Service
@Transactional
public class NestedTransactionalBean {

  public void raiseTaskException() {

    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

}
