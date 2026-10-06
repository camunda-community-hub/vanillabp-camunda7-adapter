package io.vanillabp.camunda7.it;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.adapter.migration.processservice.DeliveryRecords;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an application is told at startup when an adapter id may repeat a delivery and
 * nothing is there to remember one. The core writes that message for every adapter
 * answering {@code deliversTasksAtLeastOnce}, so this is where the datasource mode of
 * this adapter becomes visible to the developer:
 * <ul>
 * <li>an adapter id on a datasource of its own is named by the guiding message;</li>
 * <li>an application whose adapter ids all share the application's datasource hears
 * nothing, because no delivery of theirs is deduplicated.</li>
 * </ul>
 * Both applications are booted WITHOUT the platform's JDBC delivery log, which is the
 * situation the message is about; everything else about them is the same.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7MissingDeliveryLogIT {

  /**
   * The message the core writes, recognized by the words naming the missing log rather
   * than by the whole text. The core publishes those words, so a rewording there changes
   * nothing here.
   */
  private static final String MISSING_DELIVERY_LOG = DeliveryRecords.NO_DELIVERY_LOG;

  /**
   * Boots the test application without the platform's JDBC delivery log. Command-line
   * arguments have the highest precedence, so they outrank the application.yaml of this
   * module.
   *
   * @param properties What this scenario configures on top
   * @return The booted application, to be closed by the caller
   */
  private org.springframework.context.ConfigurableApplicationContext bootWithoutADeliveryLog(
      final String... properties) {

    final var arguments = java.util.stream.Stream
        .concat(
            java.util.stream.Stream
                .of(
                    "spring.autoconfigure.exclude=io.vanillabp.integration.delivery.JdbcTaskDeliveryLogAutoConfiguration"),
            java.util.stream.Stream.of(properties))
        .map("--%s"::formatted)
        .toArray(String[]::new);
    return new SpringApplicationBuilder(TestApplication.class, NamedDataSourceConfiguration.class)
        .web(WebApplicationType.NONE)
        .run(arguments);

  }

  /**
   * The application-provided datasource bean the own-datasource adapter id runs on.
   */
  @org.springframework.boot.test.context.TestConfiguration
  public static class NamedDataSourceConfiguration {

    @org.springframework.context.annotation.Bean(defaultCandidate = false)
    public javax.sql.DataSource ownEngineDataSource() {

      return new org.springframework.jdbc.datasource.SimpleDriverDataSource(
          new org.h2.Driver(), "jdbc:h2:mem:c7-missing-delivery-log-engine;DB_CLOSE_DELAY=-1");

    }

  }

  @Test
  @DisplayName("An adapter id on its own datasource is named by the missing-delivery-log message")
  public void ownDataSourceIsNamedByTheMissingDeliveryLogMessage(
      final CapturedOutput output) {

    final var alreadyLogged = output.getAll().length();

    try (var application = bootWithoutADeliveryLog(
        "spring.datasource.url=jdbc:h2:mem:c7-missing-delivery-log;DB_CLOSE_DELAY=-1",
        "vanillabp.prioritized-adapters=c7,c7own", "vanillabp.adapters.c7own.type=camunda7",
        "vanillabp.adapters.c7own.name-clash-avoidance=by-adapter",
        "vanillabp.adapters.c7own.data-source-name=ownEngineDataSource",
        "vanillabp.workflow-modules.c7-it.adapters.c7own.resources-location=classpath*:c7-it/processes")) {
      Assertions.assertTrue(application.isActive());
    }

    final var log = output.getAll().substring(alreadyLogged);
    Assertions.assertTrue(log.contains(MISSING_DELIVERY_LOG), () -> "expected the guiding message but got: "
        + log);
    Assertions.assertTrue(log.contains("adapter 'c7own'"), () -> "expected the adapter id but got: "
        + log);
    Assertions
        .assertTrue(
            log.contains("vanillabp.adapters.c7own.deduplicate-deliveries"),
            () -> "expected the remedy naming the property key but got: "
                + log);

  }

  @Test
  @DisplayName("An application on the shared datasource alone hears nothing about a delivery log")
  public void sharedDataSourceSaysNothingAboutADeliveryLog(
      final CapturedOutput output) {

    final var alreadyLogged = output.getAll().length();

    try (var application = bootWithoutADeliveryLog(
        "spring.datasource.url=jdbc:h2:mem:c7-missing-delivery-log-shared;DB_CLOSE_DELAY=-1")) {
      Assertions.assertTrue(application.isActive());
    }

    Assertions
        .assertFalse(
            output.getAll().substring(alreadyLogged).contains(MISSING_DELIVERY_LOG),
            "an engine delivering in the application's transaction needs no delivery log");

  }

}
