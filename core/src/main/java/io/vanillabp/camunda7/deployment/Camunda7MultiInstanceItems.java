package io.vanillabp.camunda7.deployment;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;

import io.vanillabp.camunda7.api.Camunda7MultiInstances;

/**
 * The multi-instance elements of a BPMN process which hand no item over, and the refusal a
 * handler asking for one deserves.
 * <p>
 * A multi-instance element tells a handler three things: which round it is in, how many
 * rounds there are, and the item of the round. Camunda 7 counts the first two by itself.
 * The item is the variable the model names in <code>camunda:elementVariable</code>. An
 * element which names none never says what the value of the round is called. A
 * <code>&#64;MultiInstanceElement</code> for it then receives <code>null</code> while the
 * workflow runs. The everyday case is an element based on a cardinality: it iterates a
 * number of times, over nothing at all.
 * <p>
 * Such a model is legitimate on its own. So is a handler which reads only the index and the
 * total. Nobody means the two together. So the refusal needs both halves: this adapter
 * reads the BPMN, and the core answers which elements a handler wants the item of.
 */
public final class Camunda7MultiInstanceItems {

  private Camunda7MultiInstanceItems() {
  }

  /**
   * The IDs of the multi-instance elements ENCLOSING the given BPMN element which name no
   * <code>camunda:elementVariable</code>, outermost first.
   * <p>
   * Only the chain of ONE element is read. This is the only reading of the question which
   * holds. A handler gets the item of the rounds which its own element runs in. An element
   * of another branch never reaches it, whatever that element names. Reading the whole
   * process instead would refuse a model which is right. Camunda 8 has always read it this
   * way, too.
   * <p>
   * A level which a CALLER adds is not in this model. The core knows that an element which
   * the model does not carry belongs to a caller.
   * <p>
   * The element itself is part of the chain when it is multi-instance. This is the usual
   * case of a multi-instance service task.
   *
   * @param element The BPMN element a handler serves, or <code>null</code> where the model
   *          carries no element under that id
   * @return The element IDs, outermost first, possibly empty
   */
  public static List<String> elementsWithoutAnItemAround(
      final org.camunda.bpm.model.xml.instance.ModelElementInstance element) {

    final var innermostFirst = new java.util.LinkedList<String>();
    org.camunda.bpm.model.xml.instance.ModelElementInstance current = element;
    while (current != null) {
      if ((current instanceof Activity activity) && (activity
          .getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics loop) && (Camunda7MultiInstances
              .elementVariableOf(loop) == null)) {
        innermostFirst.add(activity.getId());
      }
      current = current.getParentElement();
    }
    java.util.Collections.reverse(innermostFirst);
    return List.copyOf(innermostFirst);

  }

  /**
   * One handler asking for an item its model never hands over.
   *
   * @param taskElementId The BPMN element the handler serves
   * @param taskDefinition The task definition it was wired by
   * @param elementIds The multi-instance elements it wants the item of, all of them
   *          without one
   */
  public record Finding(
                        String taskElementId,
                        String taskDefinition,
                        Collection<String> elementIds) {
  }

  /**
   * The message every finding of one BPMN process is reported in.
   *
   * @param findings What the model cannot answer, at least one
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param workflowModuleId The workflow module ID
   * @return The text of the refusal
   */
  public static String refusal(
      final List<Finding> findings,
      final String bpmnProcessId,
      final String workflowModuleId) {

    final var message = new StringBuilder(
        """
            A @WorkflowTask method of BPMN process '%s' (workflow module '%s') asks for the item \
            of an iteration which has no item to give!"""
            .formatted(bpmnProcessId, workflowModuleId));
    findings
        .forEach(finding -> message
            .append(
                """

                      - the method serving task '%s' (task definition '%s') declares \
                    @MultiInstanceElement for %s no 'camunda:elementVariable', so nothing says \
                    what the value of a round is called."""
                    .formatted(
                        finding.taskElementId(),
                        finding.taskDefinition(),
                        described(finding.elementIds()))));
    message
        .append(
            """

                Write that attribute on the multi-instance characteristics of each element named \
                here (<bpmn:multiInstanceLoopCharacteristics camunda:collection="${items}" \
                camunda:elementVariable="item">). An element which iterates a number of times \
                walks over nothing at all, so it needs a collection before it has an item to \
                name. Or drop the parameter: @MultiInstanceIndex and @MultiInstanceTotal are \
                answered by every multi-instance element, whatever it iterates. Without one of \
                the two the parameter receives null as soon as a workflow reaches the task, and \
                nothing says why.""");
    return message.toString();

  }

  /**
   * The elements of one finding as the message names them. The text ends in the verb which
   * agrees with their number. So the sentence around it reads as one sentence either way.
   */
  private static String described(
      final Collection<String> elementIds) {

    final var quoted = elementIds
        .stream()
        .collect(Collectors.joining("', '", "'", "'"));
    return elementIds.size() == 1
        ? "the element %s, which names".formatted(quoted)
        : "the elements %s, which name".formatted(quoted);

  }

}
