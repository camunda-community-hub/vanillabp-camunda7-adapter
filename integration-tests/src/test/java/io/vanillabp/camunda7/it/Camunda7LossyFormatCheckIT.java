package io.vanillabp.camunda7.it;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the boot says about a value whose type the configured serialization format cannot
 * carry, read from the log of a real engine.
 * <p>
 * The model of this scenario reads a decimal at both levels: {@code total} is an object
 * variable of its own, {@code order.total} travels inside the map the sync model built.
 * Under SPIN JSON the first loses its scale and the second loses its class, and neither
 * of them looks wrong anywhere until somebody reads a rendered value. The boot is where
 * that can still be acted on.
 * <p>
 * The negative case is the same module with no format configured, which has to stay
 * quiet: Java serialization writes every one of these types and reads it back unchanged,
 * and what such an application does pay is named by the missing-format warning instead.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7LossyFormatCheckIT {

  /**
   * The boot log of an application deploying the model which reads the decimal.
   *
   * @param output What the test class captured so far
   * @param arguments What this world configures beyond the module
   * @return Everything logged while this application booted
   */
  private String bootLog(
      final CapturedOutput output,
      final String... arguments) {

    final var alreadyLogged = output
        .getAll()
        .length();
    final var startup = new java.util.ArrayList<String>(
        java.util.List
            .of(
                "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/lossy-format",
                "--spring.profiles.active=lossy-format"));
    startup.addAll(java.util.List.of(arguments));
    try (var application = new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        .run(startup.toArray(String[]::new))) {
      Assertions.assertTrue(application.isActive(), "the check warns, it never fails a boot");
    }
    return output
        .getAll()
        .substring(alreadyLogged);

  }

  @Test
  @DisplayName("A format which drops the scale is named, with the attribute, the format and what comes back")
  public void aFormatWhichDropsTheScaleIsNamed(
      final CapturedOutput output) {

    final var log = bootLog(
        output,
        "--spring.datasource.url=jdbc:h2:mem:c7-lossy-format-json;DB_CLOSE_DELAY=-1",
        "--vanillabp.adapters.c7.serialization-format=application/json",
        "--vanillabp.adapters.c7.engine-plugins.spin.plugin-class=org.camunda.spin.plugin.impl.SpinProcessEnginePlugin");

    final var topLevel = warningAbout(log, "'total'");
    Assertions
        .assertTrue(
            topLevel.contains("as a java.math.BigDecimal"),
            () -> "expected the declared type but got: "
                + topLevel);
    Assertions
        .assertTrue(
            topLevel.contains("'application/json'"),
            () -> "expected the format which was configured but got: "
                + topLevel);
    Assertions
        .assertTrue(
            topLevel.contains("reads a value of 120.50 back as 120.5"),
            () -> "expected what the measurement found but got: "
                + topLevel);
    Assertions
        .assertTrue(
            topLevel.contains("@NoSyncWithBPMS"),
            () -> "expected the ways out but got: "
                + topLevel);

    // one level down the same format loses the class as well, because nothing in the map
    // records what its members were
    final var nested = warningAbout(log, "'order.total'");
    Assertions
        .assertTrue(
            nested.contains("back as a java.lang.Double of 120.5"),
            () -> "expected the class the member comes back as but got: "
                + nested);

  }

  @Test
  @DisplayName("Nothing is said about a value the engine has a type for")
  public void aValueTheEngineHasATypeForStaysQuiet(
      final CapturedOutput output) {

    final var log = bootLog(
        output,
        "--spring.datasource.url=jdbc:h2:mem:c7-lossy-format-json-quiet;DB_CLOSE_DELAY=-1",
        "--vanillabp.adapters.c7.serialization-format=application/json",
        "--vanillabp.adapters.c7.engine-plugins.spin.plugin-class=org.camunda.spin.plugin.impl.SpinProcessEnginePlugin");

    // a String is a string variable and never meets a serializer, so there is nothing to
    // measure and nothing to say
    Assertions
        .assertFalse(
            log.contains("shares 'reference'"),
            "a value the engine stores as itself is no subject of this check");

  }

  @Test
  @DisplayName("With no format configured the check says nothing about round-tripping")
  public void withoutAFormatTheCheckSaysNothing(
      final CapturedOutput output) {

    final var log = bootLog(output, "--spring.datasource.url=jdbc:h2:mem:c7-lossy-format-plain;DB_CLOSE_DELAY=-1");

    // Java serialization carries all of these exactly, so an application which configures
    // no format loses no digit and hears nothing here. What it does pay for is the blob in
    // Cockpit, which the missing-format warning names when the first value is written
    Assertions
        .assertFalse(
            log.contains("cannot carry that type without loss"),
            () -> "nothing round-trips badly without a format of ours:%n%s".formatted(log));

  }

  /**
   * The one warning of the boot log naming that attribute.
   *
   * @param log The boot log
   * @param sharedName The attribute in quotes, as the message spells it
   * @return The line, for the assertions to read
   */
  private static String warningAbout(
      final String log,
      final String sharedName) {

    final var line = log
        .lines()
        .filter(candidate -> candidate.contains("shares "
            + sharedName))
        .findFirst();
    Assertions
        .assertTrue(
            line.isPresent(),
            () -> "no warning about %s in:%n%s".formatted(sharedName, log));
    return line.get();

  }

}
