package io.vanillabp.camunda7.wiring;

import java.util.List;
import java.util.function.Predicate;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.UserTask;

import io.vanillabp.camunda7.api.Camunda7TaskDefinitions;

/**
 * The user tasks of one BPMN process which no <code>&#64;WorkflowTask</code> method serves,
 * and the sentences a boot says about them.
 * <p>
 * Nothing here is a defect. This engine creates the user task, it appears in a task list and
 * whoever finishes it moves the workflow on, which is why the core hands such a task over as an
 * OPTIONAL spec. What the application loses is the notification, and it loses it without a word
 * unless somebody says so once. See the decision about a user task nothing serves in the
 * repository's DECISIONS.md.
 */
public final class Camunda7UnservedUserTasks {

  private Camunda7UnservedUserTasks() {
  }

  /**
   * One user task of the model which no method serves.
   *
   * @param elementId The BPMN id of the element
   * @param name What the modeller wrote on it, or <code>null</code>
   * @param formKey The <code>camunda:formKey</code> as written, or <code>null</code> where the
   *          model carries none
   */
  public record Unserved(String elementId,
                         String name,
                         String formKey) {
  }

  /**
   * Reads the user tasks of one process which no method serves.
   * <p>
   * Asked with BOTH keys a task is wired by, the way the notification asks at runtime
   * ({@link Camunda7UserTaskEventListener}): a method names the task definition or the element
   * id, and the task definition of a user task is its form key wherever the model carries one.
   * Asking by one key alone would name a task which is served.
   *
   * @param model The BPMN model, carrying the identifiers the ENGINE knows
   * @param scopedBpmnProcessId The process to look at, as the engine knows it
   * @param aMethodNames Whether a <code>&#64;WorkflowTask</code> method of the application names
   *          the given task definition or element id
   * @return The elements, in the order the model carries them, empty where every user task is
   *         served
   */
  public static List<Unserved> of(
      final BpmnModelInstance model,
      final String scopedBpmnProcessId,
      final Predicate<String> aMethodNames) {

    return model
        .getModelElementsByType(UserTask.class)
        .stream()
        .filter(userTask -> scopedBpmnProcessId.equals(owningProcessId(userTask)))
        .map(userTask -> new Unserved(
            userTask.getId(), blankToNull(userTask.getName()), blankToNull(
                Camunda7TaskDefinitions.formKeyOf(userTask))))
        .filter(element -> !aMethodServes(element, aMethodNames))
        .toList();

  }

  /**
   * Whether a method of the application serves one user task. A blank form key counts as none,
   * the way the published rule counts it, so nothing is asked under an empty name.
   *
   * @param element The element
   * @param aMethodNames Whether a method names the given task definition or element id
   * @return Whether either key reaches a method
   */
  private static boolean aMethodServes(
      final Unserved element,
      final Predicate<String> aMethodNames) {

    return ((element.formKey() != null) && aMethodNames.test(element.formKey())) || aMethodNames
        .test(element.elementId());

  }

  /**
   * Which process an element belongs to. An element inside an embedded subprocess belongs to
   * the process around it, and a file may carry several processes.
   *
   * @param element The element
   * @return The id of the process containing it, or <code>null</code>
   */
  private static String owningProcessId(
      final UserTask element) {

    org.camunda.bpm.model.xml.instance.ModelElementInstance current = element;
    while (current != null) {
      if (current instanceof org.camunda.bpm.model.bpmn.instance.Process process) {
        return process.getId();
      }
      current = current.getParentElement();
    }
    return null;

  }

  /**
   * The report about one process, naming each element and the method which would serve it.
   *
   * @param unserved The elements, never empty
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param workflowModuleId The workflow module
   * @return The message, without the adapter id a log line puts in front of it
   */
  public static String report(
      final List<Unserved> unserved,
      final String bpmnProcessId,
      final String workflowModuleId) {

    final var message = new StringBuilder(
        """
            BPMN process '%s' of workflow module '%s' has %d user task(s) which no @WorkflowTask \
            method serves. This engine creates such a task, it appears in a task list and whoever \
            finishes it moves the workflow on, so the workflow runs as modelled. What your \
            application does not get is the notification: no method of it is called when the task \
            is created, and none when the task is canceled. Where you want one, add it to the \
            class which claims this process:"""
            .formatted(bpmnProcessId, workflowModuleId, unserved.size()));
    unserved.forEach(element -> message.append(oneElement(element)));
    message
        .append(
            """

                Where a task list is all these tasks need, this line is the whole story and there is \
                nothing to do about it. VanillaBP completes such a task through \
                ProcessService#completeUserTask whether a method is notified about it or not.""");
    return message.toString();

  }

  /**
   * One element of the report: what to look for in the model, and what to write in the class.
   *
   * @param element The element
   * @return Its line, starting with a line break
   */
  private static String oneElement(
      final Unserved element) {

    final var describedElement = element.name() == null
        ? "user task '%s'".formatted(element.elementId())
        : "user task '%s' (named '%s' in the model)".formatted(element.elementId(), element.name());
    // the form key is the task definition of a user task here, so it is the name a method is
    // written under. Without one the element id is the only key left, and a method named after
    // the element serves it just as well
    return element.formKey() == null
        ? """

              - %s, which carries no form key: add a method annotated with @WorkflowTask(id = "%s"), \
            or name the method '%s'."""
            .formatted(describedElement, element.elementId(), element.elementId())
        : """

              - %s, whose form key is '%s': add a method annotated with \
            @WorkflowTask(taskDefinition = "%s") or @WorkflowTask(id = "%s")."""
            .formatted(describedElement, element.formKey(), element.formKey(), element.elementId());

  }

  /**
   * @param value What the model carries
   * @return It, or <code>null</code> where the model carries nothing readable
   */
  private static String blankToNull(
      final String value) {

    return (value == null) || value.isBlank()
        ? null
        : value;

  }

}
