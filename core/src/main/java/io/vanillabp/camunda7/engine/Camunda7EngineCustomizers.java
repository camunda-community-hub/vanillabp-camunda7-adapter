package io.vanillabp.camunda7.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;

import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.history.handler.CompositeDbHistoryEventHandler;
import org.camunda.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies the {@link Camunda7EngineCustomizer}s of an application to the configuration an
 * engine is about to be built from. Both engine holders - Spring Boot and Quarkus - call
 * this, so what a customizer may contribute is the same on both platforms.
 */
public final class Camunda7EngineCustomizers {

  private static final Logger log = LoggerFactory.getLogger(Camunda7EngineCustomizers.class);

  private Camunda7EngineCustomizers() {
  }

  /**
   * Lets the application have the last word on the engine configuration, and says in the
   * log who touched it. A customizer runs after VanillaBP set everything it needs, so it
   * can undo what the adapter relies on - which is why the line naming it matters.
   *
   * @param adapterId The adapter id whose engine is being built
   * @param configuration The configuration, with VanillaBP's own parse listener already
   *          in its PRE list
   * @param customizers The customizers of the application, in the order the platform
   *          resolved them
   */
  public static void apply(
      final String adapterId,
      final ProcessEngineConfigurationImpl configuration,
      final Collection<Camunda7EngineCustomizer> customizers) {

    if ((customizers == null) || customizers.isEmpty()) {
      return;
    }

    final var before = new LinkedList<BpmnParseListener>();
    final var after = new LinkedList<BpmnParseListener>();
    final var historyEventHandlers = new LinkedList<HistoryEventHandler>();
    for (final var customizer : customizers) {
      before.addAll(customizer.parseListenersBefore(adapterId));
      after.addAll(customizer.parseListenersAfter(adapterId));
      final var historyEventHandler = customizer.historyEventHandler(adapterId);
      if (historyEventHandler != null) {
        historyEventHandlers.add(historyEventHandler);
      }
    }

    if (!before.isEmpty()) {
      // VanillaBP's own listener is already in that list, and a listener contributed
      // "before" has to run before it
      final var preListeners = new ArrayList<BpmnParseListener>(before);
      if (configuration.getCustomPreBPMNParseListeners() != null) {
        preListeners.addAll(configuration.getCustomPreBPMNParseListeners());
      }
      configuration.setCustomPreBPMNParseListeners(preListeners);
    }
    if (!after.isEmpty()) {
      final var postListeners = configuration.getCustomPostBPMNParseListeners() == null
          ? new ArrayList<BpmnParseListener>()
          : new ArrayList<>(configuration.getCustomPostBPMNParseListeners());
      postListeners.addAll(after);
      configuration.setCustomPostBPMNParseListeners(postListeners);
    }
    if (!historyEventHandlers.isEmpty()) {
      // the engine's own handler comes along: the composite writes history the way it
      // always did and hands every event on to the contributed handlers as well
      configuration.setHistoryEventHandler(new CompositeDbHistoryEventHandler(historyEventHandlers));
    }

    for (final var customizer : customizers) {
      customizer.customize(adapterId, configuration);
    }

    log
        .info(
            "Camunda7[{}]: applying {} engine customizer(s): {}",
            adapterId,
            customizers.size(),
            customizers
                .stream()
                .map(customizer -> customizer.getClass().getName())
                .toList());

  }

}
