package io.vanillabp.camunda7.wiring;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaExecutionListener;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

/**
 * The execution listeners somebody MODELLED, and how this adapter tells them apart from the
 * listeners VanillaBP and its extensions attach themselves.
 *
 * <h2>What a modelled listener is</h2>
 *
 * A {@code camunda:executionListener} of the BPMN, written with a
 * {@code camunda:expression} or a {@code camunda:delegateExpression} - the two forms whose
 * expression text names a task definition, which is what a {@code @WorkflowTask} method is
 * matched by on this adapter. Those two are served when the application asks for it.
 * <p>
 * A {@code camunda:class} and a {@code camunda:script} listener are NOT among them and
 * {@code allow-listeners} is not the way out for either: there is no expression naming a task
 * definition, so no method can be matched, and the engine runs such a listener itself. So
 * nothing stalls and nothing is silent - which is why they cost a line of the startup report
 * rather than the boot.
 * <p>
 * A {@code camunda:taskListener} is not read at all. VanillaBP notifies the
 * {@code @WorkflowTask} method of a user task itself, on creation and on cancellation,
 * through listeners it attaches to the parsed element; a second route into the same moment
 * would be two mechanisms for one thing.
 *
 * <h2>How the adapter's own listeners are kept out</h2>
 *
 * Every listener VanillaBP attaches - the cancellation listener, the user-task listeners, the
 * listener of a start event the engine fires on its own and the one reporting a workflow's end
 * - is attached to the element the engine PARSED, never written into the BPMN. An extension
 * reaches the engine the same way, through {@link io.vanillabp.camunda7.engine.Camunda7EngineCustomizer}
 * (see decision 14 in the repository's DECISIONS.md). The collection below reads the BPMN, so
 * it sees what a modeller wrote and nothing else: the separation is by construction rather
 * than by a prefix somebody has to keep up to date.
 */
public final class Camunda7Listeners {

  private Camunda7Listeners() {
  }

  /**
   * The property switching the whole rule on, without its adapter id.
   */
  public static final String ALLOW_LISTENERS_KEY = "allow-listeners";

  /**
   * The <code>camunda:event</code> of an execution listener which the engine fires when the
   * element ends, as the model spells it.
   * <p>
   * This engine fires an END execution listener when the element is CANCELED as well, which is
   * how {@link Camunda7TaskCancellationListener} hears a cancellation at all. So a modeller who
   * wrote one already hears the cancellation, and VanillaBP adds none beside it.
   */
  public static final String EVENT_END = "end";

  /**
   * How the BPMN wires a modelled listener.
   */
  public enum Implementation {
    /**
     * {@code camunda:expression}: the engine evaluates the expression, the handler runs
     * while it is evaluated.
     */
    EXPRESSION("camunda:expression"),
    /**
     * {@code camunda:delegateExpression}: the engine resolves the expression to a listener
     * object, which is the one this adapter hands it.
     */
    DELEGATE_EXPRESSION("camunda:delegateExpression"),
    /**
     * {@code camunda:class}: the engine instantiates the class. Not served here.
     */
    CLASS("camunda:class"),
    /**
     * {@code camunda:script}: the engine runs the script. Not served here.
     */
    SCRIPT("camunda:script");

    private final String attribute;

    Implementation(
        final String attribute) {

      this.attribute = attribute;

    }

    /**
     * How the model spells this form. A message about a listener has to name the attribute
     * somebody wrote, not the word this enum uses for it.
     *
     * @return The BPMN attribute a message names
     */
    public String attribute() {

      return attribute;

    }

    /**
     * Whether the application can serve this listener at all. Only the two expression
     * forms reach the EL resolver; a listener naming a class is the engine's business and
     * VanillaBP leaves it alone.
     *
     * @return Whether a <code>@WorkflowTask</code> method can serve this form
     */
    public boolean servable() {

      return (this == EXPRESSION) || (this == DELEGATE_EXPRESSION);

    }

  }

  /**
   * One execution listener of a model.
   *
   * @param bpmnProcessId The BPMN process the element belongs to, as the model spells it at
   *          the moment of reading
   * @param elementId The BPMN element the listener sits on
   * @param event The listener's <code>camunda:event</code>, as the model spells it
   * @param implementation How the BPMN wires it
   * @param rawExpression The expression as it stands in the model, or the class respectively
   *          the script language for the forms which carry none
   * @param taskDefinition The unwrapped expression text, which is the task definition a
   *          <code>@WorkflowTask</code> method names - <code>null</code> for a form this
   *          adapter does not serve and for an expression it cannot read
   */
  public record ModelledListener(
                                 String bpmnProcessId,
                                 String elementId,
                                 String event,
                                 Implementation implementation,
                                 String rawExpression,
                                 String taskDefinition) {

    /**
     * The listener in one line, so a message can list several of them and somebody can
     * find each one in the model.
     *
     * @return The listener as one line of a message
     */
    public String describe() {

      return "process '%s', element '%s', execution listener on '%s', %s '%s'"
          .formatted(bpmnProcessId, elementId, event, implementation.attribute(), rawExpression);

    }

  }

