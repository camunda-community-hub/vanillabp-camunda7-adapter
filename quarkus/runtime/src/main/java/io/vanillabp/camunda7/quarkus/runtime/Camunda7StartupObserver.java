package io.vanillabp.camunda7.quarkus.runtime;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;

/**
 * Forces the {@link Camunda7QuarkusEngineRegistry} to be built on application
 * startup: configuration is validated AT STARTUP, never first at runtime (a
 * VanillaBP core principle) - without this observer the CDI producer would run
 * lazily and a configuration defect (unknown datasource name, shared datasource,
 * broken engine schema) would surface only when the deployment pipeline first
 * touches the engine. The default observer priority also orders this BEFORE the
 * platform's deployment runner.
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

    // resolving the instance forces the producer (and with it the startup
    // validation and the eager engine construction) to run
    engineRegistry.get();

  }

}
