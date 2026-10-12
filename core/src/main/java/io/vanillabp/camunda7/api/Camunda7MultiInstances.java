package io.vanillabp.camunda7.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.impl.bpmn.behavior.MultiInstanceActivityBehavior;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.camunda.bpm.model.xml.ModelInstance;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.vanillabp.camunda7.wiring.Camunda7CallActivities;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;

/**
 * The multi-instance scopes an execution runs in, outermost first.
 * <p>
 * Camunda 7 keeps the item, the index and the total of a multi-instance activity in
 * variables of the executions below the element. So the answer is found by a walk. It starts
 * at the execution at hand and goes up through its parents and its calling processes. On the
 * way it collects every multi-instance activity. It reads each one in the model of the
 * process which that execution belongs to. That walk is the deepest piece of engine
 * knowledge in this repository. A Camunda upgrade is most likely to break it. So it exists
 * exactly once and is published here. Nobody else who needs it writes it a second time.
 *
 * <h2>What it promises</h2>
 *
 * The map is keyed by the BPMN id of the element carrying the multi-instance
 * characteristics. Its iteration order is outermost first. Take a task inside a
 * multi-instance subprocess, where the task itself iterates too. The map lists the
 * subprocess before the task. A level whose <code>loopCounter</code> or
 * <code>nrOfInstances</code> the engine does not hold is left out, not guessed. An activity
 * whose model declares no element variable yields a <code>null</code> element.
 * <p>
 * The walk crosses a call activity. So a task of a called process is told the iteration of
 * its caller, over as many levels as the models nest. It crosses only where the called
 * process continues the CALLER'S workflow aggregate. The core answers this while the model
 * is deployed, and {@link Camunda7CallActivities} writes it onto the call activity. A
 * process with an aggregate of its own is a business case of its own. It hears nothing about
 * the iteration which called it.
 * <p>
 * A call activity which names the process to call in an expression carries no such answer.
 * The model does not say which process will be called. So the core is asked while the
 * workflow runs, and that needs the registry of this engine. The overloads which take a
 * registry cross such a call activity. The overloads without one end there.
 *
 * <h2>What it does not promise</h2>
 *
 * It promises nothing about an execution the engine no longer holds. A completed or
 * cancelled task answers an empty map. A task which never was in a multi-instance activity
 * gives the same answer. The two cannot be told apart here. A caller which must tell them
 * apart asks the engine's history instead.
 *
 * <p>
 * The promises above are held by <code>Camunda7MultiInstancesTest</code>.
 */
public final class Camunda7MultiInstances {

  /**
   * The attribute naming the variable each iteration gets its item in. It is read by
   * namespace and name, not through the typed getter of the Camunda model API. The Camunda 7
   * forks renamed those getters along with their packages. An attribute read by namespace and
   * name survives that rename.
   */
  private static final String ELEMENT_VARIABLE_ATTRIBUTE = "elementVariable";

  private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";

  private Camunda7MultiInstances() {
  }

  /**
   * The variable in which an element hands over the item of the current round. It is
   * <code>null</code> where the model names none. An element which names none iterates, but
   * never says what the value of the round is called. So a handler asking for that item reads
   * <code>null</code>. The deployment refuses such a pairing, see
   * {@code Camunda7MultiInstanceItems}.
   * <p>
   * It is published here because the deployment reads the same attribute. And reading an
   * attribute by namespace is the one part of this class which a Camunda 7 fork changes.
   *
   * @param loop The multi-instance characteristics of the element
   * @return The variable name, or <code>null</code>
   */
  public static String elementVariableOf(
      final MultiInstanceLoopCharacteristics loop) {

    return loop == null
        ? null
        : loop.getAttributeValueNs(CAMUNDA_NS, ELEMENT_VARIABLE_ATTRIBUTE);

  }

  /**
   * The multi-instance scopes of an execution the engine handed over. A delegate, a listener
   * or a task behaviour has such an execution at hand.
   *
   * @param execution The execution, or <code>null</code>
   * @return The scopes, keyed by BPMN element id and outermost first, never
   *         <code>null</code>
   */
  public static Map<String, MultiInstanceValue> of(
      final DelegateExecution execution) {

    return of(execution, null);

  }

