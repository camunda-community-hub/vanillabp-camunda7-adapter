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
 * {@link Camunda7TaskELResolver}: top-level EL names resolve wired
 * <code>&#64;WorkflowTask</code> methods and workflow-aggregate attributes first,
 * Spring beans stay resolvable for everything else (Version-1 behavior).
 */
public class Camunda7SpringExpressionManager extends SpringExpressionManager {

  private final Camunda7TaskRegistry taskRegistry;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * Puts this adapter's resolver in front of the one which resolves Spring beans, so a
   * name which is not a task still finds its bean.
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