  /**
   * The adapter-level key which switches the rule on, spelled out for a message.
   *
   * @param adapterId The adapter ID
   * @return {@code vanillabp.adapters.<id>.allow-listeners}
   */
  public static String propertyKeyOf(
      final String adapterId) {

    return "vanillabp.adapters.%s.%s".formatted(adapterId, ALLOW_LISTENERS_KEY);

  }

  /**
   * The three levels the property is read at, as a copy-pasteable block for a message.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module id, or <code>&lt;m&gt;</code> where the
   *          message speaks about no particular module
   * @param bpmnProcessId The BPMN process id, or <code>&lt;w&gt;</code> where the message
   *          speaks about no particular process
   * @return Three indented lines, the least specific level first
   */
  public static String levelsOf(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return """
        vanillabp.adapters.%s.%s
        vanillabp.workflow-modules.%s.adapters.%s.%s
        vanillabp.workflow-modules.%s.workflows.%s.adapters.%s.%s"""
        .formatted(
            adapterId,
            ALLOW_LISTENERS_KEY,
            workflowModuleId,
            adapterId,
            ALLOW_LISTENERS_KEY,
            workflowModuleId,
            bpmnProcessId,
            adapterId,
            ALLOW_LISTENERS_KEY);

  }

  /**
   * The line which opens and closes the startup report. Plain ASCII, so a log viewer without
   * a font for box drawing shows a line rather than a row of question marks, and nothing else
   * this adapter logs is framed: a second framed message would cost this one its effect.
   */
  public static final String FRAME_LINE = "=".repeat(100);

  /**
   * What an application gives up while its modelled listeners are served, in the words of the
   * startup report. Kept here because the same sentences belong into the refusal a boot
   * WITHOUT the property writes, and a reader must meet them in both places unchanged.
   * <p>
   * Word for word what the Camunda 8 adapter says, because the sentences are about VanillaBP
   * rather than about an engine, and a developer moving a module between the two must not have
   * to work out whether two wordings mean the same thing.
   */
  public static final String WHAT_IT_COSTS = """
      A listener is where a BPMS lets an application in at a moment the BPMS owns, and every \
      BPMS draws that moment differently. So the model stops being portable: another BPMS has \
      no listener at this element, and a migration of the model stops at the method serving it. \
      The Process-Engine-API has no listener concept at all, which is gap 16 and 17 of that \
      adapter's GAPS.md. The event is part of a listener's identity here, so one @WorkflowTask \
      method serves one event of one element. A listener knows two events and no more: @TaskEvent \
      receives CREATED when the modelled listener fires, and CANCELED when the element it sits on \
      is canceled. A listener on any other moment of its element still arrives as CREATED.""";

  /**
   * How a served listener hears that its element was canceled, in the words of the startup
   * report.
   */
  public static final String HOW_A_CANCELLATION_IS_REPORTED = """
      VanillaBP tells your method when the element the listener sits on is canceled, and it \
      arrives as CANCELED at the same method. A listener you modelled on 'end' gets none: this \
      engine fires an END execution listener on a cancellation too, so such a method already hears \
      the moment, and a second report would be the same moment twice. A listener on a sequence \
      flow gets none either, because a sequence flow is never canceled.""";

  /**
   * Whether a listener somebody modelled hears the cancellation of its element through itself.
   * Such a listener needs nothing from VanillaBP: the engine fires an END execution listener when
   * the element is canceled as well, so the method would hear the same moment twice.
   *
   * @param listener The listener
   * @return Whether the modeller put it on the moment which covers a cancellation
   */
  public static boolean isACancellation(
      final ModelledListener listener) {

    return EVENT_END.equals(listener.event());

  }

  /**
   * The sentence about the one ambiguity this design leaves, said wherever listeners are
   * named: an element may carry two listeners, and the element id names the element.
   */
  public static final String WHICH_METHOD_SERVES_WHICH = """
      A method serves a listener by naming its TASK DEFINITION and in no other way. \
      @WorkflowTask(id = ...) names the ELEMENT, and one element may carry a task and a listener \
      at once, so the element id cannot say which of them a method means. A listener no method \
      names is not served here: whatever else resolves it keeps resolving it.""";

