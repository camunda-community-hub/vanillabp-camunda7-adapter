package io.vanillabp.camunda7.quarkus.expressions;

/**
 * One item of an order.
 */
public class ExprItem {

  private final String name;

  public ExprItem(
      final String name) {

    this.name = name;

  }

  public String getName() {

    return name;

  }

}
