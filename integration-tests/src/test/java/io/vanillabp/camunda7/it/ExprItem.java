package io.vanillabp.camunda7.it;

import java.math.BigDecimal;

/**
 * One element of the collection a multi-instance element iterates. Carries a
 * {@link BigDecimal} so the nested conversion of a number can be told apart from the
 * top-level one.
 */
public class ExprItem {

  private final String name;

  private final BigDecimal price;

  public ExprItem(
      final String name,
      final BigDecimal price) {

    this.name = name;
    this.price = price;

  }

  public String getName() {

    return name;

  }

  public BigDecimal getPrice() {

    return price;

  }

}