  /**
   * The same scopes, with the registry of the engine this execution runs in at hand.
   * <p>
   * The walk needs it for one kind of call activity: the one which names the process it
   * calls in an expression. Nothing was written onto such a call activity while the model was
   * deployed, because the model does not say which process will be called. So the walk asks
   * through the registry while the workflow runs: does the called process continue the
   * caller's business case? Without the registry the walk ends there. That is the answer this
   * adapter gave before it could ask.
   *
   * @param execution The execution, or <code>null</code>
   * @param taskRegistry The registry of this engine, or <code>null</code>
   * @return The scopes, keyed by BPMN element id and outermost first, never
   *         <code>null</code>
   */
  public static Map<String, MultiInstanceValue> of(
      final DelegateExecution execution,
      final Camunda7TaskRegistry taskRegistry) {

    if (execution == null) {
      return Map.of();
    }
    return collect(execution, taskRegistry);

  }

  /**
   * The multi-instance scopes of an execution named by its id. This is for a caller which has
   * no execution at hand, above all for a user task read from the engine's query API. The
   * walk needs the execution tree, which lives inside an engine command. So this runs one.
   *
   * @param engine The engine holding the execution
   * @param executionId The execution, or <code>null</code>
   * @return The scopes, keyed by BPMN element id and outermost first, never
   *         <code>null</code>
   */
  public static Map<String, MultiInstanceValue> of(
      final ProcessEngine engine,
      final String executionId) {

    return of(engine, executionId, null);

  }

  /**
   * The same scopes of an execution named by its id, with the registry of that engine at
   * hand. See {@link #of(DelegateExecution, Camunda7TaskRegistry)} for what the registry
   * adds. A caller outside this adapter reaches the registry through
   * {@code Camunda7EngineFacts}.
   *
   * @param engine The engine holding the execution
   * @param executionId The execution, or <code>null</code>
   * @param taskRegistry The registry of this engine, or <code>null</code>
   * @return The scopes, keyed by BPMN element id and outermost first, never
   *         <code>null</code>
   */
  public static Map<String, MultiInstanceValue> of(
      final ProcessEngine engine,
      final String executionId,
      final Camunda7TaskRegistry taskRegistry) {

    if ((engine == null) || (executionId == null)) {
      return Map.of();
    }
    return ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
        .getCommandExecutorTxRequired()
        .execute(commandContext -> {
          final var execution = commandContext
              .getExecutionManager()
              .findExecutionById(executionId);
          return execution == null
              ? Map.<String, MultiInstanceValue>of()
              : collect(execution, taskRegistry);
        });

  }

  private static Map<String, MultiInstanceValue> collect(
      final DelegateExecution execution,
      final Camunda7TaskRegistry taskRegistry) {

    // the walk collects innermost first, but the promise above is outermost first
    final var innermostFirst = new LinkedHashMap<String, MultiInstanceValue>();
    var current = execution;
    while (current != null) {
      // the model is taken for EVERY execution, because the walk leaves the process it
      // started in. An execution of the calling process must be looked up in the calling
      // model. Looking it up in the model of the called process loses the level. This walk
      // had that defect from version 1 on
      multiInstanceOf(modelOf(current), current)
          .ifPresent(scope -> innermostFirst.put(scope.elementId(), scope.value()));
      current = nextOf(current, taskRegistry);
    }

    final var outermostFirst = new LinkedHashMap<String, MultiInstanceValue>();
    outermostFirst.putAll(innermostFirst.reversed());
    return java.util.Collections.unmodifiableMap(outermostFirst);

  }

