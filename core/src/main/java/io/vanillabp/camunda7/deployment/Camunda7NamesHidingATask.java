package io.vanillabp.camunda7.deployment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import io.vanillabp.camunda7.wiring.Camunda7TaskConnectable;

/**
 * The task definitions of a model which the engine would resolve to something else than
 * the <code>&#64;WorkflowTask</code> method, and what this adapter says about them.
 * <p>
 * The engine evaluates <code>${chargeCard}</code> by asking its EL resolvers one after the
 * other. The resolver of this adapter is the LAST one. Before it, the engine looks for a
 * process variable of that name, and on Spring Boot for a bean of that name. Whichever
 * answers first wins. So a bean <code>chargeCard</code>, or a shared attribute
 * <code>chargeCard</code> of the workflow aggregate, takes the expression away from the
 * method. The method never runs, and nothing says so.
 * <p>
 * Putting the resolver first is no way out. A task wired by its element id answers every
 * name which is evaluated at its element. An input mapping <code>${amount}</code> there would
 * then run the handler instead of reading the variable. So the start is refused instead, see
 * decision 46 in the repository's {@code DECISIONS.md}.
 * <p>
 * What the start cannot see is a process variable which the application sets by hand while a
 * workflow runs. Only the names known at the start are checked: the beans, and the
 * attributes the aggregate shares.
 */
public final class Camunda7NamesHidingATask {

  private Camunda7NamesHidingATask() {
  }

  /**
   * The findings about one model, one sentence block per task definition which something
   * else answers. A task definition which a bean AND a shared attribute answer gets the
   * finding about the attribute, because the engine reads the variable first.
   *
   * @param connectables The tasks of the model this boot deploys
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param workflowModuleId The workflow module ID
   * @param sharedAttributes The task definitions which are also attributes the aggregate
   *          shares with the engine
   * @param classOfTheBeanNamed The class of the bean of a name, empty where the engine
   *          resolves no bean of that name
   * @return The findings, empty where every task definition reaches its method
   */
  public static List<String> findings(
      final Collection<Camunda7TaskConnectable> connectables,
      final String bpmnProcessId,
      final String workflowModuleId,
      final Collection<String> sharedAttributes,
      final Function<String, Optional<Class<?>>> classOfTheBeanNamed) {

    return reachedByAnExpression(connectables)
        .stream()
        .map(connectable -> {
          if (sharedAttributes.contains(connectable.taskDefinition())) {
            return Optional.of(hiddenByAnAttribute(connectable, bpmnProcessId, workflowModuleId));
          }
          return classOfTheBeanNamed
              .apply(connectable.taskDefinition())
              .map(beanClass -> hiddenByABean(connectable, beanClass, bpmnProcessId, workflowModuleId));
        })
        .flatMap(Optional::stream)
        .distinct()
        .toList();

  }

  /**
   * The tasks the engine reaches through an EL name. A user task is not among them. Its
   * form key is never evaluated as an expression, and its method is told through a task
   * listener.
   *
   * @param connectables The tasks of the model
   * @return The tasks whose task definition is an EL name
   */
  public static List<Camunda7TaskConnectable> reachedByAnExpression(
      final Collection<Camunda7TaskConnectable> connectables) {

    return connectables
        .stream()
        .filter(connectable -> connectable.type() != Camunda7TaskConnectable.Type.USER_TASK)
        .filter(connectable -> connectable.taskDefinition() != null)
        .toList();

  }

  private static String hiddenByABean(
      final Camunda7TaskConnectable connectable,
      final Class<?> beanClass,
      final String bpmnProcessId,
      final String workflowModuleId) {

    return """
        Task definition '%s' of BPMN process '%s' (element '%s', workflow module '%s') has the \
        same name as the Spring bean '%s' (class %s)! The engine asks the Spring beans before it \
        asks VanillaBP, so the expression '${%s}' reaches the bean and the @WorkflowTask method \
        never runs. Rename the task definition in the BPMN and in the @WorkflowTask method, or \
        rename the bean."""
        .formatted(
            connectable.taskDefinition(),
            bpmnProcessId,
            connectable.elementId(),
            workflowModuleId,
            connectable.taskDefinition(),
            beanClass.getName(),
            connectable.taskDefinition());

  }

  private static String hiddenByAnAttribute(
      final Camunda7TaskConnectable connectable,
      final String bpmnProcessId,
      final String workflowModuleId) {

    return """
        Task definition '%s' of BPMN process '%s' (element '%s', workflow module '%s') has the \
        same name as the attribute '%s' of the workflow aggregate, which is shared with the \
        engine as a process variable! The engine reads its process variables before it asks \
        VanillaBP, so the expression '${%s}' reads the variable and the @WorkflowTask method \
        never runs. Rename the task definition in the BPMN and in the @WorkflowTask method, \
        rename the attribute, or do not share it with the engine (@NoSyncWithBPMS on the \
        attribute, or @NoSyncWithBPMS on the aggregate and @SyncWithBPMS on what the model \
        reads)."""
        .formatted(
            connectable.taskDefinition(),
            bpmnProcessId,
            connectable.elementId(),
            workflowModuleId,
            connectable.taskDefinition(),
            connectable.taskDefinition());

  }

}
