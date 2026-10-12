package io.vanillabp.camunda7.it;

import java.math.BigDecimal;

/**
 * The nested value of the parameter-type cases. Its members travel inside one object
 * variable. So the serialization format decides what they come back as, not the
 * aggregate.
 */
public class ParamTypesOrder {

  private final BigDecimal total;

  ParamTypesOrder(
      final BigDecimal total) {

    this.total = total;

  }

  public BigDecimal getTotal() {

    return total;

  }

  public String getReference() {

    return "REF-1";

  }

}
