package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * The aggregate of the BPMN process <code>ExprConditions</code>. The process puts one
 * expression per outcome class on an exclusive gateway. Each gateway sits on a parallel
 * branch of its own.
 * <p>
 * There is one aggregate class per process. A ProcessService starts the process which its
 * workflow service names, so every process measured here brings its own class. Everything
 * they carry is in {@link ExprData}.
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
