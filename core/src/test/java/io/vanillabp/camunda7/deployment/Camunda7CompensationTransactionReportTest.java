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
 * What a boot says about the transaction a compensation runs in.
 * <p>
 * This adapter gives every service-like task a job and therefore a transaction of its own, and
 * a compensation handler is the one place where the engine does not follow: it starts the
 * handler outside the normal flow, where no job is created, so the handlers of a throw event
 * run in that event's transaction. {@code Camunda7CompensationTokensTest} measures it on a
 * running engine, and this test asserts that somebody is told while the application boots.
 * <p>
 * A hint and not a refusal, which is why the model carrying no compensation is asserted too: a
 * warning about a model this does not happen to would be worse than none.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7CompensationTransactionReportTest {

  private static final String MODULE = "travel-booking";

  private static final String PROCESS = "TravelBooking";

  private static final String FILE = "travel-booking.bpmn";

  /**
   * Two activities which can be compensated, drawn the way a modeller draws them: a
   * compensation boundary event on each, and an association to the handler undoing it.
   */
  private static final String TWO_ACTIVITIES_WITH_A_HANDLER = """
          <bpmn:serviceTask id="Activity_Book" camunda:delegateExpression="${book}" />
          <bpmn:serviceTask id="Activity_Pay" camunda:delegateExpression="${pay}" />
          <bpmn:boundaryEvent id="Event_BookWasMade" attachedToRef="Activity_Book">
            <bpmn:compensateEventDefinition id="Compensate_Book" />
          </bpmn:boundaryEvent>
          <bpmn:boundaryEvent id="Event_PaymentWasMade" attachedToRef="Activity_Pay">
            <bpmn:compensateEventDefinition id="Compensate_Pay" />
          </bpmn:boundaryEvent>
          <bpmn:serviceTask id="Activity_CancelBooking" isForCompensation="true" camunda:delegateExpression="${cancelBooking}" />
          <bpmn:serviceTask id="Activity_RefundPayment" isForCompensation="true" camunda:delegateExpression="${refundPayment}" />
          <bpmn:association id="To_CancelBooking" associationDirection="One" sourceRef="Event_BookWasMade" targetRef="Activity_CancelBooking" />
          <bpmn:association id="To_RefundPayment" associationDirection="One" sourceRef="Event_PaymentWasMade" targetRef="Activity_RefundPayment" />
      """;

  @Test
  @DisplayName("A throw event starting two handlers is reported with both of them and what it costs")
  public void twoHandlersAreReported() {

    final var logged = warningsWhileDeploying("""
            <bpmn:intermediateThrowEvent id="Event_UndoEverything">
              <bpmn:compensateEventDefinition id="Throw_All" />
            </bpmn:intermediateThrowEvent>
        """ + TWO_ACTIVITIES_WITH_A_HANDLER);

    assertEquals(1, logged.size(), logged::toString);
    final var message = logged.getFirst();
    assertTrue(message.contains("'Event_UndoEverything'"), () -> "the throw event is named: "
        + message);
    assertTrue(message.contains("2 compensation handler"), () -> "how many handlers it starts: "
        + message);
    assertTrue(
        message.contains("Activity_CancelBooking") && message.contains("Activity_RefundPayment"),
        () -> "and which ones they are: "
            + message);
    assertTrue(
        message.contains("in the transaction of the throw event"),
        () -> "the transaction they share: "
            + message);
    assertTrue(
        message.contains("share one transaction"),
        () -> "what that costs their side effects: "
            + message);
    assertTrue(
        message.contains("running it twice does no harm"),
        () -> "and what the developer can do about it: "
            + message);

  }

  @Test
  @DisplayName("A throw event naming one activity is reported as well")
  public void oneHandlerIsReportedToo() {

    final var logged = warningsWhileDeploying("""
            <bpmn:intermediateThrowEvent id="Event_UndoTheBooking">
              <bpmn:compensateEventDefinition id="Throw_Book" activityRef="Activity_Book" />
            </bpmn:intermediateThrowEvent>
        """ + TWO_ACTIVITIES_WITH_A_HANDLER);

    assertEquals(1, logged.size(), logged::toString);
    final var message = logged.getFirst();
    assertTrue(message.contains("1 compensation handler"), () -> "one handler, and still worth "
        + "saying, because a single handler does not get a transaction of its own either: "
        + message);
    assertTrue(message.contains("Activity_CancelBooking"), message);

  }

  @Test
  @DisplayName("A model compensating nothing is not reported")
  public void withoutCompensationNothingIsReported() {

    assertEquals(
        List.of(),
        warningsWhileDeploying("""
                <bpmn:serviceTask id="Activity_Book" camunda:delegateExpression="${book}" />
            """),
        "nothing of this model runs in somebody else's transaction");

  }

  /**
   * What the adapter warned about while it wired a model carrying the given elements. The
   * module's logback-test.xml attaches no appender, so the list is this test's own.
   *
   * @param processContent The elements of the process to deploy
   * @return The warnings about the transaction of a compensation, in the order they were made
   */
  private static List<String> warningsWhileDeploying(
      final String processContent) {

    final var xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn" id="defs" targetNamespace="http://bpmn.io/schema/bpmn">
          <bpmn:process id="%s" isExecutable="true">
        %s
          </bpmn:process>
        </bpmn:definitions>
        """
        .formatted(PROCESS, processContent);
    final var service = new Camunda7DeploymentService(
        "c7", null, mock(Camunda7WorkflowProcessingLifecycle.class), TestCollaborators
            .builder()
            .build(), new Camunda7TaskRegistry());
    final var model = Bpmn
        .readModelFromStream(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
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
        .filter(message -> message.contains("compensation throw event"))
        .toList();

  }

}
