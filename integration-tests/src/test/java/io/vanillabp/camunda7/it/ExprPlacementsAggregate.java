package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * The aggregate of the BPMN process <code>ExprPlacements</code>. The process runs a chosen
 * subset through the other places where a Camunda 7 model puts an expression.
 * <p>
 * There is one aggregate class per process. A ProcessService starts the process which its
 * workflow service names, so every process measured here brings its own class. Everything
 * they carry is in {@link ExprData}.
 */
@Entity
@Table(name = "C7_EXPR_PLACEMENTS_AGGREGATE")
public class ExprPlacementsAggregate extends ExprData {

  /**
   * An id range of its own, because the Camunda business key is the aggregate's id.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7ExprPlacementsSeq")
  @SequenceGenerator(name = "c7ExprPlacementsSeq", initialValue = 720000, allocationSize = 1)
  private Long id;

  public Long getId() {

    return id;

  }

}
