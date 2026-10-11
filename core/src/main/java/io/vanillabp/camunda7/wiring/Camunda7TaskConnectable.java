package io.vanillabp.camunda7.wiring;

/**
 * One BPMN task of an executable process which is wired to a <code>&#64;WorkflowTask</code>
 * method. It is read from the model during <code>wireBpmn</code>. The task definition is the
 * unwrapped expression text (<code>${processPayment}</code> &rarr;
 * <code>processPayment</code>). That is VanillaBP's Camunda 7 convention: the task's
 * implementation expression names the task definition.
 *
 * @param workflowModuleId The workflow module ID (the Camunda tenant, unless the
 *          module's name-clash-avoidance mode uses no tenant)
 * @param bpmnProcessId The PLAIN BPMN process ID - what the core's registries are
 *          keyed by
 * @param scopedBpmnProcessId The BPMN process ID AS THE ENGINE KNOWS IT (equal to
 *          {@code bpmnProcessId} unless the module's identifiers are prefixed, see
 *          decision 3 in the repository's DECISIONS.md)
 * @param elementId The BPMN activity ID
 * @param taskDefinition The unwrapped expression text
 * @param type How the BPMN wires the task (expression vs. delegate expression)
 */
public record Camunda7TaskConnectable(
                                      String workflowModuleId,
                                      String bpmnProcessId,
                                      String scopedBpmnProcessId,
                                      String elementId,
                                      String taskDefinition,
                                      Type type) {

  /**
   * For an unscoped process. The engine knows it under its plain id.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID, which the engine knows under the same name
   *          here
   * @param elementId The BPMN activity ID
   * @param taskDefinition The unwrapped expression text
   * @param type How the BPMN wires the task
   */
  public Camunda7TaskConnectable(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String elementId,
      final String taskDefinition,
      final Type type) {

    this(workflowModuleId, bpmnProcessId, bpmnProcessId, elementId, taskDefinition, type);

  }

  /**
   * How the model wires the task to the application. It decides what the engine does with
   * the expression, and so it decides when the task is completed.
   */
  public enum Type {
    /**
     * <code>camunda:expression</code>: the expression is evaluated. The handler runs
     * during evaluation, and the task completes when the expression returns. The tasks of
     * <code>&#64;TaskId</code> methods cannot be wired this way, because they could never
     * stay open.
     */
    EXPRESSION,
    /**
     * <code>camunda:delegateExpression</code>: the expression yields the adapter's
     * {@link Camunda7WorkflowTaskBehavior}. For <code>&#64;TaskId</code> methods the task
     * stays open, and it is completed later, asynchronously.
     */
    DELEGATE_EXPRESSION,
    /**
     * A BPMN user task. The task definition is the task's <code>camunda:formKey</code>.
     * The handler, if there is one, is told through task listeners (CREATED/CANCELED).
     * It never completes the task when it returns.
     */
    USER_TASK,
    /**
     * A {@code camunda:executionListener} somebody modelled, written as
     * {@code camunda:expression}. The engine evaluates the expression, and the handler runs
     * while it is evaluated. The listener returns when the handler does. It is served only
     * where the application asked for it, see {@link Camunda7Listeners}.
     */
    EXECUTION_LISTENER_EXPRESSION,
    /**
     * A {@code camunda:executionListener} somebody modelled, written as
     * {@code camunda:delegateExpression}. The engine expects the expression to yield a
     * listener object, and the EL resolver hands it one.
     */
    EXECUTION_LISTENER_DELEGATE_EXPRESSION
  }

  /**
   * Whether this connectable serves a listener somebody modelled, not a task of the model.
   * A listener never leaves its element and never completes anything. That is the whole
   * difference at runtime.
   *
   * @return Whether the connectable is one of the two execution-listener types
   */
  public boolean isExecutionListener() {

    return (type == Type.EXECUTION_LISTENER_EXPRESSION) || (type == Type.EXECUTION_LISTENER_DELEGATE_EXPRESSION);

  }

  /**
   * The verdict about a task which has to stay open but is wired by
   * <code>camunda:expression</code>. It is reported in two places, so it lives here once.
   * The first is the start of the application. There
   * {@code Camunda7DeploymentService#wireBpmn} asks the core whether the serving method
   * completes asynchronously. The second is {@link Camunda7TaskELResolver} at runtime. It
   * stays as the backstop for a model which reached the engine another way.
   *
   * @param taskDefinition The task definition (the unwrapped expression text)
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param workflowModuleId The workflow module ID
   * @return The message, naming the task, the process, the module and the fix
   */
  public static String asynchronousTaskWiredByExpression(
      final String taskDefinition,
      final String bpmnProcessId,
      final String workflowModuleId) {

    return """
        The @WorkflowTask method serving task definition '%s' of BPMN process '%s' \
        (workflow module '%s') declares a @TaskId parameter but the BPMN wires the task by \
        'camunda:expression' - such a task completes when the expression returns and can \
        never stay open! Wire the task by 'camunda:delegateExpression' instead."""
        .formatted(taskDefinition, bpmnProcessId, workflowModuleId);

  }

  /**
   * Whether this connectable serves the given EL name, evaluated at the given BPMN
   * element. It does by NAME when the model's delegate expression is the task definition.
   * It does by ELEMENT when a method is wired to the activity, whatever the model calls
   * the expression.
   *
   * @param currentElementId The BPMN element the execution sits at while the engine
   *          evaluates
   * @param propertyName The EL name the engine is trying to resolve
   * @return Whether this task is what the name means
   */
  public boolean applies(
      final String currentElementId,
      final String propertyName) {

    return appliesByElement(currentElementId) || appliesByName(propertyName);

  }

  /**
   * Whether the EL name IS this connectable's task definition. Such a name means the
   * task and nothing else.
   *
   * @param propertyName The EL name the engine is trying to resolve
   * @return Whether the name is this task's definition
   */
  public boolean appliesByName(
      final String propertyName) {

    return (taskDefinition != null) && taskDefinition.equals(propertyName);

  }

  /**
   * Whether this connectable is wired to the BPMN element the expression is evaluated at.
   * That alone says nothing about the NAME. Every expression which is evaluated while an
   * execution sits at this element arrives here. Conditions which read the workflow
   * aggregate are among them.
   *
   * @param currentElementId The BPMN element the execution sits at
   * @return Whether this task is wired to that element
   */
  public boolean appliesByElement(
      final String currentElementId) {

    return elementId.equals(currentElementId);

  }

}
