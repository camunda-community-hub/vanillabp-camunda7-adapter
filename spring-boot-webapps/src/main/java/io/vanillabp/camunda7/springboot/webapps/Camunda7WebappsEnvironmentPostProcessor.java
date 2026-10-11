package io.vanillabp.camunda7.springboot.webapps;

import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Switches off Camunda's own engine auto-configuration, which arrives with the webapp
 * starter this module depends on.
 *
 * <p>
 * That auto-configuration always builds a {@code ProcessEngineFactoryBean}. So an
 * application would end up with two engines on one datasource. One is the engine VanillaBP
 * built and configured. The other is one Camunda built from <code>camunda.bpm.*</code>.
 * Both would run a job executor, and the second one would take the jobs of the first. The
 * webapps themselves do not need it. They find engines through the
 * {@code RuntimeContainerDelegate}, and {@link Camunda7WebappsRegistration} registers
 * VanillaBP's engines there.
 * </p>
 *
 * <p>
 * The value is added as the LAST property source, so an application can still see and
 * override it. Setting it to <code>true</code> is a defect, not a choice. So the start
 * fails with a message which says why.
 * </p>
 */
public class Camunda7WebappsEnvironmentPostProcessor implements EnvironmentPostProcessor {

  /**
   * Spring Boot builds the class from <code>spring.factories</code>, before the context
   * exists.
   */
  public Camunda7WebappsEnvironmentPostProcessor() {

  }

  static final String CAMUNDA_BPM_ENABLED = "camunda.bpm.enabled";

  private static final String PROPERTY_SOURCE_NAME = "vanillabp-camunda7-webapps";

  @Override
  public void postProcessEnvironment(
      final ConfigurableEnvironment environment,
      final SpringApplication application) {

    final var configured = environment.getProperty(CAMUNDA_BPM_ENABLED);

    if (Boolean.parseBoolean(configured)) {

      throw new IllegalStateException(
          """
              '%s' is set to true, but VanillaBP builds and owns the Camunda 7 engines. \
              Camunda's engine auto-configuration would add a second engine on the same \
              datasource, whose job executor would acquire the jobs of the first one. \
              Remove the property (the webapps do not need it) and configure the engine \
              at 'vanillabp.adapters.<id>.*' instead."""
              .formatted(CAMUNDA_BPM_ENABLED));

    }

    if (configured != null) {
      // explicitly disabled by the application - nothing to add
      return;
    }

    environment
        .getPropertySources()
        .addLast(new MapPropertySource(
            PROPERTY_SOURCE_NAME, Map.of(CAMUNDA_BPM_ENABLED, "false")));

  }

}
