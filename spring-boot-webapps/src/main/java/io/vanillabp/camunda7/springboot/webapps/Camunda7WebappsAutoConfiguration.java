package io.vanillabp.camunda7.springboot.webapps;

import org.camunda.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder;
import io.vanillabp.camunda7.springboot.processservice.Camunda7ProcessServiceConfiguration;

/**
 * Serves Camunda's web applications at <code>/camunda</code> against the engines
 * VanillaBP built.
 *
 * <p>
 * Two things are needed for that, and neither is obvious.
 * </p>
 *
 * <ol>
 * <li>Camunda's webapp auto-configuration only applies when a {@link CamundaBpmProperties}
 * bean exists. That bean normally comes with Camunda's engine auto-configuration. This
 * module switches that one off, because it would build a second engine (see
 * {@link Camunda7WebappsEnvironmentPostProcessor}). So this module adds the properties
 * bean itself. Its defaults are what the webapps need, including the path
 * <code>/camunda</code>.</li>
 * <li>The webapps find engines through the runtime container, not through beans. So
 * {@link Camunda7WebappsRegistration} registers VanillaBP's engines there.</li>
 * </ol>
 */
@AutoConfiguration(after = Camunda7ProcessServiceConfiguration.class)
@ConditionalOnWebApplication(type = Type.SERVLET)
@EnableConfigurationProperties({
    CamundaBpmProperties.class, Camunda7WebappsProperties.class
})
public class Camunda7WebappsAutoConfiguration {

  /**
   * Spring Boot builds the class to read the bean method below.
   */
  public Camunda7WebappsAutoConfiguration() {

  }

  /**
   * Registers VanillaBP's engines with the Camunda webapps, so Cockpit, Tasklist and Admin
   * show them. The engines are resolved lazily. They are built while the context
   * refreshes, and this bean is defined before that.
   *
   * @param engines The engines VanillaBP built, one per configured adapter id
   * @param properties Which adapter ids the webapps serve, and who the administrator is
   * @return The registration, which runs when the context is ready
   */
  @Bean
  public Camunda7WebappsRegistration vanillaBpCamunda7WebappsRegistration(
      final ObjectProvider<Camunda7EngineHolder> engines,
      final Camunda7WebappsProperties properties) {

    return new Camunda7WebappsRegistration(engines, properties);

  }

}
