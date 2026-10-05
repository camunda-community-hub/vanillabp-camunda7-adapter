package io.vanillabp.camunda7.it;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import io.vanillabp.spi.service.NoSyncWithBPMS;

/**
 * The nested value these tests navigate. It is a plain object built on demand from
 * the flat columns of {@link ExprData}, so the object graph the version-1 EL resolver
 * would have navigated exists without JPA having to model it.
 * <p>
 * One attribute carries {@code @NoSyncWithBPMS}, which makes this class opt-out: the sync
 * model shares everything else and leaves out exactly that one - the missing key an
 * expression meets without any error.
 */
public class ExprOrder {

  private final ExprData data;

  ExprOrder(
      final ExprData data) {

    this.data = data;

  }

  public String getReference() {

    return data.reference;

  }

  public boolean isExpress() {

    return data.express;

  }

  public BigDecimal getTotal() {

    return data.orderTotal;

  }

  public ExprStatus getStatus() {

    return data.orderStatus;

  }

  public LocalDate getDueDate() {

    return data.orderDueDate;

  }

  public LocalDateTime getDueAt() {

    return data.orderDueAt;

  }

  public ExprCustomer getCustomer() {

    return new ExprCustomer(data);

  }

  public List<ExprItem> getItems() {

    return data.itemsOf();

  }

  /**
   * How many items the order has - a method the version-1 grammar could call on the live
   * object and which no map has.
   */
  public int getItemCount() {

    return getItems().size();

  }

  /**
   * The attribute the sync model does NOT share: an expression reading
   * <code>order.internalCode</code> looks up a key the map has not got.
   */
  @NoSyncWithBPMS
  public String getInternalCode() {

    return data.internalCode;

  }

}
