package io.vanillabp.camunda7.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.CompletionCondition;
import org.camunda.bpm.model.bpmn.instance.Condition;
import org.camunda.bpm.model.bpmn.instance.ConditionExpression;
import org.camunda.bpm.model.bpmn.instance.LoopCardinality;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.SequenceFlow;
import org.camunda.bpm.model.bpmn.instance.TimerEventDefinition;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;

import io.vanillabp.integration.adapter.spi.expressions.ExpressionPlace;
import io.vanillabp.integration.adapter.spi.expressions.ModelExpression;

/**
 * Where Camunda 7 lets a BPMN model read the workflow's data, and what those places say.
 * Two startup checks ask about the same elements, so one walk over the model answers both.
 * <p>
 * What is read: the conditions of sequence flows, the conditions of conditional events
 * (an intermediate catching event, a boundary event, the start event of an event
 * subprocess), the definitions of timers, and the cardinality, collection and completion
 * condition of multi-instance elements. What is NOT read, on purpose: the input expressions
 * of a business rule task. So the engine, not this class, finds a decision which reads
 * something unshared. Also not read: everything which names a wired task (the EL resolver
 * serves those), and input mappings. There a missing value shows up as an incident, not as
 * a silent decision.
 * <p>
 * {@link #of(BpmnModelInstance, String)} answers the first check. It gives the attribute
 * PATHS those expressions rely on. A path with a segment which is not shared with the BPMS
 * always evaluates to <code>null</code>. What Camunda 7 does with that null depends on where
 * the expression sits. So every path is reported with its {@link Placement}.
 * <p>
 * It is careful about what it calls a path. It collects <code>${...}</code> and
 * <code>#{...}</code> expressions. It skips what is clearly not a variable read: EL
 * keywords, function calls and namespace prefixes. A path ENDS where a method call or an
 * indexed access begins. So <code>order.status.name()</code> is read as
 * <code>order.status</code>, and <code>order.items[0].price</code> as
 * <code>order.items</code>. What such a call resolves to depends on the runtime class which
 * the engine's serialization produced, and the check judges declared types only. An
 * expression it cannot make sense of adds nothing. A wrong warning about a model which
 * works is worse than a missing one.
 * <p>
 * {@link #expressionsOf(BpmnModelInstance, String)} answers the second check with the
 * expressions THEMSELVES. That check judges the form of what the modeller wrote, and a
 * keyword or a function call is part of that form. So nothing is dropped and nothing is
 * shortened there. What goes over is the text between <code>${</code> or <code>#{</code>
 * and the closing brace. That is what JUEL evaluates.
 */
public final class Camunda7ExpressionIdentifiers {

  /**
   * The <code>${...}</code> and <code>#{...}</code> blocks of an attribute value.
   */
  private static final Pattern EXPRESSION = Pattern.compile("[#$]\\{([^}]*)\\}");

  /**
   * A dotted chain of Java-like identifiers, followed by what ends it. An opening
   * parenthesis or a colon make the last segment a call, not a value. A bracket begins an
   * indexed access.
   */
  private static final Pattern PATH = Pattern
      .compile(
          "(?<![\\w.$:])([A-Za-z_$][\\w$]*(?:\\s*\\.\\s*[A-Za-z_$][\\w$]*)*)\\s*(?<ends>[(:\\[])?");

  /**
   * Names which are part of the language, not a variable. A path beginning with one of them
   * is none of this check's business.
   */
  private static final Set<String> KEYWORDS = Set
      .of(
          "true", "false", "null", "empty", "and", "or", "not", "div", "mod", "instanceof", "eq",
          "ne", "lt", "gt", "le", "ge", "execution", "task", "authenticatedUserId", "currentUser",
          "currentUserGroups", "dateTime", "now", "loopCounter", "nrOfInstances",
          "nrOfActiveInstances", "nrOfCompletedInstances");

  private Camunda7ExpressionIdentifiers() {
  }

  /**
   * Where in a model an expression sits. This decides what the engine does with a
   * <code>null</code> the expression produces. Camunda 7 is loud in some of these places and
   * silent in others. The check exists because of the silent ones.
   */
  public enum Placement {

    /**
     * The condition of a conditional event. Camunda 7 evaluates it with
     * {@code tryEvaluate}. That answers false for a property the value does not have. So the
     * event simply keeps waiting.
     */
    CONDITIONAL_EVENT,

