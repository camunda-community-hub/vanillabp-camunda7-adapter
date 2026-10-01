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
 * variables of the executions the element hangs below, so the answer is found by walking
 * from the execution at hand up through its parents and its calling processes and collecting
 * every multi-instance activity on the way, each one read in the model of the process that
 * execution belongs to. That walk is the deepest piece of engine
 * knowledge in this repository and the one a Camunda upgrade is most likely to invalidate,
 * which is why it exists exactly once and is published here rather than written a second
 * time by whoever else needs it.
 *
 * <h2>What it promises</h2>
 *
 * The map is keyed by the BPMN id of the element carrying the multi-instance
 * characteristics, and its iteration order is outermost first: a task inside a
 * multi-instance subprocess which itself iterates lists the subprocess before the task. A
 * level whose <code>loopCounter</code> or <code>nrOfInstances</code> the engine does not
 * hold is left out rather than guessed, and an activity whose model declares no element
 * variable yields a <code>null</code> element.
 * <p>
 * The walk crosses a call activity, so a task of a called process is told the iteration of
 * its caller, over as many levels as the models nest. It crosses where the called process
 * continues the CALLER'S workflow aggregate, which the core answers while the model is
 * deployed and {@link Camunda7CallActivities} writes onto the call activity. A process
 * with an aggregate of its own is a business case of its own and hears nothing about the
 * iteration which called it.
 * <p>
 * A call activity which names the process to call in an expression carries no such answer,
 * because the model does not say which process will be called. The core is asked for it
 * while the workflow runs, which needs the registry of this engine: the overloads taking
 * one cross such a call activity, the overloads without one end there.
 *
 * <h2>What it does not promise</h2>
 *
 * Nothing about an execution the engine no longer holds: a completed or cancelled task
 * answers an empty map, which is the same answer a task that never was in a multi-instance
 * activity gives. The two cannot be told apart here, and a caller which has to tell them
 * apart asks the engine's history instead.
 *
 * <p>
 * The promises above are held by <code>Camunda7MultiInstancesTest</code>.
 */
public final class Camunda7MultiInstances {

  /**
   * The attribute naming the variable each iteration gets its item in. It is read
   * namespace-generically rather than through the typed getter of the Camunda model API:
   * the Camunda 7 forks renamed those getters along with their packages, and an attribute
   * read by namespace and name survives that rename.
   */
  private static final String ELEMENT_VARIABLE_ATTRIBUTE = "elementVariable";

  private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";

  private Camunda7MultiInstances() {
  }

  /**
   * The variable an element hands the item of the current round over in, or
   * <code>null</code> where the model names none. An element which names none iterates
   * without ever saying what the value of the round is called, so a handler asking for
   * that item reads <code>null</code>; the deployment refuses such a pairing, see
   * {@code Camunda7MultiInstanceItems}.
   * <p>
   * Published here because the deployment reads the same attribute, and reading an
   * attribute by namespace is the one part of this class a Camunda 7 fork changes.
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
   * The multi-instance scopes of an execution the engine handed over, which is what a
   * delegate, a listener or a task behaviour has at hand.
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
   * The walk needs it for one call activity: the one which names the process it calls in an
   * expression. Nothing was written onto such a call activity while the model was deployed,
   * because the model does not say which process will be called, so the question whether
   * the called process continues the caller's business case is asked through the registry
   * while the workflow runs. Without the registry the walk ends there, which is the answer
   * this adapter gave before it could ask.
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
   * The multi-instance scopes of an execution named by its id, for a caller which has no
   * execution at hand - a user task read from the engine's query API above all. The walk
   * needs the execution tree, which lives inside an engine command, so this runs one.
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
   * hand - see {@link #of(DelegateExecution, Camunda7TaskRegistry)} for what the registry
   * buys. A caller outside this adapter reaches the registry through
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

    // the walk collects innermost first - the promise above is outermost first
    final var innermostFirst = new LinkedHashMap<String, MultiInstanceValue>();
    var current = execution;
    while (current != null) {
      // the model is taken for EVERY execution, because the walk leaves the process it
      // started in: an execution of the calling process has to be looked up in the
      // calling model, and looking it up in the model of the called process loses the
      // level (the defect this walk carried from version 1 on)
      multiInstanceOf(modelOf(current), current)
          .ifPresent(scope -> innermostFirst.put(scope.elementId(), scope.value()));
      current = nextOf(current, taskRegistry);
    }

    final var outermostFirst = new LinkedHashMap<String, MultiInstanceValue>();
    outermostFirst.putAll(innermostFirst.reversed());
    return java.util.Collections.unmodifiableMap(outermostFirst);

  }

  /**
   * Where the walk goes from here: upwards through the scopes of this process definition
   * first, and where there is no parent left through the call activity which started it.
   * <p>
   * The step into the calling process is taken only where the called process continues
   * the CALLER'S workflow aggregate. A process with an aggregate of its own runs a
   * business case of its own, and the iteration of whoever called it says nothing about
   * it. Nothing of that iteration is lost: the engine keeps it in the executions of the
   * calling process, where a model which wants it in the called process reads it the way
   * it reads any other variable.
   * <p>
   * Where the model spells the called process out, that answer was written onto the call
   * activity while the model was deployed and it stands. Where the model names the process
   * in an expression there was nothing to write it onto, so the core is asked now, through
   * the registry of this engine. Without a registry the walk ends there.
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
      // the deployment could answer and its answer was no, which the walk keeps even where
      // the declarations of the application have changed since
      return null;
    }
    return Camunda7CallActivities
        .continuesTheCallersWorkflowAggregate(execution, callingExecution, taskRegistry)
            ? callingExecution
            : null;

  }

  /**
   * The BPMN model an execution belongs to, which is the model of ITS process definition
   * rather than the model the walk started in.
   * <p>
   * The engine answers it for every execution, whether or not that execution stands on an
   * element of its own, so nothing has to be carried along the walk and nothing depends on
   * what the step into a calling process lands on. An execution which is no
   * {@link ExecutionEntity}, a test double above all, is asked through the element it
   * stands on instead. Where there is no element either, the fallback of
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
   * none of its own, and its activity instance id then names the element as
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
