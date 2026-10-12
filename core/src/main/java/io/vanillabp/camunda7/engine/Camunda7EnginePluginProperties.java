package io.vanillabp.camunda7.engine;

import java.util.Map;

/**
 * One engine plugin of an adapter id: which class, and the properties Camunda
 * applies to it.
 * <p>
 * The application gives the section a NAME. It is not keyed by the class, because a class
 * name has dots, and both configuration binders would need them quoted:
 *
 * <pre>
 * vanillabp:
 *   adapters:
 *     camunda7:
 *       engine-plugins:
 *         xstream:
 *           plugin-class: org.camunda.xstream.ProcessEnginePlugin
 *           properties:
 *             encoding: UTF-8
 *             allowed-types: my.project.**,other.project.**
 * </pre>
 */
public class Camunda7EnginePluginProperties {

  /**
   * The platform integration builds one per configured plugin section and fills it from
   * the keys it found.
   */
  public Camunda7EnginePluginProperties() {

  }

  /** The class Camunda instantiates for this plugin section. */
  private String pluginClass;

  /** The properties Camunda applies to the plugin, keyed in kebab-case. */
  private Map<String, String> properties = Map.of();

  /**
   * The class Camunda instantiates for this plugin section, e.g.
   * <code>org.camunda.xstream.ProcessEnginePlugin</code>.
   *
   * @return The class name, <code>null</code> where the section names none
   */
  public String getPluginClass() {
    return pluginClass;
  }

  /**
   * The class Camunda instantiates for this plugin section.
   *
   * @param pluginClass The class name
   */
  public void setPluginClass(
      final String pluginClass) {
    this.pluginClass = pluginClass;
  }

  /**
   * The plugin's own properties in kebab-case. Camunda converts them to the types the
   * plugin declares.
   *
   * @return The properties, empty where the section has none
   */
  public Map<String, String> getProperties() {
    return properties;
  }

  /**
   * Keeps an empty map instead of <code>null</code>. So a plugin section which names only a
   * class is applied, and it does not fail when Camunda reads it.
   *
   * @param properties The plugin's own properties, may be <code>null</code>
   */
  public void setProperties(
      final Map<String, String> properties) {
    this.properties = properties == null
        ? Map.of()
        : properties;
  }

}