    /**
     * The completion condition of a multi-instance element. A condition which is never
     * true lets every instance run, so the element ends the way it would without one.
     */
    MULTI_INSTANCE_COMPLETION_CONDITION,

    /**
     * The condition of a sequence flow leaving an element which has a default flow.
     */
    SEQUENCE_FLOW_CONDITION,

    /**
     * The condition of a sequence flow leaving an element without a default flow. If nothing
     * satisfies the condition, the engine has nowhere to go.
     */
    SEQUENCE_FLOW_CONDITION_WITHOUT_DEFAULT_FLOW,

    /**
     * The duration, date or cycle of a timer.
     */
    TIMER,

    /**
     * The cardinality of a multi-instance element.
     */
    MULTI_INSTANCE_CARDINALITY,

    /**
     * The collection a multi-instance element iterates.
     */
    MULTI_INSTANCE_COLLECTION

  }

  /**
   * The attribute paths read by the expressions of one BPMN process. Each is mapped to where
   * it was read: the element's ID, the expression itself and the placement. So a message can
   * name all three.
   * <p>
   * A path read by several elements is reported ONCE, with the placement which fails most
   * quietly. The types are collected in an order which puts the conditional event and the
   * completion condition first. A timer reading the same path raises an incident which the
   * developer cannot miss anyway.
   *
   * @param model The deployed model
   * @param scopedBpmnProcessId The process ID as the engine knows it
   * @return The paths with their origin, segments separated by dots, in the order found
   */
  public static Map<String, Origin> of(
      final BpmnModelInstance model,
      final String scopedBpmnProcessId) {

    final var paths = new LinkedHashMap<String, Origin>();
    forEachExpressionText(
        model,
        scopedBpmnProcessId,
        (
            elementId,
            placement,
            text) -> pathsOf(text)
                .forEach(path -> paths.putIfAbsent(path, new Origin(elementId, text.trim(), placement))));
    return paths;

  }

  /**
   * Where a path was read.
   *
   * @param elementId The ID of the BPMN element carrying the expression
   * @param expression The expression as the model has it
   * @param placement Where in the model that expression sits
   */
  public record Origin(
                       String elementId,
                       String expression,
                       Placement placement) {
  }

  /**
   * The expressions of one BPMN process, as the core is told about them. Each comes with the
   * element it sits in, the place inside that element and the text JUEL evaluates.
   * <p>
   * Every <code>${...}</code> and <code>#{...}</code> block is one reported expression. So
   * an attribute holding two of them is reported twice. An attribute holding a plain value,
   * such as a timer written as <code>PT1H</code>, is not reported at all. This split lets the
   * core's verdict follow what the modeller meant. A text like <code>due-${order.id}</code>
   * is two things glued together. Judging the whole of it would call a path a computation.
   * <p>
   * Unlike {@link #of(BpmnModelInstance, String)}, nothing is dropped and nothing is
   * shortened here. A keyword, a function call and an index are part of what the core
   * judges.
   *
   * @param model The deployed model
   * @param scopedBpmnProcessId The process ID as the engine knows it
   * @return The expressions in the order they were found, empty where the model carries
   *         none
   */
  public static List<ModelExpression> expressionsOf(
      final BpmnModelInstance model,
      final String scopedBpmnProcessId) {

    final var expressions = new ArrayList<ModelExpression>();
    forEachExpressionText(
        model,
        scopedBpmnProcessId,
        (
            elementId,
            placement,
            text) -> {
          final var blocks = EXPRESSION.matcher(text);
          while (blocks.find()) {
            expressions
                .add(new ModelExpression(elementId, placeOf(placement), blocks.group(), blocks.group(1)));
          }
        });
    return List.copyOf(expressions);

  }

