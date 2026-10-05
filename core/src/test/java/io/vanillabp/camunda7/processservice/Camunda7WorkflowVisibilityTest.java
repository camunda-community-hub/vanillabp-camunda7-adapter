package io.vanillabp.camunda7.processservice;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The core waits for a workflow to become findable only where the adapter
 * asks for it. Camunda 7 answers awareness probes from the very database and
 * transaction which created the instance, so it asks for nothing - a workflow this
 * engine does not know does not exist, and saying so has to stay immediate.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7WorkflowVisibilityTest {

  @Test
  @DisplayName("An embedded engine reports NO visibility delay - probes are answered from its own database")
  public void embeddedEngineReportsNoVisibilityDelay() {

    final var processService = new Camunda7ProcessService<Object>(
        "camunda7", null, null, null, null, io.vanillabp.camunda7.TestCollaborators.complete());

    assertFalse(
        processService.workflowVisibilityDelay().isWaiting(),
        "Camunda 7 must not make the core wait for a workflow to show up");

  }

}
