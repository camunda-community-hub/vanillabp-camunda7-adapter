package io.vanillabp.camunda7.api;

import org.camunda.bpm.engine.HistoryService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;

/**
 * The root of a workflow. The root is the business case a called process belongs to.
 *
 * <h2>The rule, and its one fallback</h2>
 *
 * Camunda 7 records the root process instance of every instance it starts from a call
 * activity. An instance which nobody called has no root recorded. Such an instance IS its
 * own root. That is the whole rule, and the fallback is part of it. There is no second
 * fallback. For an instance the engine knows, the answer is never empty.
 * <p>
 * The rule was once written at three places, with two different fallbacks. So it is
 * published here and stated once.
 *
 * <h2>What it does not promise</h2>
 *
 * It promises nothing about an instance which the engine's history does not hold. That is
 * the case with a history level of <code>none</code>, or when the history of the instance
 * was cleaned up. For such an instance, the overload taking a {@link HistoryService} answers
 * the instance id it was given. Nobody knows anything about that instance, so nobody can show
 * that it was called. A caller which must tell "no root recorded" from "no history at all"
 * asks the history itself.
 *
 * <p>
 * The rule above is held by <code>Camunda7ExecutionsTest</code>.
 */
public final class Camunda7Executions {

  private Camunda7Executions() {
  }

  /**
   * The instance at the top of a call hierarchy. Camunda leaves the root empty on an
   * instance nobody called. Then the answer falls back to the instance's own id. So a caller
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
   * The same rule for a caller which is inside the engine and has the execution itself, like
   * a listener or a history event handler. Where the engine recorded no root, the root is the
   * execution's own process instance. This is the same rule, in the shape this caller works
   * in.
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
