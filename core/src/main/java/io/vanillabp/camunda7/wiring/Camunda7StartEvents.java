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
 * Tells which trigger a start event carries, and which start events start a WORKFLOW. Two
 * callers need that, and they see it from two sides. The deployment service reads the parsed
 * BPMN model and reports the start events to the core. The parse listener sees the raw XML
 * element while the engine builds its process definition.
 */
public final class Camunda7StartEvents {

  private Camunda7StartEvents() {
  }

  /**
   * Which trigger a start event carries, read from the model while the engine parses it.
   * Every start event has an answer, the plain one included. What a start of a workflow
   * MEANS comes from the state of that workflow, not from the kind of its start event. So
   * the listener sits on all of them. See decision 28 in the repository's DECISIONS.md.
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
   * The same answer for a start event of a parsed model. The deployment service reads it
   * when it tells the core which start events a process has.
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
   * Whether the start event of a parsed model starts the WORKFLOW. Only the start events
   * which the process itself holds do.
   * <p>
   * A start event of an event subprocess fires while the workflow already runs and
   * already has its aggregate. So it starts no workflow. If it counted as one, every model
   * with a timer event subprocess would be refused at startup. And at runtime the
   * application would be asked to build a second aggregate for a workflow which already has
   * one.
   *
   * @param startEvent The start event as the BPMN model carries it
   * @return Whether the process itself holds it
   */
  public static boolean startsTheWorkflow(
      final StartEvent startEvent) {

    return startEvent.getParentElement() instanceof Process;

  }

  /**
   * Whether the start event the engine is parsing starts the WORKFLOW. The answer comes
   * from the scope the engine hands to its parse listeners.
   * <p>
   * The engine parses the start events of an event subprocess in the scope of that
   * subprocess. It parses the start events of the process in the scope of the process
   * definition. The engine draws the same line itself. A start event whose scope is not a
   * process definition becomes a scope start event.
   *
   * @param scope The scope the engine parses the start event in
   * @return Whether that scope is the process itself
   */
  public static boolean startsTheWorkflow(
      final ScopeImpl scope) {

    return scope instanceof ProcessDefinitionEntity;

  }

}
