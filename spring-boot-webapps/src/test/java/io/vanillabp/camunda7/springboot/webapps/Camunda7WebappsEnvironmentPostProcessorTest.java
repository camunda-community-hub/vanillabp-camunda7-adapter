package io.vanillabp.camunda7.springboot.webapps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.mock.env.MockEnvironment;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Camunda's engine auto-configuration must not run next to VanillaBP's engines. This is
 * where that is decided, before any bean is created.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7WebappsEnvironmentPostProcessorTest {

  private final Camunda7WebappsEnvironmentPostProcessor postProcessor = new Camunda7WebappsEnvironmentPostProcessor();

  @Test
  @DisplayName("Camunda's engine auto-configuration is switched off by default")
  public void switchedOffByDefault() {

    final var environment = new MockEnvironment();

    postProcessor.postProcessEnvironment(environment, null);

    assertEquals(
        "false",
        environment.getProperty(Camunda7WebappsEnvironmentPostProcessor.CAMUNDA_BPM_ENABLED));

  }

  @Test
  @DisplayName("An application switching it off itself is left alone")
  public void applicationMayDisableItItself() {

    final var environment = new MockEnvironment()
        .withProperty(Camunda7WebappsEnvironmentPostProcessor.CAMUNDA_BPM_ENABLED, "false");

    postProcessor.postProcessEnvironment(environment, null);

    assertEquals(
        1,
        environment
            .getPropertySources()
            .size(),
        "nothing has to be added when the application said it already");

  }

  @Test
  @DisplayName("Switching it on fails the start, naming the property and the reason")
  public void switchingItOnFails() {

    final var environment = new MockEnvironment()
        .withProperty(Camunda7WebappsEnvironmentPostProcessor.CAMUNDA_BPM_ENABLED, "true");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> postProcessor.postProcessEnvironment(environment, null));

    assertTrue(exception.getMessage().contains("camunda.bpm.enabled"), exception::getMessage);
    assertTrue(exception.getMessage().contains("second engine"), exception::getMessage);
    assertTrue(
        exception.getMessage().contains("vanillabp.adapters.<id>"),
        exception::getMessage);

  }

}
