package io.vanillabp.camunda7.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.processservice.Camunda7ProcessService;
import io.vanillabp.integration.adapter.migration.config.MessageAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The message time-to-live is resolved by the platform, with the same keys for every adapter.
 * What this adapter adds is its own rule about the values: zero switches the waiting off, and
 * a value below zero ends the boot.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MessageTimeToLiveResolutionTest {

  private static final String ADAPTER = "c7";

  private static MigrationAdapterProperties properties(
      final Duration module,
      final Duration message) {

    final var messageSection = new MessageAdapterProperties();
    messageSection.setMessageTimeToLive(message);
    final var properties = new MigrationAdapterProperties();
    properties.setWorkflowModules(Map.of("the-module", WorkflowModuleAdapterProperties
        .builder()
        .messageTimeToLive(module)
        .workflows(Map.of("TheWorkflow", WorkflowAdapterProperties
            .builder()
            .messages(Map.of("ContractSigned", messageSection))
            .build()))
        .build()));
    return properties;

  }

  @Test
  @DisplayName("Zero at a more specific level switches the waiting off again")
  public void zeroSwitchesTheWaitingOff() {

    assertEquals(
        Duration.ZERO,
        properties(Duration.ofHours(1), Duration.ZERO)
            .messageTimeToLive("the-module", "TheWorkflow", "ContractSigned"));

  }

  @Test
  @DisplayName("Nothing configured is null - a correlation nothing waits for is then refused as in version 1")
  public void nothingConfiguredIsNull() {

    assertNull(properties(null, null).messageTimeToLive("the-module", "TheWorkflow", "ContractSigned"));

  }

  @Test
  @DisplayName("A time-to-live below zero ends the boot, and the message names every such key")
  public void aNegativeTimeToLiveEndsTheBoot() {

    final var written = properties(Duration.ofSeconds(-1), Duration.ofSeconds(-2)).messageTimesToLiveWritten();
    final var message = assertThrows(
        IllegalStateException.class,
        () -> Camunda7ProcessService.refuseNegativeMessageTimesToLive(ADAPTER, written))
        .getMessage();
    assertTrue(message.contains("vanillabp.workflow-modules.the-module.message-time-to-live=PT-1S"), message);
    assertTrue(
        message.contains(
            "vanillabp.workflow-modules.the-module.workflows.TheWorkflow.messages.ContractSigned.message-time-to-live=PT-2S"),
        message);
    assertTrue(message.contains("'0s'"), message);
    Camunda7ProcessService
        .refuseNegativeMessageTimesToLive(ADAPTER,
            properties(Duration.ZERO, null).messageTimesToLiveWritten());

  }

}