  /**
   * Where the walk goes from here. First it goes up through the scopes of this process
   * definition. Where no parent is left, it goes through the call activity which started it.
   * <p>
   * The step into the calling process is taken only where the called process continues
   * the CALLER'S workflow aggregate. A process with an aggregate of its own runs a business
   * case of its own. The iteration of whoever called it says nothing about it. Nothing of that
   * iteration is lost. The engine keeps it in the executions of the calling process. A model
   * which wants it in the called process reads it like any other variable.
   * <p>
   * Where the model names the called process directly, the answer was written onto the call
   * activity while the model was deployed, and it stands. Where the model names the process
   * in an expression, there was nothing to write the answer onto. So the core is asked now,
   * through the registry of this engine. Without a registry the walk ends there.
   */
  private static DelegateExecution nextOf(
      final DelegateExecution execution,
      final Camunda7TaskRegistry taskRegistry) {

    if (execution.getParentId() != null) {
      return ((ExecutionEntity) execution).getParent();
    }
    final var callingExecution = execution.getSuperExecution();
    if (callingExecution == null) {
      return null;
    }
    final var callActivity = callingExecution.getBpmnModelElementInstance();
    if (Camunda7CallActivities.continuesTheCallersWorkflowAggregate(callActivity)) {
      return callingExecution;
    }
    if (Camunda7CallActivities.theModelSaysWhichProcessIsCalled(callActivity)) {
      // the deployment could answer, and its answer was no. The walk keeps that answer,
      // even where the declarations of the application have changed since
      return null;
    }
    return Camunda7CallActivities
        .continuesTheCallersWorkflowAggregate(execution, callingExecution, taskRegistry)
            ? callingExecution
            : null;

  }

  /**
   * The BPMN model an execution belongs to. That is the model of ITS process definition, not
   * the model the walk started in.
   * <p>
   * The engine answers it for every execution, whether or not that execution stands on an
   * element of its own. So nothing has to be carried along the walk. And nothing depends on
   * where the step into a calling process lands. An execution which is no
   * {@link ExecutionEntity}, above all a test double, is asked through the element it stands
   * on instead. Where there is no element either, the fallback of
   * {@link #currentElementOf(ModelInstance, DelegateExecution)} has nothing to look in and
   * reports no level.
   */
  private static ModelInstance modelOf(
      final DelegateExecution execution) {

    if (execution instanceof final ExecutionEntity entity) {
      final var model = entity.getBpmnModelInstance();
      if (model != null) {
        return model;
      }
    }
    final var element = execution.getBpmnModelElementInstance();
    return element == null
        ? null
        : element.getModelInstance();

  }

  /**
   * One collected level: the element carrying the multi-instance characteristics and what
   * the engine holds for the iteration at hand.
   */
  private record Scope(
                       String elementId,
                       MultiInstanceValue value) {
  }

  private static java.util.Optional<Scope> multiInstanceOf(
      final ModelInstance model,
      final DelegateExecution execution) {

    if (!(currentElementOf(model, execution) instanceof final Activity activity)) {
      return java.util.Optional.empty();
    }
    if (!(activity.getLoopCharacteristics() instanceof final MultiInstanceLoopCharacteristics loop)) {
      return java.util.Optional.empty();
    }
    final var index = execution.getVariable(MultiInstanceActivityBehavior.LOOP_COUNTER);
    final var total = execution.getVariable(MultiInstanceActivityBehavior.NUMBER_OF_INSTANCES);
    if (!(index instanceof final Integer itemNo) || !(total instanceof final Integer totalCount)) {
      return java.util.Optional.empty();
    }
    final var elementVariable = elementVariableOf(loop);
    final var item = elementVariable == null
        ? null
        : execution.getVariable(elementVariable);
    return java.util.Optional
        .of(new Scope(activity.getId(), new MultiInstanceValue(item, itemNo.intValue(), totalCount.intValue())));

  }

  /**
   * The BPMN element an execution stands on. An execution of an embedded subprocess has
   * none of its own. Then its activity instance id names the element as
   * <code>&lt;element id&gt;:&lt;instance id&gt;</code>.
   */
  private static ModelElementInstance currentElementOf(
      final ModelInstance model,
      final DelegateExecution execution) {

    if (execution.getBpmnModelElementInstance() != null) {
      return execution.getBpmnModelElementInstance();
    }
    final var activityInstanceId = execution.getActivityInstanceId();
    if (activityInstanceId == null) {
      return null;
    }
    final var elementMarker = activityInstanceId.indexOf(':');
    return (elementMarker == -1) || (model == null)
        ? null
        : model.getModelElementById(activityInstanceId.substring(0, elementMarker));

  }

}
