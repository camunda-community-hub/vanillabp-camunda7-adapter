package io.vanillabp.camunda7.quarkus.expressions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import io.vanillabp.spi.service.NoSyncWithBPMS;

/**
 * The order an expression reads through. What each of its attributes is here for:
 * <ul>
 * <li>{@code total}, {@code dueDate}, {@code customer} and {@code items} are shared, so an
 * expression reading them says nothing;</li>
 * <li>{@code dueDate} is a {@code LocalDate}, which carries nothing below it - a path
 * reading {@code dueDate.year} stops there;</li>
 * <li>{@code internalCode} is kept back, so an expression reading it reads a value the
 * engine never gets;</li>
 * <li>there is no {@code hiddenItems}, which is what a collection that does not resolve
 * looks like.</li>
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
