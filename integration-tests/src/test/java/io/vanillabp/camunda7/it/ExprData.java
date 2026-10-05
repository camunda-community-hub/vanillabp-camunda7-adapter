package io.vanillabp.camunda7.it;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;

/**
 * The data every aggregate of these expression tests carries. The state is stored FLAT,
 * in columns of the entity, and the nested object graph the expressions navigate is built
 * from it on demand ({@link #getOrder()}). That keeps the graph independent of what JPA
 * can map, and it survives a reload - which the migration fallback of the EL resolver
 * needs, because it loads the aggregate by its business key.
 * <p>
 * What is shared and what is not: the only annotations on this class are
 * {@code @NoSyncWithBPMS}, so the sync model treats it as opt-out - everything is shared
 * except {@link #getHiddenOrder()}, which is the name the EL resolver still answers from
 * the LIVE object because the engine holds no variable of it.
 * <p>
 * The flat state has no public getters on purpose. The sync model reads readable
 * JavaBean properties, so a private field without a getter is invisible to it and the
 * variables the engine gets are exactly the ones these tests are about.
 * <p>
 * Dates, a decimal, an enum and a list are among them, and none of those means the same
 * in every expression language. The four processes of these tests therefore declare
 * every value they share ({@code declared-aggregate-values: '*'} in the test
 * application's configuration), because what is measured here is where an expression may
 * stand and what it reads, not what a type does on its way to the engine.
 */
@MappedSuperclass
public abstract class ExprData {

  String reference;

  boolean express;

  BigDecimal orderTotal;

  @Enumerated(EnumType.STRING)
  ExprStatus orderStatus;

  LocalDate orderDueDate;

  LocalDateTime orderDueAt;

  String customerName;

  boolean vip;

  LocalDateTime customerSince;

  String internalCode;

  /**
   * The items as "name:price" pairs separated by commas - a collection without a
   * collection table.
   */
  String itemsCsv;

  BigDecimal total;

  @Enumerated(EnumType.STRING)
  ExprStatus topStatus;

  LocalDate dueDate;

  LocalDateTime dueAt;

  /**
   * The nested value the expressions navigate.
   */
  @Transient
  public ExprOrder getOrder() {

    return new ExprOrder(this);

  }

  /**
   * A top-level {@link BigDecimal}: an object variable of its own, while the same value
   * inside {@link #getOrder()} travels as a member of the flattened order. Both reach the
   * serializer, and what each of them comes back as is what the two worlds measure.
   */
  public BigDecimal getTotal() {

    return total;

  }

  /**
   * A top-level enum: the sync model shares its name.
   */
  public ExprStatus getTopStatus() {

    return topStatus;

  }

  public LocalDate getDueDate() {

    return dueDate;

  }

  public LocalDateTime getDueAt() {

    return dueAt;

  }

  /**
   * The same object graph under a name the aggregate does NOT share. The engine has no
   * variable of it, so the migration fallback of {@code Camunda7TaskELResolver} answers
   * it from the live aggregate - which is what keeps the whole version-1 grammar alive
   * until the fallback is removed.
   */
  @Transient
  @NoSyncWithBPMS
  public ExprOrder getHiddenOrder() {

    return getOrder();

  }

  List<ExprItem> itemsOf() {

    final var items = new ArrayList<ExprItem>();
    if ((itemsCsv == null) || itemsCsv.isBlank()) {
      return items;
    }
    for (final var pair : itemsCsv.split(",")) {
      final var parts = pair.split(":");
      items.add(new ExprItem(parts[0], new BigDecimal(parts[1])));
    }
    return items;

  }

  /**
   * The sample every case of these tests reads.
   */
  public void fillWithTheSample() {

    reference = "REF-1";
    express = true;
    orderTotal = new BigDecimal("120.50");
    orderStatus = ExprStatus.OPEN;
    orderDueDate = LocalDate.of(2027, 3, 4);
    orderDueAt = LocalDateTime.of(2027, 3, 4, 5, 6, 7);
    customerName = "ACME";
    vip = true;
    customerSince = LocalDateTime.of(2020, 1, 2, 3, 4, 5);
    internalCode = "IC-9";
    itemsCsv = "Widget:25.00,Gadget:5.00,Gizmo:15.00";
    total = new BigDecimal("120.50");
    topStatus = ExprStatus.OPEN;
    dueDate = LocalDate.of(2027, 3, 4);
    dueAt = LocalDateTime.of(2027, 3, 4, 5, 6, 7);

  }

}
