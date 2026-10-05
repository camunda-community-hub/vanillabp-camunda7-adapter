package io.vanillabp.camunda7.api;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;

/**
 * The root of a workflow, which is the business case a called process belongs to.
 *
 * <h2>The rule, and its one fallback</h2>
 *
 * Camunda 7 records the root process instance of every instance it starts from a call
 * activity. An instance which was not called by anybody has no root recorded, and such an
 * instance IS its own root. That is the whole rule and the fallback is part of it: there is
 * no second fallback and no case in which the answer is empty for an instance the engine
 * knows.
 * <p>
 * The rule stood written at three places with two different fallbacks, which is why it is
 * published here and stated once.
 *
 * <h2>What it does not promise</h2>
 *
 * Nothing about an instance the engine's history does not hold - a history level of
 * <code>none</code>, or an instance whose history was cleaned up. The overload taking a
 * {@link HistoryService} answers the instance id it was given for such an instance, because
 * an instance nobody knows anything about cannot be shown to be a called one. A caller which
 * has to tell "no root recorded" from "no history at all" apart asks the history itself.
 *
 * <p>
 * The rule above is held by <code>Camunda7ExecutionsTest</code>.
 */
public final class Camunda7Executions {

  private Camunda7Executions() {
  }

  /**
   * The instance at the top of a call hierarchy. Camunda leaves the root empty on an
   * instance nobody called, so the answer falls back to the instance's own id and a caller
   * gets one id to group by either way.
   *
   * @param instance The historic process instance, or <code>null</code>
   * @return The id of the root instance, the instance's own id where it has no root, and
   *         <code>null</code> for no instance
   */
  public static String rootProcessInstanceIdOf(
      final HistoricProcessInstance instance) {

    if (instance == null) {
      return null;
    }
    return instance.getRootProcessInstanceId() == null
        ? instance.getId()
        : instance.getRootProcessInstanceId();

  }

  /**
   * The same rule for a caller which is inside the engine and has the execution itself - a
   * listener or a history event handler. The execution's own process instance is the root
   * where the engine recorded none, which is the same sentence in the shape this caller
   * works in.
   *
   * @param execution The execution, or <code>null</code>
   * @return The id of the root instance, the execution's own process instance where it has
   *         no root, and <code>null</code> for no execution
   */
  public static String rootProcessInstanceIdOf(
      final ExecutionEntity execution) {

    if (execution == null) {
      return null;
    }
    return execution.getRootProcessInstanceId() == null
        ? execution.getProcessInstanceId()
        : execution.getRootProcessInstanceId();

  }

  /**
   * The same rule for a caller which has an instance id rather than the instance.
   *
   * @param historyService The engine's history service
   * @param processInstanceId The process instance, or <code>null</code>
   * @return The id of the root instance, and the given id where the history holds no such
   *         instance (see the class comment)
   */
  public static String rootProcessInstanceIdOf(
      final HistoryService historyService,
      final String processInstanceId) {

    if ((historyService == null) || (processInstanceId == null)) {
      return processInstanceId;
    }
    final var instance = historyService
        .createHistoricProcessInstanceQuery()
        .processInstanceId(processInstanceId)
        .singleResult();
    return instance == null
        ? processInstanceId
        : rootProcessInstanceIdOf(instance);

  }

}
