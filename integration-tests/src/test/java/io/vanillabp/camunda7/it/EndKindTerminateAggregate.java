package io.vanillabp.camunda7.it;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the workflow which ends at a terminate end event. What the test reads
 * is {@link #endedAs}, written by the <code>&#64;WorkflowEnded</code> method.
 */
@Entity
@Table(name = "C7_END_KIND_TERMINATE")
@Getter
@Setter
public class EndKindTerminateAggregate {

  /**
   * An id range of its own: the Camunda business key is the aggregate's id, so the
   * id spaces of the test aggregates must not overlap.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7EndKindTerminateSeq")
  @SequenceGenerator(name = "c7EndKindTerminateSeq", initialValue = 960000, allocationSize = 1)
  private Long id;

  /**
   * The kind and the end event id the application was told, as one string. The engine
   * never reads it: it is written once the workflow is over.
   */
  @NoSyncWithBPMS
  private String endedAs;

}
