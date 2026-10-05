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
 * The aggregate of the process-version integration test.
 */
@Entity
@Table(name = "C7_VERSIONED_TEST")
@Getter
@Setter
public class VersionedTestAggregate {

  /**
   * An id range of its own: the Camunda business key is the aggregate's id, so the
   * id spaces of the test aggregates must not overlap.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7VersionedSeq")
  @SequenceGenerator(name = "c7VersionedSeq", initialValue = 800000, allocationSize = 1)
  private Long id;

  /**
   * Which <code>&#64;WorkflowTask</code> method served the task - the version of the
   * deployed process definition decides it.
   */
  private String servedBy;

}
