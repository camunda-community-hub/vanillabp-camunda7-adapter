package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;

/**
 * The aggregate of the BPMN process <code>ExprEventSub</code>. The process carries the
 * conditional start event of an event subprocess. It is measured on its own, because the
 * engine evaluates that condition on every change of a variable in its scope.
 * <p>
 * There is one aggregate class per process. A ProcessService starts the process which its
 * workflow service names, so every process measured here brings its own class. Everything
 * they carry is in {@link ExprData}.
 */
@Entity
@Table(name = "C7_EXPR_EVENTSUB_AGGREGATE")
public class ExprEventSubAggregate extends ExprData {

  /**
   * An id range of its own, because the Camunda business key is the aggregate's id.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7ExprEventSubSeq")
  @SequenceGenerator(name = "c7ExprEventSubSeq", initialValue = 730000, allocationSize = 1)
  private Long id;

  public Long getId() {

    return id;

  }

}