  /**
   * Reports keys the application set at TASK level, where this one does not resolve, and lets
   * the boot go on: the value changes no answer, and ending a boot over a key which simply
   * does nothing would be worse than saying so.
   *
   * @param adapterId The adapter ID
   * @param keysAtTaskLevel The keys found, fully spelled out; nothing is reported for an empty
   *          list
   * @param warnLogger Where the guidance goes
   */
  public static void reportKeysSetAtTaskLevel(
      final String adapterId,
      final List<String> keysAtTaskLevel,
      final Consumer<String> warnLogger) {

    if (keysAtTaskLevel.isEmpty()) {
      return;
    }
    warnLogger.accept(
        """
            Camunda 7 adapter '%s' has '%s' set at TASK level: %s. That level does not resolve this \
            key and the value changes nothing. A task level is keyed by a task DEFINITION, and \
            whether a modelled listener becomes a task at all is what this key decides - so the \
            task definition such a level would need does not exist yet at the moment the key is \
            read. The key is read at these three levels, the most specific configured one winning:
            %s"""
            .formatted(
                adapterId,
                ALLOW_LISTENERS_KEY,
                String.join(", ", keysAtTaskLevel),
                levelsOf(adapterId, "<m>", "<w>")));

  }

  /**
   * Every execution listener of one process, in document order - the servable ones and the
   * others alike, because a report has to name both.
   * <p>
   * Read off the BPMN rather than off a list of element types, so a listener reaches this
   * wherever a modeller put one. A listener of VanillaBP or of one of its extensions is not
   * among them, see the class comment.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The process id as it stands in the model at this point
   * @param expressionReader Turns the expression of the model into a task definition, and
   *          answers <code>null</code> for one it cannot read
   * @return The listeners, in document order
   */
  public static List<ModelledListener> listenersOf(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final java.util.function.UnaryOperator<String> expressionReader) {

    final var found = new LinkedList<ModelledListener>();
    for (final var listener : model.getModelElementsByType(CamundaExecutionListener.class)) {
      final var elementId = owningElementIdOf(listener);
      if ((elementId == null) || !bpmnProcessId.equals(owningProcessIdOf(listener))) {
        continue;
      }
      final var event = listener.getCamundaEvent();
      final var delegateExpression = listener.getCamundaDelegateExpression();
      final var expression = listener.getCamundaExpression();
      if ((delegateExpression != null) && !delegateExpression.isBlank()) {
        found.add(new ModelledListener(
            bpmnProcessId, elementId, event, Implementation.DELEGATE_EXPRESSION, delegateExpression, expressionReader
                .apply(delegateExpression)));
        continue;
      }
      if ((expression != null) && !expression.isBlank()) {
        found.add(new ModelledListener(
            bpmnProcessId, elementId, event, Implementation.EXPRESSION, expression, expressionReader
                .apply(expression)));
        continue;
      }
      final var listenerClass = listener.getCamundaClass();
      if ((listenerClass != null) && !listenerClass.isBlank()) {
        found.add(new ModelledListener(
            bpmnProcessId, elementId, event, Implementation.CLASS, listenerClass, null));
        continue;
      }
      if (listener.getCamundaScript() != null) {
        found.add(new ModelledListener(
            bpmnProcessId, elementId, event, Implementation.SCRIPT, listener
                .getCamundaScript()
                .getCamundaScriptFormat(), null));
      }
    }
    return found;

  }

  /**
   * The listeners of one element which share a task definition, which is the one case a served
   * listener cannot be wired in: one method would serve two events and nothing could tell it
   * which one it is being called for.
   *
   * @param listeners The servable listeners of one process
   * @return Per element and task definition the listeners sharing it, only where there is more
   *         than one
   */
  public static List<List<ModelledListener>> listenersSharingATaskDefinition(
      final List<ModelledListener> listeners) {

    final var byElementAndTaskDefinition = new LinkedHashMap<String, List<ModelledListener>>();
    listeners
        .forEach(listener -> byElementAndTaskDefinition
            .computeIfAbsent(
                listener.elementId()
                    + "|"
                    + listener.taskDefinition(),
                key -> new LinkedList<>())
            .add(listener));
    return byElementAndTaskDefinition
        .values()
        .stream()
        .filter(sharing -> sharing.size() > 1)
        .collect(Collectors.toList());

  }

  /**
   * The id of the BPMN element a listener sits on - a listener lives inside
   * {@code bpmn:extensionElements}, which carries no id of its own.
   *
   * @param listener The listener
   * @return The element id, or <code>null</code> where nothing above it carries one
   */
  private static String owningElementIdOf(
      final CamundaExecutionListener listener) {

    ModelElementInstance current = listener.getParentElement();
    while (current != null) {
      if (current instanceof org.camunda.bpm.model.bpmn.instance.BaseElement element) {
        return element.getId();
      }
      current = current.getParentElement();
    }
    return null;

  }

  /**
   * The id of the {@code bpmn:process} a listener belongs to.
   *
   * @param listener The listener
   * @return The process id, or <code>null</code> for a listener outside any process
   */
  private static String owningProcessIdOf(
      final CamundaExecutionListener listener) {

    ModelElementInstance current = listener;
    while (current != null) {
      if (current instanceof Process process) {
        return process.getId();
      }
      current = current.getParentElement();
    }
    return null;

  }

}
