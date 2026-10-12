package io.vanillabp.camunda7.quarkus;

import java.util.List;
import java.util.logging.LogRecord;
import java.util.regex.Matcher;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.lossyformat.LossyAggregate;
import io.vanillabp.camunda7.quarkus.lossyformat.LossyOrder;
import io.vanillabp.camunda7.quarkus.lossyformat.LossyPersistence;
import io.vanillabp.camunda7.quarkus.lossyformat.LossyWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The startup check on Quarkus which measures what the configured serialization format
 * makes of the values an aggregate shares. A nested value travels in that format. A format
 * which reads a decimal back as a double changes what a model compares. The number the
 * gateway sees is not the number the application wrote. The engine has a variable type for
 * a top-level decimal. So the same attribute is safe there and lossy one level down. That
 * is exactly the surprise the check exists to name.
 * <p>
 * Like the check about unshared paths, it is written once in the core. It used to run on
 * Spring Boot only. Running it here shows that the Quarkus half of this adapter does the
 * same thing.
 * <p>
 * The engine needs a JSON dataformat for this, which the application configures as an engine
 * plugin. So the test also drives the plugin configuration end to end. Nothing on Quarkus
 * did that before.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7LossyFormatCheckTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(LossyAggregate.class)
          .addClass(LossyOrder.class)
          .addClass(LossyPersistence.class)
          .addClass(LossyWorkflowService.class)
          .addAsResource("lossy-format/application.yaml", "application.yaml")
          .addAsResource(
              "c7-lossy-format/processes/lossy-format.bpmn",
              "c7-lossy-format/processes/lossy-format.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .setLogRecordPredicate(record -> record
          .getLoggerName()
          .startsWith("io.vanillabp"))
      .assertLogRecords(Camunda7LossyFormatCheckTest::theCheckReportedWhatTheFormatMadeOfIt);

  private static void theCheckReportedWhatTheFormatMadeOfIt(
      final List<LogRecord> records) {

    final var log = records
        .stream()
        .map(Camunda7LossyFormatCheckTest::textOf)
        .toList();

    final var nested = about(log, "order.total");
    Assertions.assertTrue(nested.contains("java.math.BigDecimal"), () -> nested);
    Assertions.assertTrue(nested.contains("application/json"), () -> nested);
    Assertions
        .assertTrue(
            nested.contains("java.lang.Double"),
            () -> "the report has to name what came back instead: "
                + nested);

    // a String survives every format. So anything said about it would be noise
    final var everything = String.join("\n", log);
    Assertions
        .assertFalse(
            everything.contains("shares 'reference'"),
            () -> "a value the format carries must not be reported: "
                + everything);

  }

  private static String about(
      final List<String> log,
      final String attribute) {

    final var reported = log
        .stream()
        .filter(line -> line.contains("shares '"
            + attribute
            + "'"))
        .toList();
    Assertions
        .assertEquals(
            1,
            reported.size(),
            () -> "expected exactly one report about '%s' but the boot wrote:%n%s"
                .formatted(attribute, String.join("\n", log)));
    return reported.getFirst();

  }

  /**
   * What a record says, with its parameters filled in. The adapter logs through SLF4J. So a
   * record still carries the pattern and the values apart.
   */
  private static String textOf(
      final LogRecord record) {

    var text = String.valueOf(record.getMessage());
    final var parameters = record.getParameters();
    if (parameters != null) {
      for (final var parameter : parameters) {
        text = text.replaceFirst("\\{}", Matcher.quoteReplacement(String.valueOf(parameter)));
      }
    }
    return text;

  }

  @Test
  public void theCheckRunsWhileTheApplicationBoots() {
    // the assertions happen on the records of the boot (assertLogRecords above)
  }

}
