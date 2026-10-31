package io.vanillabp.camunda7.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.engine.Camunda7EngineProperties;
import io.vanillabp.camunda7.wiring.Camunda7MessageTimeToLiveResolver;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The message time-to-live is resolved per message, with a fallback to the workflow, the
 * workflow module and the adapter. It has the key of the Camunda 8 adapter.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MessageTimeToLiveResolutionTest {

  private static final String ADAPTER = "c7";

  private static VanillaBpCamunda7Properties.Camunda7ScopedProperties scoped(
      final Duration timeToLive) {

    final var scoped = new VanillaBpCamunda7Properties.Camunda7ScopedProperties();
    scoped.setMessageTimeToLive(timeToLive);
    return scoped;

  }

  private VanillaBpCamunda7Properties properties(
      final Duration adapter,
      final Duration module,
      final Duration workflow,
      final Duration message) {

    final var properties = new VanillaBpCamunda7Properties();
    final var engineProperties = new Camunda7EngineProperties();
    engineProperties.setMessageTimeToLive(adapter);
    properties.setAdapters(Map.of(ADAPTER, engineProperties));

    final var moduleProperties = new VanillaBpCamunda7Properties.Camunda7WorkflowModuleProperties();
    final var moduleScoped = new VanillaBpCamunda7Properties.Camunda7ModuleScopedProperties();
    moduleScoped.setMessageTimeToLive(module);
    moduleProperties.setAdapters(Map.of(ADAPTER, moduleScoped));
    final var workflowProperties = new VanillaBpCamunda7Properties.Camunda7WorkflowProperties();
    workflowProperties.setAdapters(Map.of(ADAPTER, scoped(workflow)));
    final var messageProperties = new VanillaBpCamunda7Properties.Camunda7MessageProperties();
    final var messageScoped = new VanillaBpCamunda7Properties.Camunda7MessageScopedProperties();
    messageScoped.setMessageTimeToLive(message);
    messageProperties.setAdapters(Map.of(ADAPTER, messageScoped));
    workflowProperties.setMessages(Map.of("ContractSigned", messageProperties));
    // a task of the same name is a different map. A value meant for it never answers a
    // question about a message
    final var taskProperties = new VanillaBpCamunda7Properties.Camunda7TaskProperties();
    taskProperties.setAdapters(Map.of(ADAPTER, scoped(Duration.ofDays(9))));
    workflowProperties.setTasks(Map.of("Reminder", taskProperties));
    moduleProperties.setWorkflows(Map.of("TheWorkflow", workflowProperties));
    properties.setWorkflowModules(Map.of("the-module", moduleProperties));
    return properties;

  }

  @Test
  @DisplayName("The message wins over its workflow, the workflow over its module, the module over the adapter")
  public void mostSpecificWins() {

    final var all = properties(Duration.ofHours(1), Duration.ofMinutes(30), Duration.ofMinutes(10), Duration
        .ofMinutes(1));
    assertEquals(Duration.ofMinutes(1),
        all.messageTimeToLiveFor(ADAPTER, "the-module", "TheWorkflow", "ContractSigned"));
    assertEquals(
        Duration.ofMinutes(10),
        all.messageTimeToLiveFor(ADAPTER, "the-module", "TheWorkflow", "Reminder"),
        "a task name is not a message name, so this falls back to the workflow");
    assertEquals(Duration.ofMinutes(30),
        all.messageTimeToLiveFor(ADAPTER, "the-module", "OtherWorkflow", "ContractSigned"));
    assertEquals(Duration.ofHours(1),
        all.messageTimeToLiveFor(ADAPTER, "other-module", "TheWorkflow", "ContractSigned"));
    assertEquals(
        Duration.ZERO,
        properties(Duration.ofHours(1), null, null, Duration.ZERO)
            .messageTimeToLiveFor(ADAPTER, "the-module", "TheWorkflow", "ContractSigned"),
        "zero at a more specific level switches the waiting off again");

  }

  @Test
  @DisplayName("Nothing configured is null - a correlation nothing waits for is then refused as in version 1")
  public void nothingConfiguredIsNull() {

    assertNull(properties(null, null, null, null).messageTimeToLiveFor(ADAPTER, "the-module", "TheWorkflow",
        "ContractSigned"));
    assertNull(new VanillaBpCamunda7Properties().messageTimeToLiveFor(ADAPTER, "unknown", "Unknown", "Unknown"));

  }

  @Test
  @DisplayName("A time-to-live below zero ends the boot, and the message names every such key")
  public void aNegativeTimeToLiveEndsTheBoot() {

    final var properties = properties(Duration.ofSeconds(-1), null, null, Duration.ofSeconds(-2));
    final var keys = properties.negativeMessageTimeToLiveKeys(ADAPTER);
    assertEquals(
        List.of(
            "vanillabp.adapters.c7.message-time-to-live=PT-1S",
            "vanillabp.workflow-modules.the-module.workflows.TheWorkflow.messages.ContractSigned.adapters.c7.message-time-to-live=PT-2S"),
        keys);
    final var message = assertThrows(
        IllegalStateException.class,
        () -> Camunda7MessageTimeToLiveResolver.refuseNegativeValues(ADAPTER, keys))
        .getMessage();
    assertTrue(message.contains("vanillabp.adapters.c7.message-time-to-live=PT-1S"), message);
    assertTrue(message.contains("'0s'"), message);
    Camunda7MessageTimeToLiveResolver
        .refuseNegativeValues(ADAPTER,
            properties(Duration.ZERO, null, null, null).negativeMessageTimeToLiveKeys(ADAPTER));

  }

}
