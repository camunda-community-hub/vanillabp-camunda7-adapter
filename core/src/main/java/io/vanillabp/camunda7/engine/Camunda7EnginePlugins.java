package io.vanillabp.camunda7.engine;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.container.impl.metadata.PropertyHelper;
import org.camunda.bpm.engine.impl.cfg.ProcessEnginePlugin;

/**
 * Builds the engine plugins an adapter id configures - the way a dataformat
 * (camunda-xstream, SPIN) or any other Camunda plugin reaches an embedded engine VanillaBP
 * builds:
 *
 * <pre>
 * vanillabp:
 *   adapters:
 *     camunda7:
 *       serialization-format: application/xstream
 *       engine-plugins:
 *         xstream:
 *           plugin-class: org.camunda.xstream.ProcessEnginePlugin
 *           properties:
 *             encoding: UTF-8
 *             ignore-unknown-elements: true
 *             allowed-types: my.project.**,other.project.**
 * </pre>
 *
 * The section is named by the application (a class name carries dots, which both
 * configuration binders would need quoted), it names the plugin's class, and <b>the
 * properties are applied by Camunda itself</b> ({@link PropertyHelper}, the same code which reads the
 * <code>&lt;property&gt;</code> elements of a <code>bpm-platform.xml</code>), so the types a
 * plugin declares are converted exactly as they are in a container-managed engine. The
 * kebab-case of the configuration maps to the plugin's setters
 * ({@code allowed-types} to {@code setAllowedTypes}).
 * <p>
 * Configuring a plugin here is per adapter id, which is what a side-by-side migration needs:
 * two embedded engines, each with the plugins its models require. An application may instead
 * contribute a plugin as a BEAN - a plugin which configures itself from the application's own
 * properties (camunda-xstream does that on Spring Boot) is easier that way - and those beans
 * apply to every engine this adapter builds.
 * <p>
 * Why an embedded engine of this adapter accepts plugins at all is decision 9 in the repository's
 * DECISIONS.md: without a data format a nested shared value cannot become an object variable.
 */
public final class Camunda7EnginePlugins {

  private Camunda7EnginePlugins() {
  }

  /**
   * Instantiates and configures the plugins of one adapter id.
   *
   * @param adapterId The adapter id, for the guiding messages
   * @param configuredPlugins Plugin class name to its properties (may be empty)
   * @return The plugins, in the order configured
   * @throws IllegalStateException If a class is unknown, is no plugin, cannot be
   *           instantiated or a property does not exist - each with the key which caused it
   */
  public static List<ProcessEnginePlugin> of(
      final String adapterId,
      final Map<String, Camunda7EnginePluginProperties> configuredPlugins) {

    final var plugins = new LinkedList<ProcessEnginePlugin>();
    if (configuredPlugins == null) {
      return plugins;
    }
    configuredPlugins
        .forEach((
            name,
            plugin) -> plugins.add(pluginOf(adapterId, name, plugin)));
    return plugins;

  }

  private static ProcessEnginePlugin pluginOf(
      final String adapterId,
      final String name,
      final Camunda7EnginePluginProperties configured) {

    final var className = configured != null
        ? configured.getPluginClass()
        : null;
    if ((className == null) || className.isBlank()) {
      throw new IllegalStateException(
          """
              The engine plugin '%s' configured at 'vanillabp.adapters.%s.engine-plugins' names no \
              class! Set 'vanillabp.adapters.%s.engine-plugins.%s.plugin-class' to the plugin's \
              class, e.g. 'org.camunda.xstream.ProcessEnginePlugin'."""
              .formatted(name, adapterId, adapterId, name));
    }
    final var properties = configured.getProperties();
    final Class<?> pluginClass;
    try {
      pluginClass = Class.forName(className, true, Thread.currentThread().getContextClassLoader());
    } catch (final ClassNotFoundException | LinkageError notThere) {
      throw new IllegalStateException(
          """
              The engine plugin class '%s' configured at 'vanillabp.adapters.%s.engine-plugins' is \
              not on the classpath! Add the dependency providing it (e.g. 'org.camunda:camunda-xstream' \
              for 'org.camunda.xstream.ProcessEnginePlugin'), or remove the section."""
              .formatted(className, adapterId), notThere);
    }
    if (!ProcessEnginePlugin.class.isAssignableFrom(pluginClass)) {
      throw new IllegalStateException(
          """
              The class '%s' configured at 'vanillabp.adapters.%s.engine-plugins' is not a Camunda \
              engine plugin! It has to implement %s."""
              .formatted(className, adapterId, ProcessEnginePlugin.class.getName()));
    }
    final ProcessEnginePlugin plugin;
    try {
      plugin = (ProcessEnginePlugin) pluginClass
          .getDeclaredConstructor()
          .newInstance();
    } catch (final ReflectiveOperationException | RuntimeException cannotInstantiate) {
      throw new IllegalStateException(
          """
              The engine plugin '%s' configured at 'vanillabp.adapters.%s.engine-plugins' could not \
              be created! A plugin configured here needs a public constructor without arguments - a \
              plugin which needs more is contributed as a bean of your application instead, which \
              this adapter applies to every engine it builds."""
              .formatted(className, adapterId), cannotInstantiate);
    }
    if ((properties == null) || properties.isEmpty()) {
      return plugin;
    }
    try {
      // Camunda's own property mapping: the same code which applies the <property>
      // elements of a bpm-platform.xml, so the plugin's types are converted the way its
      // documentation describes. Kebab-case, like every other VanillaBP key
      PropertyHelper.applyProperties(plugin, properties, PropertyHelper.KEBAB_CASE);
    } catch (final RuntimeException rejected) {
      throw new IllegalStateException(
          """
              A property of the engine plugin '%s' configured at \
              'vanillabp.adapters.%s.engine-plugins' was rejected by Camunda: %s. The keys are the \
              plugin's own properties in kebab-case ('allowed-types' sets 'allowedTypes'), and the \
              plugin's documentation names them."""
              .formatted(className, adapterId, rejected.getMessage()), rejected);
    }
    return plugin;

  }

}
