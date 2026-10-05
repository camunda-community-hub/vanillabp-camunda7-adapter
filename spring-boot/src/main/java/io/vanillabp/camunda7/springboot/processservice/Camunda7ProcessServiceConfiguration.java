package io.vanillabp.camunda7.springboot.processservice;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import io.vanillabp.camunda7.springboot.Camunda7AdapterBeanRegistrar;
import io.vanillabp.camunda7.springboot.VanillaBpCamunda7Properties;

/**
 * Wires the Camunda 7 adapter's per-adapter-id beans, registered by the imported
 * {@link Camunda7AdapterBeanRegistrar}: ONE embedded engine
 * ({@code Camunda7EngineHolder}) plus ONE process-service and ONE deployment-service
 * <i>element</i> bean per configured adapter id of type {@code camunda7}. Without any
 * configured {@code camunda7} adapter id no bean is registered (the registrar derives
 * the id set from the configuration), so no {@code @Conditional} gating is needed
 * here. Auto-configuration ordering relative to the datasource/transaction-manager
 * auto-configurations is irrelevant, too: the engine holders resolve the
 * application's {@code DataSource}/{@code PlatformTransactionManager} LAZILY at
 * bean-creation time (context refresh), with a guiding failure if none is available.
 */
@AutoConfiguration
@EnableConfigurationProperties(VanillaBpCamunda7Properties.class)
@Import(Camunda7AdapterBeanRegistrar.class)
public class Camunda7ProcessServiceConfiguration {

  /**
   * Spring Boot builds the class to apply the annotations above. It declares no bean
   * method of its own: the beans of this adapter are registered per configured adapter id,
   * which only the registrar can do.
   */
  public Camunda7ProcessServiceConfiguration() {

  }

}
