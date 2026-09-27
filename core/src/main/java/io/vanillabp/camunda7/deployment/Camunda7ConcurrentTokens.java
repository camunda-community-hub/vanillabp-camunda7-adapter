package io.vanillabp.camunda7.deployment;

import java.util.List;
import java.util.stream.Stream;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.Association;
import org.camunda.bpm.model.bpmn.instance.BoundaryEvent;
import org.camunda.bpm.model.bpmn.instance.CompensateEventDefinition;
import org.camunda.bpm.model.bpmn.instance.EndEvent;
import org.camunda.bpm.model.bpmn.instance.FlowElement;
import org.camunda.bpm.model.bpmn.instance.InclusiveGateway;
import org.camunda.bpm.model.bpmn.instance.IntermediateThrowEvent;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.camunda.bpm.model.bpmn.instance.ParallelGateway;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.SubProcess;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.vanillabp.integration.adapter.spi.workflowtask.CompensationSpec;

/**
 * Finds the elements of a BPMN process which can put a SECOND token into a running
 * workflow. The core is told about them during wiring and decides what it
 * means: two tokens are two branches writing the same workflow aggregate, and an
 * aggregate without a version attribute loses one of the two writes without any error.
 * <p>
 * What is looked for - each one verified against this engine rather than taken from a
 * list:
 * <ul>
 * <li>a boundary event which does NOT cancel its activity, so its branch runs next to
 * the still open activity;</li>
 * <li>a parallel or inclusive gateway forking into more than one sequence flow (one
 * outgoing flow is a joining or pass-through gateway, which forks nothing);</li>
 * <li>an activity marked as a PARALLEL multi-instance (a sequential one holds one
 * token at a time);</li>
 * <li>an event subprocess whose start event does not interrupt the process.</li>
 * </ul>
 */
public class Camunda7ConcurrentTokens {

  private Camunda7ConcurrentTokens() {
  }

