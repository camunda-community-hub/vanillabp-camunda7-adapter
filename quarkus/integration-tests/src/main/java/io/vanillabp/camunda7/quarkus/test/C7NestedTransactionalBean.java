package io.vanillabp.camunda7.quarkus.test;

import io.vanillabp.spi.service.TaskException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * A bean of the application carrying its own transaction annotation, called by a
 * <code>&#64;WorkflowTask</code> handler. On Camunda 7 the handler shares its
 * transaction with the engine's job, so the rollback-only mark this bean's
 * interceptor sets takes the engine's transaction down as well: the workflow does not
 * even reach its error boundary event, and VanillaBP turns that into a failure naming
 * the cause.
 */
@ApplicationScoped
@Transactional
public class C7NestedTransactionalBean {

  public void raiseTaskException() {

    throw new TaskException("PaymentFailed", "PAYMENT_FAILED");

  }

}
