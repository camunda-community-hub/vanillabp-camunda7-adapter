package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The JPA workflow aggregate of the declared-id runtime integration test. Its ID is a
 * String and carries no <code>@GeneratedValue</code>: the
 * <code>@WorkflowStartedByBpms</code> method names the workflow, and it puts its own
 * generation into that name. That is how the test tells the workflows of the two
 * generations apart.
 */
@Entity
@Table(name = "C7_DECLARED_RUNTIME_AGGREGATE")
@Getter
@Setter
public class DeclaredRuntimeAggregate {

  @Id
  private String id;

  /**
   * Set by the <code>@WorkflowStartedByBpms</code> method: the trigger's kind.
   */
  private String startedAs;

  /**
   * Set by the <code>@WorkflowStartedByBpms</code> method: the PLAIN signal name for
   * a signal start, <code>null</code> otherwise.
   */
  private String signalName;

  /**
   * Set by the <code>@WorkflowTask</code> method following the start event.
   */
  private String processedBy;

  /**
   * Set by the <code>@WorkflowEnded</code> method.
   */
  private String endedAs;

}
