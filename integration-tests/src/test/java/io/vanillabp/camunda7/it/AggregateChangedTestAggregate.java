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
 * The aggregate of the aggregateChanged integration test. It annotates nothing, so all of
 * it travels to the engine, and the test application allows that (see "Sharing the workflow
 * aggregate" in the README). The test covers what matters on an embedded engine. The engine
 * looks at the condition of a conditional event when a variable in the scope of that event
 * changes. And the push is what changes one.
 */
@Entity
@Table(name = "C7_AGGREGATE_CHANGED_TEST")
@Getter
@Setter
public class AggregateChangedTestAggregate {

  /**
   * An id range of its own. The Camunda business key is the aggregate's id, so the
   * id spaces of the test aggregates must not overlap.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7AggregateChangedSeq")
  @SequenceGenerator(name = "c7AggregateChangedSeq", initialValue = 700000, allocationSize = 1)
  private Long id;

  /**
   * What the conditional event waits for.
   */
  private boolean readyToGo;

  private String processedBy;

}
