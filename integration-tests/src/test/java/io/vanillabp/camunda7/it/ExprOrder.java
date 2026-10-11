package io.vanillabp.camunda7.it;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import io.vanillabp.spi.service.NoSyncWithBPMS;

/**
 * The nested value which these tests navigate. It is a plain object, built on demand from
 * the flat columns of {@link ExprData}. So the object graph which the version-1 EL resolver
 * would have navigated exists, without JPA having to model it.
 * <p>
 * One attribute carries {@code @NoSyncWithBPMS}, which makes this class opt-out. The sync
 * model shares everything else and leaves out exactly that one. It is the missing key which
 * an expression meets without any error.
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
   * How many items the order has. The version-1 grammar could call this method on the live
   * object, and no map has it.
   */
  public int getItemCount() {

    return getItems().size();

  }

  /**
   * The attribute which the sync model does NOT share. An expression which reads
   * <code>order.internalCode</code> looks up a key the map does not have.
   */
  @NoSyncWithBPMS
  public String getInternalCode() {

    return data.internalCode;

  }

}
