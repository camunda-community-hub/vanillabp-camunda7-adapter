package io.vanillabp.camunda7.engine;

import java.util.List;

import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.history.handler.HistoryEventHandler;

/**
 * How something outside this adapter reaches the embedded engine. Mostly this is a VanillaBP
 * extension. It was written for the Business Cockpit.
 * <p>
 * Version 1 of that extension got its hooks from Camunda's Spring Boot starter. It used an
 * engine plugin for its parse listener, and the starter's history events for the lifecycle of
 * a workflow. Version 2 builds the plain engine itself, so neither exists. Without this
 * interface an extension has nowhere to put a listener. This is C7-specific SPI and belongs to
 * the C7 adapter. Engine hooks live in the engine's adapter, never in the VanillaBP core.
 * <p>
 * <b>Per adapter id.</b> A customizer is asked once per configured Camunda 7 adapter id, and it
 * gets that id. Two ids are two engines. An extension which registers something per engine
 * has to tell them apart. A customizer may contribute nothing for an id, because every method
 * has a default.
 * <p>
 * <b>Where the contributions land.</b> Parse listeners run before or after VanillaBP's own.
 * This decides the order of the listeners they attach to a BPMN element. So the built-in task
 * listeners of a POST listener run after the ones VanillaBP attaches. A history event handler
 * is installed as a COMPOSITE next to the engine's own. So the history level and everything
 * else about history stays exactly as configured.
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
   * Parse listeners which run BEFORE VanillaBP's own. An extension needs them when it has
   * to see a BPMN element as the model declares it, before the adapter changed it.
   *
   * @param adapterId The adapter id whose engine is being built
   * @return The listeners, in the order they are to run
   */
  default List<BpmnParseListener> parseListenersBefore(
      final String adapterId) {

    return List.of();

  }

  /**
   * Parse listeners which run AFTER VanillaBP's own. This is the usual case. A built-in
   * task listener attached here runs after the ones VanillaBP attached. So an extension
   * which tracks user tasks sees a task VanillaBP has already dealt with.
   *
   * @param adapterId The adapter id whose engine is being built
   * @return The listeners, in the order they are to run
   */
  default List<BpmnParseListener> parseListenersAfter(
      final String adapterId) {

    return List.of();

  }

  /**
   * A handler for the engine's history events. With it an extension learns that a workflow
   * started, ended or was cancelled. It does not have to attach an execution listener to
   * every process.
   * <p>
   * It is installed NEXT TO the engine's own handler, not in its place. So everything about
   * history stays as configured.
   *
   * @param adapterId The adapter id whose engine is being built
   * @return The handler, or <code>null</code> for none
   */
  default HistoryEventHandler historyEventHandler(
      final String adapterId) {

    return null;

  }

  /**
   * Everything else, with the engine configuration in hand. This is the last resort for
   * what this interface does not name. It runs after the contributions above were applied,
   * and before the engine is built.
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
