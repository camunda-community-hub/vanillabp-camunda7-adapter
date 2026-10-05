package io.vanillabp.camunda7.quarkus.lossyformat;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the lossy-format check. It shares the same decimal twice: once at
 * the top, where the engine has a variable type of its own for it, and once inside an order,
 * where the configured format decides what comes back.
 */
@Entity
@Table(name = "C7_LOSSY_AGGREGATE")
@Getter
@Setter
public class LossyAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private BigDecimal total;

  private String reference;

  @Transient
  public LossyOrder getOrder() {

    return new LossyOrder(total);

  }

}
