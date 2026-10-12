package io.vanillabp.camunda7.quarkus.runtime;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;

/**
 * Forces the {@link Camunda7QuarkusEngineRegistry} to be built when the application
 * starts. Configuration is validated AT STARTUP, never first at runtime. That is a core
 * principle of VanillaBP. Without this observer the CDI producer would run lazily. Then a
 * configuration defect (unknown datasource name, broken engine schema) would show up only
 * when the deployment pipeline first touches the engine. Two adapter ids on one database are
 * not checked here. The platform's deployment pipeline checks them through
 * {@code validateDistinctAdapterInstances}. The default
 * observer priority also puts this BEFORE the platform's deployment runner.
 */
@ApplicationScoped
public class Camunda7StartupObserver {

  /**
   * Quarkus builds the bean to deliver the startup event below.
   */
  public Camunda7StartupObserver() {

  }

  void onStart(
      @Observes final StartupEvent event,
      final Instance<Camunda7QuarkusEngineRegistry> engineRegistry) {

    // resolving the instance forces the producer to run, and with it the startup
    // validation and the eager construction of the engines
    engineRegistry.get();

  }

}
