package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.engine.impl.el.JuelExpressionManager;
import org.camunda.bpm.impl.juel.jakarta.el.CompositeELResolver;
import org.camunda.bpm.impl.juel.jakarta.el.ELResolver;

import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;

/**
 * The engine's default (JUEL) expression manager extended by VanillaBP's
 * {@link Camunda7TaskELResolver} - used on platforms without a bean-aware
 * expression manager (Quarkus; the Spring Boot module extends Camunda's
 * {@code SpringExpressionManager} the same way to keep Spring beans resolvable
 * in EL).
 */
public class Camunda7TaskExpressionManager extends JuelExpressionManager {

  private final Camunda7TaskRegistry taskRegistry;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * Puts this adapter's resolver in front of the engine's own, for a platform which has no
   * bean-aware expression manager to extend instead.
   *
   * @param taskRegistry What the resolver built here looks a task up in
   * @param workflowTaskInvoker Where the application's method is called
   */
  public Camunda7TaskExpressionManager(
      final Camunda7TaskRegistry taskRegistry,
      final WorkflowTaskInvoker workflowTaskInvoker) {

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
