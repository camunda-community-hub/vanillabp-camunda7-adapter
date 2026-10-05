package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;
import org.camunda.bpm.model.bpmn.instance.ConditionalEventDefinition;
import org.camunda.bpm.model.bpmn.instance.MessageEventDefinition;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.SignalEventDefinition;
import org.camunda.bpm.model.bpmn.instance.StartEvent;
import org.camunda.bpm.model.bpmn.instance.TimerEventDefinition;

import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Tells which trigger a start event carries and which start events start a WORKFLOW. Two
 * callers need that, from two angles: the deployment service reads the parsed BPMN model to
 * report them to the core, and the parse listener sees the raw XML element while the engine
 * builds its process definition.
 */
public final class Camunda7StartEvents {

  private Camunda7StartEvents() {
  }

  /**
   * Which trigger a start event carries, read from the model while the engine parses it.
   * Every start event has an answer, the plain one included: what a start of a workflow
   * MEANS is read from the state of that workflow and not from the kind of its start event,
   * so the listener hangs on all of them - see decision 28 in the repository's
   * DECISIONS.md.
   *
   * @param startEventElement The start event's XML element as the engine's parser
   *          sees it
   * @return Which kind of start event it is
   */
  public static BpmsStartTrigger.Kind kindOf(
      final Element startEventElement) {

    if (startEventElement.element("timerEventDefinition") != null) {
      return BpmsStartTrigger.Kind.TIMER;
    }
    if (startEventElement.element("signalEventDefinition") != null) {
      return BpmsStartTrigger.Kind.SIGNAL;
    }
    if (startEventElement.element("conditionalEventDefinition") != null) {
      return BpmsStartTrigger.Kind.CONDITIONAL;
    }
    if (startEventElement.element("messageEventDefinition") != null) {
      return BpmsStartTrigger.Kind.MESSAGE;
    }
    return BpmsStartTrigger.Kind.NONE;

  }

  /**
   * The same answer for a start event of a parsed model, which is what the deployment
   * service reads when it tells the core which start events a process has.
   *
   * @param startEvent The start event as the BPMN model carries it
   * @return Which kind of start event it is
   */
  public static BpmsStartTrigger.Kind kindOf(
      final StartEvent startEvent) {

    final var definitions = startEvent.getEventDefinitions();
    if (definitions.stream().anyMatch(TimerEventDefinition.class::isInstance)) {
      return BpmsStartTrigger.Kind.TIMER;
    }
    if (definitions.stream().anyMatch(SignalEventDefinition.class::isInstance)) {
      return BpmsStartTrigger.Kind.SIGNAL;
    }
    if (definitions.stream().anyMatch(ConditionalEventDefinition.class::isInstance)) {
      return BpmsStartTrigger.Kind.CONDITIONAL;
    }
    if (definitions.stream().anyMatch(MessageEventDefinition.class::isInstance)) {
      return BpmsStartTrigger.Kind.MESSAGE;
    }
    return BpmsStartTrigger.Kind.NONE;

  }

  /**
   * Whether the start event of a parsed model starts the WORKFLOW, which is true of the
   * start events the process itself holds and of no other.
   * <p>
   * A start event of an event subprocess fires while the workflow already runs and
   * already has its aggregate, so it starts no workflow. Counting it as one would refuse
   * every model with a timer event subprocess at startup, and would ask the application at
   * runtime to build a second aggregate for a workflow it already owns one for.
   *
   * @param startEvent The start event as the BPMN model carries it
   * @return Whether the process itself holds it
   */
  public static boolean startsTheWorkflow(
      final StartEvent startEvent) {

    return startEvent.getParentElement() instanceof Process;

  }

  /**
   * Whether the start event the engine is parsing starts the WORKFLOW, read from the
   * scope the engine hands to its parse listeners.
   * <p>
   * The engine parses the start events of an event subprocess in the scope of that
   * subprocess and the start events of the process in the scope of the process
   * definition, and it draws the same line itself: a start event whose scope is no process
   * definition becomes a scope start event.
   *
   * @param scope The scope the engine parses the start event in
   * @return Whether that scope is the process itself
   */
  public static boolean startsTheWorkflow(
      final ScopeImpl scope) {

    return scope instanceof ProcessDefinitionEntity;

  }

}
