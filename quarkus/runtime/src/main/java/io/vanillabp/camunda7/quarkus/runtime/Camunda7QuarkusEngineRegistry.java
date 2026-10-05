package io.vanillabp.camunda7.quarkus.runtime;

import java.util.Map;

/**
 * Holds the embedded engines of all configured {@code camunda7} adapter ids (one
 * {@link Camunda7QuarkusEngineHolder} per id), built eagerly at startup by
 * {@link Camunda7EngineProducer} (validation surfaces at boot, never first at
 * runtime). Closed on application shutdown - each holder stops its job executor
 * before closing its engine.
 */
public class Camunda7QuarkusEngineRegistry implements AutoCloseable {

  private final Map<String, Camunda7QuarkusEngineHolder> enginesByAdapterId;

  /**
   * Takes a copy, so nothing adds an engine after the startup which validated them.
   *
   * @param enginesByAdapterId The engines built at startup, one per configured adapter id
   *          of this type
   */
  public Camunda7QuarkusEngineRegistry(
      final Map<String, Camunda7QuarkusEngineHolder> enginesByAdapterId) {

    this.enginesByAdapterId = Map.copyOf(enginesByAdapterId);

  }

  /**
   * The engine of one adapter id. Every id which is configured has one by the time anybody
   * asks, because the engines are built at startup.
   *
   * @param adapterId The adapter id
   * @return The adapter id's engine holder
   */
  public Camunda7QuarkusEngineHolder engineFor(
      final String adapterId) {

    final var holder = enginesByAdapterId.get(adapterId);
    if (holder == null) {
      throw new IllegalStateException(
          "No embedded Camunda 7 engine exists for adapter '%s' - configured ids: %s"
              .formatted(adapterId, enginesByAdapterId.keySet()));
    }
    return holder;

  }

  @Override
  public void close() {

    enginesByAdapterId
        .values()
        .forEach(Camunda7QuarkusEngineHolder::close);

  }

}
