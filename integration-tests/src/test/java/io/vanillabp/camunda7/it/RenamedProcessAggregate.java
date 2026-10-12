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
 * The aggregate of the renamed-process integration test. It outlives the application which
 * started its workflow. The second boot of that test reads it from the same database. A
 * workflow which runs across an upgrade needs exactly that.
 */
@Entity
@Table(name = "C7_RENAMED_PROCESS_TEST")
@Getter
@Setter
public class RenamedProcessAggregate {

  /**
   * An id range of its own: the Camunda business key is the aggregate's id, so the id
   * spaces of the test aggregates must not overlap.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7RenamedProcessSeq")
  @SequenceGenerator(name = "c7RenamedProcessSeq", initialValue = 990000, allocationSize = 1)
  private Long id;

  /**
   * Which generation of the application served the first task.
   */
  private String startedBy;

  /**
   * Which generation of the application served the last task, the one wired by
   * <code>camunda:delegateExpression</code>.
   */
  private String finishedBy;

  /**
   * The id of that last task while it is open. The test completes it. That is only possible
   * if the connectable which the engine resolved carries the type which the OLD model wired
   * the task with.
   */
  private String openTaskId;

}
