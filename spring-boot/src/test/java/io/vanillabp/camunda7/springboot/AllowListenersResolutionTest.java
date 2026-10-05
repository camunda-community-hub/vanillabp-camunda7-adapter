package io.vanillabp.camunda7.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Where the Spring binding reads <code>allow-listeners</code>, and what it says about a value
 * written at a level it does not read.
 * <p>
 * The same three levels and the same most-specific-wins rule as the Camunda 8 adapter reads
 * for the very same key: two keys about what a model may contain, reaching different levels on
 * two adapters, would be the worse answer.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AllowListenersResolutionTest {

  private static final String ADAPTER = "c7";

  private VanillaBpCamunda7Properties properties(
      final boolean perAdapter,
      final Boolean perModule,
      final Boolean perWorkflow,
      final Boolean perTask) {

    final var properties = new VanillaBpCamunda7Properties();
    final var engineProperties = new Camunda7EngineProperties();
    engineProperties.setAllowListeners(perAdapter);
    properties.setAdapters(Map.of(ADAPTER, engineProperties));

    final var module = new VanillaBpCamunda7Properties.Camunda7WorkflowModuleProperties();
    if (perModule != null) {
      final var scoped = new VanillaBpCamunda7Properties.Camunda7ModuleScopedProperties();
      scoped.setAllowListeners(perModule);
      module.setAdapters(Map.of(ADAPTER, scoped));
    }
    if ((perWorkflow != null) || (perTask != null)) {
      final var workflow = new VanillaBpCamunda7Properties.Camunda7WorkflowProperties();
      if (perWorkflow != null) {
        final var scoped = new VanillaBpCamunda7Properties.Camunda7ScopedProperties();
        scoped.setAllowListeners(perWorkflow);
        workflow.setAdapters(Map.of(ADAPTER, scoped));
      }
      if (perTask != null) {
        final var task = new VanillaBpCamunda7Properties.Camunda7TaskProperties();
        final var scoped = new VanillaBpCamunda7Properties.Camunda7ScopedProperties();
        scoped.setAllowListeners(perTask);
        task.setAdapters(Map.of(ADAPTER, scoped));
        workflow.setTasks(Map.of("archiveTheOrder", task));
      }
      module.setWorkflows(Map.of("TheWorkflow", workflow));
    }
    properties.setWorkflowModules(Map.of("the-module", module));
    return properties;

  }

  @Test
  @DisplayName("The workflow wins over its module, the module over the adapter, in both directions")
  public void mostSpecificWins() {

    final var perWorkflow = properties(true, false, true, null)
        .allowListenersFor(ADAPTER, "the-module", "TheWorkflow");
    assertTrue(perWorkflow.allowed());
    assertEquals(
        "vanillabp.workflow-modules.the-module.workflows.TheWorkflow.adapters.c7.allow-listeners",
        perWorkflow.propertyKey(),
        "the report has to name the line the reader can find in their configuration");

    final var perModule = properties(true, false, true, null)
        .allowListenersFor(ADAPTER, "the-module", "AnotherWorkflow");
    assertFalse(
        perModule.allowed(),
        "a workflow module may switch OFF what the adapter switched on, which version 1 could not");
    assertEquals(
        "vanillabp.workflow-modules.the-module.adapters.c7.allow-listeners",
        perModule.propertyKey());

    final var perAdapter = properties(true, null, null, null)
        .allowListenersFor(ADAPTER, "another-module", "SomeWorkflow");
    assertTrue(perAdapter.allowed());
    assertEquals("vanillabp.adapters.c7.allow-listeners", perAdapter.propertyKey());

  }

  @Test
  @DisplayName("Nothing configured serves no modelled listener, and no key stands behind it")
  public void nothingConfiguredServesNoListener() {

    final var resolved = properties(false, null, null, null)
        .allowListenersFor(ADAPTER, "the-module", "TheWorkflow");
    assertFalse(resolved.allowed(), "version 1 served them unannounced, which is why the default is off");
    assertNull(resolved.propertyKey());

    assertFalse(
        new VanillaBpCamunda7Properties().allowListenersFor(ADAPTER, "unknown", "Unknown").allowed());
    assertFalse(
        properties(true, null, null, null).allowListenersFor("unknown-adapter", "the-module", "TheWorkflow")
            .allowed(),
        "one adapter id's key says nothing about another's");

  }

  @Test
  @DisplayName("A value at task level is found and changes no answer")
  public void aValueAtTaskLevelIsFoundAndChangesNoAnswer() {

    final var properties = properties(false, null, null, true);

    assertEquals(
        List
            .of("vanillabp.workflow-modules.the-module.workflows.TheWorkflow.tasks.archiveTheOrder.adapters.c7.allow-listeners"),
        properties.allowListenersKeysAtTaskLevel(ADAPTER),
        "the boot names the key it cannot honour instead of ignoring it silently");
    assertFalse(
        properties.allowListenersFor(ADAPTER, "the-module", "TheWorkflow").allowed(),
        "and the value changes nothing: whether a listener becomes a task at all is what this key "
            + "decides, so there is no task definition yet to key a level by");
    assertTrue(
        properties(false, null, null, null).allowListenersKeysAtTaskLevel(ADAPTER).isEmpty(),
        "a configuration which does nothing wrong produces no line");

  }

}
