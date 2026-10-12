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
 * The task definition is the <code>camunda:formKey</code> of the user task. Where the model
 * carries no form key, it is the BPMN id of the element. The form key wins wherever it is
 * there, and a blank one counts as none. The element id is only the fallback.
 *
 * <h2>What an expression form key resolves to</h2>
 *
 * A form key which is an expression resolves to ITS OWN TEXT, the string the modeller wrote.
 * So <code>${formOf(task)}</code> stays <code>${formOf(task)}</code>. It is never the value
 * the engine computes from it. The computed value differs per workflow instance. So one task
 * would reach a consumer under as many identities as it has instances. And the same task
 * would look different when read while the model is parsed and when read while a workflow
 * runs. Two copies of the rule got this wrong. So the reading of the written text lives here
 * as well. {@link #formKeyOf(UserTask)} reads it from the model,
 * {@link #formKeyOf(TaskDefinition)} from what the engine parsed, and
 * {@link #formKeyOf(ProcessEngine, String, String)} from a definition the engine holds. All
 * three answer the same string for the same task.
 *
 * <h2>What it does not promise</h2>
 *
 * It promises nothing about what this adapter REPORTS. For a user task without a form key,
 * the adapter leaves the task definition empty and does not fill in the element id. So
 * {@link #of(String, String)} is the published rule. It does not describe what the adapter
 * writes into its own wiring. Changing the reported value would be a change of behaviour.
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
   * It is read by namespace and name, not through the typed getter of the Camunda model API.
   * The Camunda 7 forks renamed those getters along with their packages. An attribute read by
   * namespace and name survives that rename.
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
   * The form key as the modeller wrote it, read from what the engine parsed. A parse listener
   * and a built-in task listener have this value at hand. It is the same string
   * {@link #formKeyOf(UserTask)} answers for that task.
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
   * holds. This is the way in for a caller which has a running or a finished task, and so only
   * its two identifiers. Asking the task itself would answer the EVALUATED form key. That
   * would produce the second identity which this class exists to prevent.
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
            // the engine throws for a definition id it does not hold, it does not return
            // nothing. And a definition somebody deleted while its history stayed is a
            // normal thing to be asked about. Everything else stays an error
            return null;
          }
        });

  }

}
