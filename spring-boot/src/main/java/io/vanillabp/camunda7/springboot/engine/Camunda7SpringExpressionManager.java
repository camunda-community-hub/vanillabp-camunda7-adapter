package io.vanillabp.camunda7.springboot.engine;

import org.camunda.bpm.engine.spring.SpringExpressionManager;
import org.camunda.bpm.impl.juel.jakarta.el.CompositeELResolver;
import org.camunda.bpm.impl.juel.jakarta.el.ELResolver;
import org.springframework.context.ApplicationContext;

import io.vanillabp.camunda7.wiring.Camunda7TaskELResolver;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;

/**
 * Camunda's Spring-bean-aware expression manager extended by VanillaBP's
 * {@link Camunda7TaskELResolver}. The engine asks its process variables first, then the
 * Spring beans, and VanillaBP last. A top-level EL name which none of the others answers
 * reaches a wired <code>&#64;WorkflowTask</code> method. Version 1 had the same order.
 */
public class Camunda7SpringExpressionManager extends SpringExpressionManager {

  private final Camunda7TaskRegistry taskRegistry;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * Adds this adapter's resolver behind the one which resolves Spring beans. So a bean
   * answers a name before a task does, and the start refuses a task definition which a
   * bean would hide (see {@code DECISIONS.pending/966.md} in the repository).
   *
   * @param applicationContext The application's context, which is where every name this
   *          adapter does not claim is resolved
   * @param taskRegistry What the resolver built here looks a task up in
   * @param workflowTaskInvoker Where the application's method is called
   */
  public Camunda7SpringExpressionManager(
      final ApplicationContext applicationContext,
      final Camunda7TaskRegistry taskRegistry,
      final WorkflowTaskInvoker workflowTaskInvoker) {

    super(applicationContext, null);
    this.taskRegistry = taskRegistry;
    this.workflowTaskInvoker = workflowTaskInvoker;

  }

  @Override
  protected ELResolver createElResolver() {

    final var compositeResolver = (CompositeELResolver) super.createElResolver();
    compositeResolver.add(new Camunda7TaskELResolver(taskRegistry, workflowTaskInvoker));
    return compositeResolver;

  }

}
