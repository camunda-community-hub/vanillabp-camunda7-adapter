package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import lombok.Setter;

/**
 * The engine plugins an adapter id configures - and that their properties are
 * converted by CAMUNDA, the same way a <code>bpm-platform.xml</code> would.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7EnginePluginsTest {

  /**
   * Stands in for a dataformat plugin: it has the property types such a plugin has.
   */
  @Setter
  public static class TestPlugin extends AbstractProcessEnginePlugin {

    private String encoding;

    private boolean ignoreUnknownElements;

    private int maxDepth;

    @Override
    public void preInit(
        final ProcessEngineConfigurationImpl processEngineConfiguration) {
      // nothing to do - this plugin exists to be configured
    }

    @Override
    public void postProcessEngineBuild(
        final ProcessEngine processEngine) {
      // nothing to do
    }

  }

  /**
   * A plugin which cannot be created from configuration.
   */
  public static class PluginWithoutDefaultConstructor extends AbstractProcessEnginePlugin {

    public PluginWithoutDefaultConstructor(
        final String required) {
    }

  }

  /**
   * One configured plugin section.
   */
  private static Map<String, Camunda7EnginePluginProperties> configured(
      final String pluginClass,
      final Map<String, String> properties) {

    final var plugin = new Camunda7EnginePluginProperties();
    plugin.setPluginClass(pluginClass);
    plugin.setProperties(properties);
    return Map.of("theSection", plugin);

  }

  @Test
  @DisplayName("Camunda converts the properties, kebab-case included")
  public void propertiesAreAppliedByCamunda() {

    final var plugins = Camunda7EnginePlugins
        .of(
            "c7",
            configured(
                TestPlugin.class.getName(),
                Map.of("encoding", "UTF-8", "ignore-unknown-elements", "true", "max-depth", "7")));

    assertEquals(1, plugins.size());
    final var plugin = assertInstanceOf(TestPlugin.class, plugins.getFirst());
    assertEquals("UTF-8", plugin.encoding);
    // a boolean and an int arrive as their types, not as text - Camunda's PropertyHelper
    // does that, and kebab-case maps to the setter
    assertTrue(plugin.ignoreUnknownElements);
    assertEquals(7, plugin.maxDepth);

  }

  @Test
  @DisplayName("A plugin without properties is created as it is, and nothing configured is no plugin")
  public void withoutPropertiesAndWithoutConfiguration() {

    assertEquals(1, Camunda7EnginePlugins.of("c7", configured(TestPlugin.class.getName(), Map.of())).size());
    assertTrue(Camunda7EnginePlugins.of("c7", Map.of()).isEmpty());
    assertTrue(Camunda7EnginePlugins.of("c7", null).isEmpty());

  }

  @Test
  @DisplayName("An unknown class, a class which is no plugin and an unusable one are named")
  public void guidingMessages() {

    final var unknown = assertThrowsExactly(
        IllegalStateException.class,
        () -> Camunda7EnginePlugins.of("c7", configured("no.such.Plugin", Map.of())));
    assertTrue(unknown.getMessage().contains("no.such.Plugin"), unknown.getMessage());
    assertTrue(unknown.getMessage().contains("vanillabp.adapters.c7.engine-plugins"), unknown.getMessage());

    final var noPlugin = assertThrowsExactly(
        IllegalStateException.class,
        () -> Camunda7EnginePlugins.of("c7", configured("java.lang.String", Map.of())));
    assertTrue(noPlugin.getMessage().contains("is not a Camunda engine plugin"), noPlugin.getMessage());

    final var notCreatable = assertThrowsExactly(
        IllegalStateException.class,
        () -> Camunda7EnginePlugins
            .of("c7", configured(PluginWithoutDefaultConstructor.class.getName(), Map.of())));
    assertTrue(notCreatable.getMessage().contains("without arguments"), notCreatable.getMessage());

  }

  @Test
  @DisplayName("A section without a class names the key to set")
  public void aSectionWithoutAClassIsReported() {

    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        () -> Camunda7EnginePlugins.of("c7", configured(null, Map.of("encoding", "UTF-8"))));

    assertTrue(
        failure.getMessage().contains("vanillabp.adapters.c7.engine-plugins.theSection.plugin-class"),
        failure.getMessage());

  }

  @Test
  @DisplayName("A property the plugin does not have is reported with the plugin and the section")
  public void unknownPropertyIsReported() {

    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        () -> Camunda7EnginePlugins
            .of("c7", configured(TestPlugin.class.getName(), Map.of("no-such-property", "x"))));

    assertTrue(failure.getMessage().contains(TestPlugin.class.getName()), failure.getMessage());
    assertTrue(failure.getMessage().contains("kebab-case"), failure.getMessage());

  }

}
