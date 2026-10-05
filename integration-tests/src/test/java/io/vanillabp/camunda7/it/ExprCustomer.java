package io.vanillabp.camunda7.it;

import java.time.LocalDateTime;

/**
 * The second level of the nested path: an expression navigating
 * <code>order.customer.name</code> reaches a value which is two maps deep once the sync
 * model flattened it.
 */
public class ExprCustomer {

  private final ExprData data;

  ExprCustomer(
      final ExprData data) {

    this.data = data;

  }

  public String getName() {

    return data.customerName;

  }

  public boolean isVip() {

    return data.vip;

  }

  public LocalDateTime getSince() {

    return data.customerSince;

  }

}
