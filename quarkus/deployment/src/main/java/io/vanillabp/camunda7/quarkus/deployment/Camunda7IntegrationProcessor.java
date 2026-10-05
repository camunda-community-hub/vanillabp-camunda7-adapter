package io.vanillabp.camunda7.quarkus.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.camunda7.Camunda7Adapter;
import io.vanillabp.camunda7.quarkus.deployment.config.Camunda7Properties;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7AdapterProducer;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7EngineProducer;
import io.vanillabp.camunda7.quarkus.runtime.Camunda7StartupObserver;
import io.vanillabp.integration.deployment.pipeline.VanillaBpAdapterDeploymentServiceBuildItem;
import io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem;

/**
 * Quarkus extension of the VanillaBP Camunda 7 adapter (embedded plain engine;
 * JVM mode only). Announces the adapter type, its process-service and its
 * deployment-service beans to the VanillaBP Quarkus integration and registers the
 * engine producer plus the startup observer forcing the engine construction (and
 * with it the configuration validation) at boot.
 */
class Camunda7IntegrationProcessor {

  private static final String FEATURE = "vanillabp-camunda7";

  /**
   * Builds the {@link VanillaBpMigratableProcessServiceBuildItem} used by the
   * VanillaBP Quarkus integration to determine the process-service bean of the
   * Camunda 7 adapter.
   *
   * @param properties Camunda 7 build-time properties (forces config-root registration)
   * @param featureProducer Feature build-item producer used to register the extension
   * @return The {@link VanillaBpMigratableProcessServiceBuildItem}
   */
  @BuildStep
  VanillaBpMigratableProcessServiceBuildItem buildProcessServices(
      final Camunda7Properties properties,
      final BuildProducer<FeatureBuildItem> featureProducer) {

    featureProducer.produce(new FeatureBuildItem(FEATURE));

    return VanillaBpMigratableProcessServiceBuildItem
        .builder()
        .adapterType(Camunda7Adapter.ADAPTER_TYPE)
        // the announced bean class is registered by the VanillaBP extension - it
        // has to be the producer, not the core process-service class
        .migratableProcessServiceBeanClass(Camunda7AdapterProducer.class.getName())
        .build();

  }

  /**
   * Builds the {@link VanillaBpAdapterDeploymentServiceBuildItem} used by the
   * VanillaBP Quarkus integration to determine the deployment-service bean of the
   * Camunda 7 adapter - consumed by the platform's runtime deployment pipeline
   * (readBpmn &rarr; prepareBpmn &rarr; wireBpmn &rarr; deployResources &rarr;
   * startWorkflowProcessing).
   *
   * @return The {@link VanillaBpAdapterDeploymentServiceBuildItem}
   */
  @BuildStep
  VanillaBpAdapterDeploymentServiceBuildItem buildDeploymentServices() {

    return VanillaBpAdapterDeploymentServiceBuildItem
        .builder()
        .adapterType(Camunda7Adapter.ADAPTER_TYPE)
        .deploymentServiceBeanClass(Camunda7AdapterProducer.class.getName())
        .build();

  }

  /**
   * Registers the remaining runtime beans: the engine producer (one embedded engine
   * per configured adapter id) and the startup observer forcing it at boot.
   *
   * @return The bean registration build item
   */
  @BuildStep
  AdditionalBeanBuildItem registerEngineProducer() {

    // the process-service and deployment-service producers are registered by the
    // VanillaBP extension via the build items above - only the other beans are
    // registered here
    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(Camunda7EngineProducer.class)
        .addBeanClass(Camunda7StartupObserver.class)
        .setUnremovable()
        .build();

  }

  /**
   * Keeps the engine customizers of an extension alive. Nothing of the application
   * injects them - the engine holder looks them up while it builds its engine - and ArC
   * removes beans nobody injects.
   *
   * @return What must not be removed
   */
  @BuildStep
  io.quarkus.arc.deployment.UnremovableBeanBuildItem keepEngineCustomizers() {

    return io.quarkus.arc.deployment.UnremovableBeanBuildItem
        .beanTypes(io.vanillabp.camunda7.engine.Camunda7EngineCustomizer.class);

  }

}