  /**
   * The place the core knows for one of this adapter's placements.
   * <p>
   * The two sequence-flow placements become one place. Whether the gateway declares a
   * default flow decides what the ENGINE does with a null. That is the other check's
   * question. What an expression costs the application is the same either way.
   *
   * @param placement Where in the model the expression sits
   * @return The place as the core names it
   */
  private static ExpressionPlace placeOf(
      final Placement placement) {

    return switch (placement) {
      case CONDITIONAL_EVENT -> ExpressionPlace.CONDITIONAL_EVENT_CONDITION;
      case MULTI_INSTANCE_COMPLETION_CONDITION -> ExpressionPlace.MULTI_INSTANCE_COMPLETION_CONDITION;
      case SEQUENCE_FLOW_CONDITION, SEQUENCE_FLOW_CONDITION_WITHOUT_DEFAULT_FLOW ->
        ExpressionPlace.SEQUENCE_FLOW_CONDITION;
      case TIMER -> ExpressionPlace.TIMER;
      case MULTI_INSTANCE_CARDINALITY -> ExpressionPlace.MULTI_INSTANCE_CARDINALITY;
      case MULTI_INSTANCE_COLLECTION -> ExpressionPlace.MULTI_INSTANCE_COLLECTION;
    };

  }

  /**
   * What one of the collected elements carries: the element's ID, where in the model it
   * sits, and one text which may hold an expression.
   */
  @FunctionalInterface
  private interface ExpressionTexts {

    /**
     * Hands over one text of one element.
     *
     * @param elementId The ID of the BPMN element carrying it
     * @param placement Where in the model that element sits
     * @param text The attribute value or element text, possibly without any expression
     */
    void accept(
        String elementId,
        Placement placement,
        String text);

  }

  /**
   * Walks the elements of one BPMN process which may carry an expression and hands every
   * text of them over.
   * <p>
   * The order of the types lets {@link #of(BpmnModelInstance, String)} keep the quietest
   * placement of a path. The conditional event and the completion condition come first. A
   * timer reading the same path raises an incident which the developer cannot miss anyway.
   */
  private static void forEachExpressionText(
      final BpmnModelInstance model,
      final String scopedBpmnProcessId,
      final ExpressionTexts texts) {

    final var process = model.getModelElementById(scopedBpmnProcessId);
    if (!(process instanceof Process)) {
      return;
    }
    Stream
        .<Class<? extends ModelElementInstance>>of(
            Condition.class,
            CompletionCondition.class,
            ConditionExpression.class,
            TimerEventDefinition.class,
            LoopCardinality.class,
            MultiInstanceLoopCharacteristics.class)
        .forEach(type -> model
            .getModelElementsByType(model.getModel().getType(type))
            .stream()
            .filter(element -> belongsTo(element, process))
            .forEach(element -> {
              final var elementId = elementIdOf(element);
              final var placement = placementOf(element);
              textsOf(element).forEach(text -> texts.accept(elementId, placement, text));
            }));

  }

  /**
   * Which placement an element of one of the collected types is.
   */
  private static Placement placementOf(
      final ModelElementInstance element) {

    if (element instanceof Condition) {
      return Placement.CONDITIONAL_EVENT;
    }
    if (element instanceof CompletionCondition) {
      return Placement.MULTI_INSTANCE_COMPLETION_CONDITION;
    }
    if (element instanceof ConditionExpression) {
      return hasADefaultFlow(element)
          ? Placement.SEQUENCE_FLOW_CONDITION
          : Placement.SEQUENCE_FLOW_CONDITION_WITHOUT_DEFAULT_FLOW;
    }
    if (element instanceof TimerEventDefinition) {
      return Placement.TIMER;
    }
    if (element instanceof LoopCardinality) {
      return Placement.MULTI_INSTANCE_CARDINALITY;
    }
    return Placement.MULTI_INSTANCE_COLLECTION;

  }

  /**
   * Whether the element which the conditional sequence flow leaves declares a default flow.
   * With one, the workflow quietly continues elsewhere. Without one, the engine cannot move
   * on at all. This is read from the DOM attribute. <code>default</code> is declared on the
   * gateways and on the activities separately, and a condition may hang off any of them.
   */
  private static boolean hasADefaultFlow(
      final ModelElementInstance element) {

    final var flow = closest(element, SequenceFlow.class);
    if (flow == null) {
      return false;
    }
    final var source = flow.getSource();
    if (source == null) {
      return false;
    }
    final var declared = source
        .getDomElement()
        .getAttribute("default");
    return (declared != null) && !declared.isBlank();

  }

