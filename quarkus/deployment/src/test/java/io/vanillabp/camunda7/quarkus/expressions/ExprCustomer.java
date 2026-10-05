package io.vanillabp.camunda7.quarkus.expressions;

/**
 * The customer of an order. It has a name and a flag, and deliberately no address: an
 * expression reading one is the case where a path stops at a name the type does not have.
 */
public class ExprCustomer {

  private final boolean vip;

  ExprCustomer(
      final boolean vip) {

    this.vip = vip;

  }

  public String getName() {

    return "a customer";

  }

  public boolean isVip() {

    return vip;

  }

}
