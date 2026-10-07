package io.vanillabp.camunda7.deployment;

import java.util.List;
import java.util.stream.Collectors;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.impl.BpmnModelConstants;
import org.camunda.bpm.model.bpmn.instance.Activity;

/**
 * The activities of a BPMN process which carry a standard loop, and what this adapter says
 * about them.
 * <p>
 * A modeller draws <code>standardLoopCharacteristics</code> to repeat one activity while a
 * condition holds. Camunda 7 does not implement it: the engine deploys the model, runs the
 * activity once and moves on, and it writes no line about it (see
 * {@code Camunda7StandardLoopTest}). So a model this boot deploys is refused where the
 * application claims its process. A process nobody claims is not looked at: it is somebody
 * else's model (see {@code DECISIONS.pending/937.md}). A version the engine
 * already holds is reported where workflows still run on it, because nobody can change that
 * model any more.
 * <p>
 * The model API of the engine has no type for the marker. It keeps the element in the XML all
 * the same, which is where it is looked for.
 */
public final class Camunda7StandardLoops {

  private Camunda7StandardLoops() {
  }

  /**
   * What the engine does with a standard loop, and the two forms which do repeat an activity.
   * Shared by the refusal and the warning, so both say the same.
   */
  private static final String WHAT_THE_ENGINE_DOES = """
      Camunda 7 does not run a standard loop: it runs the activity once, the workflow moves on, \
      and the engine says nothing about it. Two forms do repeat an activity. Draw a loop in the \
      sequence flow: a gateway after the activity leads back to it while the condition holds. Or \
      make the activity a multi-instance element: a handler then learns its round from \
      @MultiInstanceElement, @MultiInstanceIndex and @MultiInstanceTotal.""";

  /**
   * The IDs of the activities of one BPMN process which carry
   * <code>standardLoopCharacteristics</code>, at any depth of its subprocesses, in the order
   * of the model.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The BPMN process ID as the model carries it
   * @return The element IDs, possibly empty
   */
  public static List<String> elementIdsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    return model
        .getModelElementsByType(Activity.class)
        .stream()
        .filter(activity -> bpmnProcessId.equals(Camunda7DeploymentService.owningProcessId(activity)))
        .filter(activity -> !activity
            .getDomElement()
            .getChildElementsByNameNs(BpmnModelConstants.BPMN20_NS, "standardLoopCharacteristics")
            .isEmpty())
        .map(Activity::getId)
        .toList();

  }

  /**
   * The message which ends the deployment of a model carrying a standard loop.
   *
   * @param elementIds The activities carrying one, at least one
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param workflowModuleId The workflow module ID
   * @return The text of the refusal
   */
  public static String refusal(
      final List<String> elementIds,
      final String bpmnProcessId,
      final String workflowModuleId) {

    return """
        BPMN process '%s' of workflow module '%s' carries a standard loop \
        (standardLoopCharacteristics) on %s! %s Change the model to one of the two forms and \
        deploy it again."""
        .formatted(bpmnProcessId, workflowModuleId, described(elementIds), WHAT_THE_ENGINE_DOES);

  }

  /**
   * The warning about a version the engine holds which carries a standard loop.
   *
   * @param elementIds The activities carrying one, at least one
   * @param version The version the engine counts the model under
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param workflowModuleId The workflow module ID
   * @param running How many workflows run on that version, or <code>null</code> where the
   *          engine could not be asked
   * @return The text of the warning
   */
  public static String warningAboutAHeldVersion(
      final List<String> elementIds,
      final String version,
      final String bpmnProcessId,
      final String workflowModuleId,
      final Long running) {

    return """
        Version '%s' of BPMN process '%s' (workflow module '%s') carries a standard loop \
        (standardLoopCharacteristics) on %s, and %s. %s The engine holds that model already, so \
        nobody can change it and the boot goes on. Let those workflows end, or migrate them to a \
        version which uses one of the two forms."""
        .formatted(
            version,
            bpmnProcessId,
            workflowModuleId,
            described(elementIds),
            running == null
                ? "the engine could not say how many workflows still run on it"
                : running == 1L
                    ? "one workflow still runs on it"
                    : "%d workflows still run on it".formatted(running),
            WHAT_THE_ENGINE_DOES);

  }

  private static String described(
      final List<String> elementIds) {

    final var quoted = elementIds
        .stream()
        .collect(Collectors.joining("', '", "'", "'"));
    return elementIds.size() == 1
        ? "the activity %s".formatted(quoted)
        : "the activities %s".formatted(quoted);

  }

}
