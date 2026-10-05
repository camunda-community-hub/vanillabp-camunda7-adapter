package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * The aggregate of the BPMN process <code>ExprConditions</code>, which puts one expression
 * per outcome class on an exclusive gateway, each on a parallel branch of its own.
 * <p>
 * One aggregate class per process: a ProcessService starts the process its workflow
 * service names, so every process measured here brings one. Everything they carry
 * is in {@link ExprData}.
 */
@Entity
@Table(name = "C7_EXPR_CONDITIONS_AGGREGATE")
public class ExprConditionsAggregate extends ExprData {

  /**
   * An id range of its own - the Camunda business key is the aggregate's id.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7ExprConditionsSeq")
  @SequenceGenerator(name = "c7ExprConditionsSeq", initialValue = 710000, allocationSize = 1)
  private Long id;

  public Long getId() {

    return id;

  }

}
