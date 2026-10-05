package io.vanillabp.camunda7;

/**
 * Constants shared by all modules of the Camunda 7 adapter.
 */
public final class Camunda7Adapter {

  /**
   * The adapter type of this adapter. Configured per adapter id via
   * {@code vanillabp.adapters.<id>.type=camunda7} and announced to the
   * VanillaBP platform integrations.
   */
  public static final String ADAPTER_TYPE = "camunda7";

  private Camunda7Adapter() {
    // constants holder
  }

}