  /**
   * The element itself or the closest ancestor of the given type.
   */
  private static <T> T closest(
      final ModelElementInstance element,
      final Class<T> type) {

    for (var candidate = element; candidate != null; candidate = candidate.getParentElement()) {
      if (type.isInstance(candidate)) {
        return type.cast(candidate);
      }
    }
    return null;

  }

  /**
   * The texts of one element which may carry an expression. They are read per type, not
   * from the element's whole text content. In the DOM, that is everything below the element
   * joined together. A multi-instance element would answer with its cardinality and its
   * completion condition glued together.
   */
  private static Set<String> textsOf(
      final ModelElementInstance element) {

    final var texts = new LinkedHashSet<String>();
    if (element instanceof TimerEventDefinition timer) {
      addIfPresent(texts, timer.getTimeDuration());
      addIfPresent(texts, timer.getTimeDate());
      addIfPresent(texts, timer.getTimeCycle());
      return texts;
    }
    if (element instanceof MultiInstanceLoopCharacteristics multiInstance) {
      // the collection a multi-instance element iterates. Usually this is an expression
      // naming an attribute of the aggregate
      if (multiInstance.getCamundaCollection() != null) {
        texts.add(multiInstance.getCamundaCollection());
      }
      return texts;
    }
    // a condition, a cardinality and a completion condition carry their expression as
    // their own text
    addIfPresent(texts, element);
    return texts;

  }

  private static void addIfPresent(
      final Set<String> texts,
      final ModelElementInstance element) {

    if ((element != null) && (element.getTextContent() != null) && !element.getTextContent().isBlank()) {
      texts.add(element.getTextContent());
    }

  }

  /**
   * Whether the element sits inside the given process. A file may hold several.
   */
  private static boolean belongsTo(
      final ModelElementInstance element,
      final ModelElementInstance process) {

    for (var candidate = element; candidate != null; candidate = candidate.getParentElement()) {
      if (candidate == process) {
        return true;
      }
    }
    return false;

  }

  /**
   * The ID of the element itself or of the closest ancestor which has one. A condition
   * expression carries no ID, but its sequence flow does.
   */
  private static String elementIdOf(
      final ModelElementInstance element) {

    for (var candidate = element; candidate != null; candidate = candidate.getParentElement()) {
      final var id = candidate
          .getDomElement()
          .getAttribute("id");
      if ((id != null) && !id.isBlank()) {
        return id;
      }
    }
    return "unknown element";

  }

  /**
   * The attribute paths of one expression text.
   *
   * @param text The attribute value or element text, possibly without any expression
   * @return The paths found, segments separated by dots
   */
  static Set<String> pathsOf(
      final String text) {

    final var paths = new LinkedHashSet<String>();
    if (text == null) {
      return paths;
    }
    final var expressions = EXPRESSION.matcher(text);
    while (expressions.find()) {
      // string literals are no identifiers. '${execution.getVariable('x')}' reads a
      // variable through the engine's API, not one this check is about
      final var body = expressions
          .group(1)
          .replaceAll("'[^']*'", "''")
          .replaceAll("\"[^\"]*\"", "\"\"");
      final var candidates = PATH.matcher(body);
      while (candidates.find()) {
        final var segments = segmentsOf(candidates.group(1), candidates.group("ends"));
        if (segments.isEmpty() || KEYWORDS.contains(segments.get(0))) {
          continue;
        }
        paths.add(String.join(".", segments));
      }
    }
    return paths;

  }

  /**
   * The segments a matched chain adds. Where the chain ended in a parenthesis or a namespace
   * colon, its last segment is a method or a function name, not a value. Then the path is
   * everything before it. Otherwise the whole chain is the path.
   *
   * @param chain The dotted chain as it was matched
   * @param ends What followed it, or <code>null</code>
   * @return The segments, possibly none
   */
  private static List<String> segmentsOf(
      final String chain,
      final String ends) {

    final var segments = new ArrayList<String>();
    for (final var segment : chain.split("\\.")) {
      segments.add(segment.trim());
    }
    if (ends == null) {
      return segments;
    }
    if ("[".equals(ends)) {
      // an indexed access. What the element is depends on the collection's runtime
      // content, so the path ends at the collection itself
      return segments;
    }
    // a method call or a namespace-prefixed function. The last segment is neither an
    // attribute nor a value
    segments.remove(segments.size() - 1);
    return segments;

  }

}
