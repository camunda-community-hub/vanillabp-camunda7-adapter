package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the Camunda 7 adapter contributes to the name-clash-avoidance model: the mode
 * applying without configuration, and how an unscoped workflow module
 * is reported - the alternatives to {@link NameClashAvoidance#NONE} are Camunda 7's,
 * so the message is the adapter's. The default is {@link NameClashAvoidance#BY_ADAPTER}
 * because version 1 deployed a tenant per workflow module.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7DeploymentServiceTest {

  private static final String MODULE = "loan-approval";

  private static Camunda7DeploymentService serviceOfAdapterId(
      final String adapterId) {

    return serviceReportingEndsTo(adapterId, null);

  }

  /**
   * The service with a core which knows the given end handlers - what the deployment
   * asks while it decides whether a method of the application will ever be called.
   */
  private static Camunda7DeploymentService serviceReportingEndsTo(
      final String adapterId,
      final io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker workflowEndedInvoker) {

    return new Camunda7DeploymentService(
        adapterId, null, null, io.vanillabp.camunda7.TestCollaborators.reportingEndsTo(workflowEndedInvoker), null);

  }

  /**
   * The core's registry of <code>&#64;WorkflowEnded</code> methods, reduced to the one
   * question the deployment asks it.
   */
  private static io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker handlersFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return new io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker() {

      @Override
      public boolean workflowEndedHandlerExists(
          final String module,
          final String process) {

        return workflowModuleId.equals(module) && bpmnProcessId.equals(process);

      }

      @Override
      public void workflowEnded(
          final String module,
          final String process,
          final io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext context) {

        throw new UnsupportedOperationException("not part of this test");

      }

    };

  }

  @Test
  @DisplayName("A @WorkflowEnded method nobody will call is reported as the adapter's wiring defect")
  public void unservedWorkflowEndedHandlerIsReported() {

    final var service = serviceReportingEndsTo("c7", handlersFor(MODULE, "LoanApproval"));
    service.setEngineDeliversWorkflowEnded(false);

    final var warnings = warningsOf(() -> service.warnAboutUnservedWorkflowEndedHandlers(MODULE, "LoanApproval"));

    assertEquals(1, warnings.size(), warnings::toString);
    final var message = warnings.getFirst();
    assertTrue(message.contains("LoanApproval"), () -> message);
    assertTrue(message.contains(MODULE), () -> message);
    assertTrue(message.contains("'c7'"), () -> message);
    // Camunda 7 CAN report the end of a workflow, so this is not the application's
    // problem to solve - the message has to say whose it is
    assertTrue(message.contains("wiring defect of the adapter"), () -> message);

  }

  @Test
  @DisplayName("Nothing is said where the engine reports the end, or where no method waits for it")
  public void servedOrUnusedWorkflowEndStaysSilent() {

    final var wired = serviceReportingEndsTo("c7", handlersFor(MODULE, "LoanApproval"));
    wired.setEngineDeliversWorkflowEnded(true);
    assertEquals(
        List.of(),
        warningsOf(() -> wired.warnAboutUnservedWorkflowEndedHandlers(MODULE, "LoanApproval")),
        "the end listener is attached, so the method will be called");

    final var withoutHandler = serviceReportingEndsTo("c7", handlersFor(MODULE, "AnotherProcess"));
    withoutHandler.setEngineDeliversWorkflowEnded(false);
    assertEquals(
        List.of(),
        warningsOf(() -> withoutHandler.warnAboutUnservedWorkflowEndedHandlers(MODULE, "LoanApproval")),
        "no method of this process waits for the notification");

    final var withoutRegistry = serviceOfAdapterId("c7");
    assertEquals(
        List.of(),
        warningsOf(() -> withoutRegistry.warnAboutUnservedWorkflowEndedHandlers(MODULE, "LoanApproval")),
        "without the core's registry there is nothing to compare against");

  }

  @Test
  @DisplayName("Without configuration the mode is BY_ADAPTER - what version 1 deployed")
  public void defaultsToByAdapter() {

    assertEquals(
        NameClashAvoidance.BY_ADAPTER,
        serviceOfAdapterId("camunda7").defaultNameClashAvoidance(),
        "version 1 deployed every workflow module into a tenant named after it, so an application "
            + "upgrading without touching its configuration has to find its workflows again");

  }

  /**
   * The WARNs the adapter logged (the module's logback-test.xml has no appender on
   * purpose).
   */
  private static List<String> warningsOf(
      final Runnable action) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var adapterLog = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(Camunda7DeploymentService.class);
    adapterLog.addAppender(logWatcher);
    try {
      action.run();
    } finally {
      adapterLog.detachAndStopAllAppenders();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .toList();

  }

  @Test
  @DisplayName("An unscoped workflow module is reported naming Camunda 7's alternatives")
  public void unscopedIdentifiersAreReported() {

    final var service = serviceOfAdapterId("myengine");

    final var configured = warningsOf(() -> service.warnAboutUnscopedIdentifiers(MODULE, false));
    assertEquals(1, configured.size(), configured::toString);
    final var message = configured.getFirst();
    assertTrue(message.contains("'"
        + MODULE
        + "'"), () -> message);
    assertFalse(message.contains("nothing is configured"), () -> message);
    assertTrue(
        message.contains("vanillabp.adapters.myengine.name-clash-avoidance: by-adapter"),
        () -> message);
    assertTrue(
        message.contains("vanillabp.adapters.myengine.name-clash-avoidance: use-prefix"),
        () -> message);
    assertTrue(message.contains("data-source-name"), () -> message);
    assertTrue(message.contains("table-prefix"), () -> message);

    // the mode being the adapter's default is worth saying - the developer configured
    // nothing, so they may not know the mode at all
    final var byDefault = warningsOf(() -> service.warnAboutUnscopedIdentifiers(MODULE, true));
    assertTrue(byDefault.getFirst().contains("nothing is configured"), byDefault::toString);
    // ... and the way out of the warning is part of it
    assertTrue(
        byDefault.getFirst().contains("vanillabp.adapters.myengine.accept-unscoped-identifiers: true"),
        byDefault::toString);

  }

  @Test
  @DisplayName("A declared process nothing was deployed under is answered with the engine's catalog")
  public void aDeclaredProcessIsAnsweredWithTheEnginesCatalog() {

    final var service = serviceOfAdapterId("myengine");

    final var reported = warningsOf(() -> assertNotNull(
        service.processVersionCatalogOf(MODULE, "loan_approval"),
        "the versions this engine holds under a declared id are what it can be asked about"));

    assertEquals(
        List.of(),
        reported,
        "the versions of a declared id are a question, not a finding - what the wiring of those "
            + "models comes to is said where it happens");

  }

  @Test
  @DisplayName("Accepting unscoped identifiers deliberately silences the warning")
  public void acceptedUnscopedIdentifiersStaySilent() {

    final var service = serviceOfAdapterId("myengine");
    service.setAcceptUnscopedIdentifiers(true);

    assertEquals(
        List.of(),
        warningsOf(() -> service.warnAboutUnscopedIdentifiers(MODULE, true)),
        "the decision is on record, so there is nothing left to ask");

  }

}
