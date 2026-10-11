package io.vanillabp.camunda7.quarkus.expressions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.vanillabp.spi.service.NoSyncWithBPMS;

/**
 * The order an expression reads through. What each of its attributes is here for:
 * <ul>
 * <li>{@code total}, {@code dueDate}, {@code customer} and {@code items} are shared. So the
 * check says nothing about an expression which reads them.</li>
 * <li>{@code dueDate} is a {@code LocalDate}, which carries nothing below it. A path which
 * reads {@code dueDate.year} stops there.</li>
 * <li>{@code internalCode} is kept back. So an expression which reads it reads a value the
 * engine never gets.</li>
 * <li>there is no {@code hiddenItems}. That is what a collection looks like which does not
 * resolve.</li>
 * </ul>
 */
public class ExprOrder {

  private final boolean vip;

  ExprOrder(
      final boolean vip) {

    this.vip = vip;

  }

  public BigDecimal getTotal() {

    return new BigDecimal("120.50");

  }

  public LocalDate getDueDate() {

    return LocalDate.of(2026, 9, 14);

  }

  public ExprCustomer getCustomer() {

    return new ExprCustomer(vip);

  }

  public List<ExprItem> getItems() {

    return List.of(new ExprItem("first"), new ExprItem("second"));

  }

  @NoSyncWithBPMS
  public String getInternalCode() {

    return "IC-9";

  }

}
