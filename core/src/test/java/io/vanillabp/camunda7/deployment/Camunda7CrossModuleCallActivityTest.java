package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.camunda.bpm.model.bpmn.Bpmn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.TestCollaborators;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a boot says about a call activity which calls a process of ANOTHER workflow module.
 * <p>
 * Everything VanillaBP scopes is scoped per workflow module, a BPMN error code among it. So a
 * called process of another module raises its errors under that module's prefix while the
 * error boundary event of the call activity waits for the prefix of the calling one, and the
 * called workflow fails with an incident instead. The only model which can get there names
 * {@code camunda:calledElementTenantId}: every other called element is resolved in the
 * tenant of the calling instance.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7CrossModuleCallActivityTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String FILE = "loan-approval.bpmn";

  private static String model(
      final String callActivity) {

    return """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="LoanApproval" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(callActivity);

  }

  @Test
  @DisplayName("A call activity naming another tenant is reported with what it costs a BPMN error")
  public void anotherTenantIsReported() {

    final var logged = warningsWhileDeploying(
        """
                <bpmn:callActivity id="Activity_invoice" calledElement="Invoicing" camunda:calledElementTenantId="billing" />
            """);

    assertEquals(1, logged.size(), logged::toString);
    final var message = logged.getFirst();
    assertTrue(message.contains("'Activity_invoice'"), () -> "the call activity is named: "
        + message);
    assertTrue(message.contains("'billing'"), () -> "and the tenant it sends the engine to: "
        + message);
    assertTrue(
        message.contains("carries the prefix of ITS workflow module"),
        () -> "the reason the error finds no catcher: "
            + message);
    assertTrue(
        message.contains("incident"),
        () -> "what the developer would otherwise see first: "
            + message);
    assertTrue(
        message.contains("into this workflow module"),
        () -> "and a way out: "
            + message);

  }

  @Test
  @DisplayName("A call activity naming no tenant stays in its module and is not reported")
  public void withoutATenantNothingIsReported() {

    assertEquals(
        List.of(),
        warningsWhileDeploying("""
                <bpmn:callActivity id="Activity_assess" calledElement="RiskAssessment" />
            """),
        "this engine resolves the called process in the tenant of the calling instance, which "
            + "is this workflow module");

  }

  /**
   * What the adapter warned about while it wired a model carrying the given call activity.
   * The module's logback-test.xml attaches no appender, so the list is this test's own.
   */
  private static List<String> warningsWhileDeploying(
      final String callActivity) {

    final var service = new Camunda7DeploymentService(
        "c7", null, mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .build(), new Camunda7TaskRegistry());
    final var model = Bpmn
        .readModelFromStream(new ByteArrayInputStream(model(callActivity).getBytes(StandardCharsets.UTF_8)));
    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var adapterLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(Camunda7DeploymentService.class);
    adapterLog.addAppender(logWatcher);
    try {
      final var context = service.prepareBpmn(MODULE, null, FILE, PROCESS, model);
      service.wireBpmn(MODULE, FILE, PROCESS, model, context);
    } finally {
      adapterLog.detachAndStopAllAppenders();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .filter(message -> message.contains("names the tenant"))
        .toList();

  }

}
