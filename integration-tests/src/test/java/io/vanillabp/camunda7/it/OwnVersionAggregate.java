package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the test about which version's methods a workflow reaches. Each field
 * says which generation of the methods served one element, "old" for the methods version 1
 * names and "new" for the ones version 2 names.
 */
@Entity
@Table(name = "C7_OWN_VERSION_TEST")
@Getter
@Setter
public class OwnVersionAggregate {

  /**
   * An id range of its own: the Camunda business key is the aggregate's id, so the id spaces
   * of the test aggregates must not overlap.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7OwnVersionSeq")
  @SequenceGenerator(name = "c7OwnVersionSeq", initialValue = 980000, allocationSize = 1)
  private Long id;

  /** Who served the task wired by 'camunda:expression'. */
  private String expressionServedBy;

  /** Who served the listener written as 'camunda:expression'. */
  private String listenerServedBy;

  /** Who served the task wired by 'camunda:delegateExpression'. */
  private String delegateServedBy;

}
