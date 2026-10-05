package io.vanillabp.camunda7.engine;

import java.util.List;

import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.history.handler.HistoryEventHandler;

/**
 * How something outside this adapter reaches the embedded engine - a VanillaBP extension
 * above all, the Business Cockpit being the one this was written for.
 * <p>
 * Version 1 of that extension got its hooks from Camunda's Spring Boot starter: an engine
 * plugin for its parse listener, the starter's history eventing for the lifecycle of a
 * workflow. Version 2 builds the plain engine itself, so neither exists, and an extension
 * has nowhere to put a listener. This is that place, and it is C7-specific SPI belonging
 * to the C7 adapter - engine hooks live in the engine's adapter, never in the VanillaBP
 * core.
 * <p>
 * <b>Per adapter id.</b> A customizer is asked once per configured Camunda 7 adapter id,
 * with that id in hand: two ids are two engines, and an extension registering something
 * per engine has to be able to tell them apart. Contributing nothing for an id is
 * legitimate - every method has a default.
 * <p>
 * <b>Where the contributions land.</b> Parse listeners run before or after VanillaBP's
 * own, which is what decides the order of the listeners they attach to a BPMN element:
 * the built-in task listeners of a POST listener run after the ones VanillaBP attaches.
 * A history event handler is installed as a COMPOSITE next to the engine's own, so the
 * history level and everything else about history stays exactly as configured.
 * <p>
 * The order between an adapter and an extension is VanillaBP's promise, not this
 * adapter's. The wiki page
 * <a href="https://github.com/vanillabp/adapter-platform-integration/wiki/Extensions">Extensions</a>
 * says what is promised and what is not.
 * <p>
 * On Spring Boot a customizer is a bean of this type; on Quarkus a CDI bean of it. Both
 * engine holders collect them while they build their engine.
 *
 * @see Camunda7EngineCustomizers
 */
public interface Camunda7EngineCustomizer {

  /**
   * Parse listeners running BEFORE VanillaBP's own - what an extension needs when it has
   * to see a BPMN element as the model declares it, untouched by the adapter.
   *
   * @param adapterId The adapter id whose engine is being built
   * @return The listeners, in the order they are to run
   */
  default List<BpmnParseListener> parseListenersBefore(
      final String adapterId) {

    return List.of();

  }

  /**
   * Parse listeners running AFTER VanillaBP's own - the usual case. A built-in task
   * listener attached here runs after the ones VanillaBP attached, so an extension
   * tracking user tasks sees a task VanillaBP has already dealt with.
   *
   * @param adapterId The adapter id whose engine is being built
   * @return The listeners, in the order they are to run
   */
  default List<BpmnParseListener> parseListenersAfter(
      final String adapterId) {

    return List.of();

  }

  /**
   * A handler for the engine's history events - how an extension learns that a workflow
   * started, ended or was cancelled without attaching an execution listener to every
   * process.
   * <p>
   * It is installed NEXT TO the engine's own handler rather than in its place, so
   * everything about history stays as configured.
   *
   * @param adapterId The adapter id whose engine is being built
   * @return The handler, or <code>null</code> for none
   */
  default HistoryEventHandler historyEventHandler(
      final String adapterId) {

    return null;

  }

  /**
   * Everything else, with the engine configuration in hand - the last resort for what
   * this interface does not name. It runs after the contributions above were applied and
   * before the engine is built.
   *
   * @param adapterId The adapter id whose engine is being built
   * @param configuration The configuration the engine will be built from
   */
  default void customize(
      final String adapterId,
      final ProcessEngineConfigurationImpl configuration) {
    // an extension contributing listeners alone needs nothing here
  }

}
