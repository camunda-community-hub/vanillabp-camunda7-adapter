package io.vanillabp.camunda7.quarkus.expressions;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate the expressions of {@code ExprCheckProcess} read through. Nothing
 * of it is ever started: what this aggregate exists for is the shape its attributes give the
 * startup check.
 */
@Entity
@Table(name = "C7_EXPR_AGGREGATE")
@Getter
@Setter
public class ExprAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private boolean vip;

  /**
   * The nested order, shared with the engine.
   */
  @Transient
  public ExprOrder getOrder() {

    return new ExprOrder(vip);

  }

  /**
   * An order this aggregate keeps to itself.
   */
  @NoSyncWithBPMS
  @Transient
  public ExprHiddenOrder getHiddenOrder() {

    return new ExprHiddenOrder();

  }

}
