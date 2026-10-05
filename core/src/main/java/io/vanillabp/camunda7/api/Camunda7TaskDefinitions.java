package io.vanillabp.camunda7.api;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.task.TaskDefinition;
import org.camunda.bpm.model.bpmn.instance.UserTask;

/**
 * What a user task is called.
 *
 * <h2>The rule</h2>
 *
 * The task definition is the <code>camunda:formKey</code> of the user task, and the BPMN id
 * of the element where the model carries no form key. The form key wins wherever it is
 * there, a blank one counts as none, and the element id is the fallback rather than the
 * other way round.
 *
 * <h2>What an expression form key resolves to</h2>
 *
 * A form key which is an expression resolves to ITS OWN TEXT, the string the modeller wrote:
 * <code>${formOf(task)}</code> stays <code>${formOf(task)}</code>. It is never the value the
 * engine computes from it. The computed value differs per workflow instance, so one task
 * would reach a consumer under as many identities as it has instances, and the same task
 * read while the model is parsed and read while a workflow runs would not look like the same
 * task. This is the sentence two copies of the rule got wrong, which is why the reading of
 * the written text lives here as well: {@link #formKeyOf(UserTask)} reads it from the model,
 * {@link #formKeyOf(TaskDefinition)} from what the engine parsed, and
 * {@link #formKeyOf(ProcessEngine, String, String)} from a definition the engine holds. All
 * three answer the same string for the same task.
 *
 * <h2>What it does not promise</h2>
 *
 * Nothing about what this adapter REPORTS. The adapter leaves the task definition of a user
 * task without a form key empty and does not fill in the element id, so
 * {@link #of(String, String)} is the published rule, not a description of what the adapter
 * writes into its own wiring. A story which changes the reported value is a change of
 * behaviour and is not this one.
 *
 * <p>
 * The rules above are held by <code>Camunda7TaskDefinitionsTest</code>.
 */
public final class Camunda7TaskDefinitions {

  private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";

  private static final String FORM_KEY_ATTRIBUTE = "formKey";

  private Camunda7TaskDefinitions() {
  }

  /**
   * The rule, applied.
   *
   * @param formKey The form key AS WRITTEN in the model, or <code>null</code>
   * @param elementId The BPMN id of the user task
   * @return The form key where there is one, the element id otherwise
   */
  public static String of(
      final String formKey,
      final String elementId) {

    return (formKey == null) || formKey.isBlank()
        ? elementId
        : formKey;

  }

  /**
   * The form key as the modeller wrote it, read from the model.
   * <p>
   * Read namespace-generically rather than through the typed getter of the Camunda model
   * API: the Camunda 7 forks renamed those getters along with their packages, and an
   * attribute read by namespace and name survives that rename.
   *
   * @param userTask The user task of a BPMN model, or <code>null</code>
   * @return The written form key, or <code>null</code> where the model carries none
   */
  public static String formKeyOf(
      final UserTask userTask) {

    return userTask == null
        ? null
        : userTask.getAttributeValueNs(CAMUNDA_NS, FORM_KEY_ATTRIBUTE);

  }

  /**
   * The form key as the modeller wrote it, read from what the engine parsed. This is the
   * value a parse listener and a built-in task listener have at hand, and it is the same
   * string {@link #formKeyOf(UserTask)} answers for that task.
   *
   * @param taskDefinition The engine's parsed user task, or <code>null</code>
   * @return The written form key, or <code>null</code> where the model carries none
   */
  public static String formKeyOf(
      final TaskDefinition taskDefinition) {

    if (taskDefinition == null) {
      return null;
    }
    final var formKey = taskDefinition.getFormKey();
    return formKey == null
        ? null
        : formKey.getExpressionText();

  }

  /**
   * The form key as the modeller wrote it, for a task of a process definition the engine
   * holds. This is the way in for a caller which has a running or a finished task and
   * therefore only its two identifiers: asking the task itself would answer the EVALUATED
   * form key and produce the second identity this class exists to prevent.
   *
   * @param engine The engine holding the definition
   * @param processDefinitionId The engine's process definition id
   * @param taskDefinitionKey The BPMN id of the user task
   * @return The written form key, or <code>null</code> where the model carries none or the
   *         engine does not hold that definition any more
   */
  public static String formKeyOf(
      final ProcessEngine engine,
      final String processDefinitionId,
      final String taskDefinitionKey) {

    if ((engine == null) || (processDefinitionId == null) || (taskDefinitionKey == null)) {
      return null;
    }
    final var configuration = (ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration();
    return configuration
        .getCommandExecutorTxRequired()
        .execute(commandContext -> {
          try {
            return formKeyOf(
                configuration
                    .getDeploymentCache()
                    .findDeployedProcessDefinitionById(processDefinitionId)
                    .getTaskDefinitions()
                    .get(taskDefinitionKey));
          } catch (final org.camunda.bpm.engine.exception.NotFoundException e) {
            // the engine answers a definition id it does not hold by throwing rather than
            // by returning nothing, and a definition somebody deleted while its history
            // stayed is a normal thing to be asked about. Everything else stays an error
            return null;
          }
        });

  }

}
