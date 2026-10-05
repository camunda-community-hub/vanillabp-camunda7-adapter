package io.vanillabp.camunda7.it;

import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the decision test: a task computes what the gateway right behind
 * it reads, so the value has to be a process variable by the time the engine evaluates
 * that gateway. It also carries a NESTED shared value, which becomes an object variable in
 * the configured serialization format.
 */
@Entity
@Table(name = "C7_DECISION_TEST_AGGREGATE")
@Getter
@Setter
public class DecisionTestAggregate {

  /**
   * A high id range of its own - the Camunda business key is the aggregate's id, and the
   * id spaces of the test aggregates must not overlap.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7DecisionTestSeq")
  @SequenceGenerator(name = "c7DecisionTestSeq", initialValue = 950000, allocationSize = 1)
  private Long id;

  /**
   * What the <code>decideTask</code> handler computes and the gateway behind it reads.
   */
  private boolean decided;

  /**
   * Which branch the workflow took - recorded by the handlers.
   */
  private String decisionResult;

  private String taskId;

  /**
   * A nested shared value: an object variable in the configured format.
   */
  @Embedded
  private DecisionTestCustomer customer;

}