  /**
   * The IDs of the elements of the given BPMN process which can produce a second
   * token.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The process' ID as the model knows it (the SCOPED ID)
   * @return The element IDs, possibly empty
   */
  public static List<String> elementIdsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    return Stream
        .of(
            elementsOf(model, bpmnProcessId, BoundaryEvent.class)
                .filter(boundaryEvent -> !boundaryEvent.cancelActivity()),
            elementsOf(model, bpmnProcessId, ParallelGateway.class)
                .filter(gateway -> gateway.getOutgoing().size() > 1),
            elementsOf(model, bpmnProcessId, InclusiveGateway.class)
                .filter(gateway -> gateway.getOutgoing().size() > 1),
            elementsOf(model, bpmnProcessId, Activity.class)
                .filter(Camunda7ConcurrentTokens::isParallelMultiInstance),
            elementsOf(model, bpmnProcessId, SubProcess.class)
                .filter(Camunda7ConcurrentTokens::isNonInterruptingEventSubProcess))
        .flatMap(elements -> elements)
        .map(FlowElement::getId)
        .distinct()
        .toList();

  }

  /**
   * The compensation throw events of the given BPMN process which start MORE THAN ONE
   * handler, each with the handlers it starts.
   * <p>
   * Compensation is a second token drawn differently: from the throw event the workflow holds
   * a token per handler, and every one of those handlers is an ordinary workflow task writing
   * the same workflow aggregate.
   * <p>
   * This engine starts the handlers one after the other rather than next to each other,
   * measured on 7.24 by {@code Camunda7CompensationTokensTest}. The report is made anyway.
   * The question this check asks is whether the process can hold more than one token, and
   * both compensating executions exist from the moment the throw event runs. A handler which
   * WAITS, a user task among them, also keeps its token while the next handler is started,
   * and two such handlers are completed by the application in two transactions which may
   * overlap.
   * <p>
   * A throw event which names ONE activity compensates that activity's handler and nothing
   * else. Such an event is read here and dropped by the caller.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The process' ID as the model knows it (the SCOPED ID)
   * @return The throw events with their handlers, in model order, possibly empty
   */
  public static List<CompensationSpec> compensationOf(
      final BpmnModelInstance model,
      final String bpmnProcessId) {

    final var handlerOfActivity = new java.util.LinkedHashMap<String, Activity>();
    final var compensatedActivities = new java.util.LinkedHashMap<String, Activity>();
    elementsOf(model, bpmnProcessId, BoundaryEvent.class)
        .filter(boundaryEvent -> boundaryEvent
            .getEventDefinitions()
            .stream()
            .anyMatch(CompensateEventDefinition.class::isInstance))
        .forEach(boundaryEvent -> {
          final var compensated = boundaryEvent.getAttachedTo();
          final var handler = handlerAttachedTo(model, boundaryEvent);
          if ((compensated == null) || (handler == null)) {
            return;
          }
          handlerOfActivity.put(compensated.getId(), handler);
          compensatedActivities.put(compensated.getId(), compensated);
        });
    if (handlerOfActivity.isEmpty()) {
      return List.of();
    }

    final var compensations = new java.util.LinkedList<CompensationSpec>();
    Stream
        .of(
            elementsOf(model, bpmnProcessId, IntermediateThrowEvent.class),
            elementsOf(model, bpmnProcessId, EndEvent.class))
        .flatMap(events -> events)
        .forEach(throwEvent -> {
          final var thrown = throwEvent
              .getEventDefinitions()
              .stream()
              .filter(CompensateEventDefinition.class::isInstance)
              .map(CompensateEventDefinition.class::cast)
              .findFirst()
              .orElse(null);
          if (thrown == null) {
            return;
          }
          final var named = thrown.getActivity();
          final List<String> handlers;
          if (named != null) {
            final var handler = handlerOfActivity.get(named.getId());
            handlers = handler == null
                ? List.of()
                : List.of(handler.getId());
          } else {
            // no activity is named, so everything of the throw event's scope which was
            // compensated is compensated at once
            final var scope = compensationScopeOf(throwEvent);
            handlers = compensatedActivities
                .entrySet()
                .stream()
                .filter(compensated -> liesWithin(compensated.getValue(), scope))
                .map(compensated -> handlerOfActivity.get(compensated.getKey()).getId())
                .toList();
          }
          compensations.add(new CompensationSpec(throwEvent.getId(), handlers));
        });
    return compensations;

  }

  /**
   * The compensation handler an association points at, starting from the compensation
   * boundary event. Only an activity marked as one which compensates counts: an association
   * may reach a text annotation as well, and that is documentation rather than a handler.
   */
  private static Activity handlerAttachedTo(
      final BpmnModelInstance model,
      final BoundaryEvent boundaryEvent) {

    return model
        .getModelElementsByType(Association.class)
        .stream()
        .filter(association -> boundaryEvent.equals(association.getSource()))
        .map(Association::getTarget)
        .filter(Activity.class::isInstance)
        .map(Activity.class::cast)
        .filter(Activity::isForCompensation)
        .findFirst()
        .orElse(null);

  }

  /**
   * The scope a compensation throw event which names no activity compensates: the subprocess
   * around it, or the process.
   * <p>
   * An EVENT subprocess is walked through rather than taken as the scope. Such a subprocess
   * handles what happened in the scope AROUND it, so a throw event inside it undoes the
   * activities of that scope and not the ones of the handler it sits in.
   */
  private static ModelElementInstance compensationScopeOf(
      final FlowElement throwEvent) {

    ModelElementInstance current = throwEvent.getParentElement();
    while (current != null) {
      if ((current instanceof SubProcess subProcess) && !subProcess.triggeredByEvent()) {
        return current;
      }
      if (current instanceof Process) {
        return current;
      }
      current = current.getParentElement();
    }
    return null;

  }

  /**
   * Whether an activity sits inside the given scope, at any depth.
   */
  private static boolean liesWithin(
      final Activity activity,
      final ModelElementInstance scope) {

    if (scope == null) {
      return false;
    }
    ModelElementInstance current = activity;
    while (current != null) {
      if (scope.equals(current)) {
        return true;
      }
      current = current.getParentElement();
    }
    return false;

  }

  private static <T extends FlowElement> Stream<T> elementsOf(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final Class<T> type) {

    return model
        .getModelElementsByType(type)
        .stream()
        .filter(element -> bpmnProcessId.equals(Camunda7DeploymentService.owningProcessId(element)));

  }

  private static boolean isParallelMultiInstance(
      final Activity activity) {

    return (activity.getLoopCharacteristics() instanceof MultiInstanceLoopCharacteristics loop) && !loop.isSequential();

  }

  private static boolean isNonInterruptingEventSubProcess(
      final SubProcess subProcess) {

    return subProcess.triggeredByEvent() && subProcess
        .getChildElementsByType(StartEvent.class)
        .stream()
        .anyMatch(startEvent -> !startEvent.isInterrupting());

  }

}
