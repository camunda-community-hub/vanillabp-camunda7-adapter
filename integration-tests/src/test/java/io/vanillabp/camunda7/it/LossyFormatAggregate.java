package io.vanillabp.camunda7.it;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

/**
 * The workflow aggregate of the round-trip check: it shares a decimal at both levels a
 * value can travel at, and the model of this scenario reads both of them.
 * <p>
 * The values are never pushed anywhere. This scenario is about what the BOOT says, so
 * nothing here has to run - what the engine makes of these values at runtime is measured
 * by {@code AbstractNestedExpressionsIT} against the same types.
 */
@Entity
@Table(name = "C7_LOSSY_FORMAT_AGGREGATE")
public class LossyFormatAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private BigDecimal total;

  private String reference;

  public Long getId() {

    return id;

  }

  /**
   * A decimal at the top level: an object variable of its own, and what an expression
   * renders depends on the format it was written in.
   */
  public BigDecimal getTotal() {

    return total;

  }

  /**
   * The same decimal one level down, where a format loses the type rather than a digit.
   */
  @Transient
  public LossyFormatOrder getOrder() {

    return new LossyFormatOrder(total);

  }

  /**
   * A value the engine has a variable type for, which the check has to stay quiet about.
   */
  public String getReference() {

    return reference;

  }

}
