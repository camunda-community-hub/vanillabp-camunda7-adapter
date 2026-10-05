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
 * The aggregate of the old-process-versions integration test.
 */
@Entity
@Table(name = "C7_OLD_PROCESS_VERSIONS_TEST")
@Getter
@Setter
public class OldProcessVersionsAggregate {

  /**
   * An id range of its own: the Camunda business key is the aggregate's id, so the
   * id spaces of the test aggregates must not overlap.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7OldProcessVersionsSeq")
  @SequenceGenerator(name = "c7OldProcessVersionsSeq", initialValue = 900000, allocationSize = 1)
  private Long id;

  /**
   * Which method served the task which survived into version 2.
   */
  private String servedBy;

  /**
   * The id of the task which exists in version 1 only - it stays open, which keeps a
   * workflow of version 1 running while the next boot deploys version 2.
   */
  private String openTaskId;

}
