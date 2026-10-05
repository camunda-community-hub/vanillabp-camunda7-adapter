package io.vanillabp.camunda7.it;

import java.math.BigDecimal;

/**
 * The nested value of {@link LossyFormatAggregate}, built on demand. It carries the one
 * member the model reads, so a warning about it names a path rather than a name.
 */
public class LossyFormatOrder {

  private final BigDecimal total;

  public LossyFormatOrder(
      final BigDecimal total) {

    this.total = total;

  }

  public BigDecimal getTotal() {

    return total;

  }

}
