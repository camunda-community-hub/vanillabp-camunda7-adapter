package io.vanillabp.camunda7.springboot.processservice;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import io.vanillabp.camunda7.springboot.Camunda7AdapterBeanRegistrar;
import io.vanillabp.camunda7.springboot.VanillaBpCamunda7Properties;

/**
 * Wires the beans of the Camunda 7 adapter per adapter id. The imported
 * {@link Camunda7AdapterBeanRegistrar} registers them. For each configured adapter id of
 * type {@code camunda7} there is ONE embedded engine ({@code Camunda7EngineHolder}), ONE
 * process-service <i>element</i> bean and ONE deployment-service <i>element</i> bean.
 * Without a configured {@code camunda7} adapter id no bean is registered. The registrar
 * reads the set of ids from the configuration. So no {@code @Conditional} is needed here.
 * The order relative to the datasource and transaction-manager auto-configurations does
 * not matter either. The engine holders resolve the application's {@code DataSource} and
 * {@code PlatformTransactionManager} LAZILY, when the bean is created (context refresh).
 * If none is available, they fail with a guiding message.
 */
@AutoConfiguration
@EnableConfigurationProperties(VanillaBpCamunda7Properties.class)
@Import(Camunda7AdapterBeanRegistrar.class)
public class Camunda7ProcessServiceConfiguration {

  /**
   * Spring Boot builds the class to apply the annotations above. It declares no bean
   * method of its own. The beans of this adapter are registered per configured adapter id,
   * and only the registrar can do that.
   */
  public Camunda7ProcessServiceConfiguration() {

  }

}
