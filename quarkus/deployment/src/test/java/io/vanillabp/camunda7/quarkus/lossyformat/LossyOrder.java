package io.vanillabp.camunda7.quarkus.lossyformat;

import java.math.BigDecimal;

/**
 * A nested order which carries the same decimal as the aggregate. Nested means that it
 * travels through the engine's serialization format. That is what the check is about.
 */
public class LossyOrder {

  private final BigDecimal total;

  public LossyOrder(
      final BigDecimal total) {

    this.total = total;

  }

  public BigDecimal getTotal() {

    return total;

  }

}
