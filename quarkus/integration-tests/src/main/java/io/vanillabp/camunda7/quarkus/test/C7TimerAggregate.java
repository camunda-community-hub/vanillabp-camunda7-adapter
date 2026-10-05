package io.vanillabp.camunda7.quarkus.test;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the workflow the ENGINE starts on its own. Its id is a String
 * and no <code>&#64;GeneratedValue</code> is involved, which is what lets the
 * <code>&#64;WorkflowStartedByBpms</code> method name the workflow itself.
 */
@Entity
@Table(name = "C7_E2E_TIMER_AGGREGATE")
@Getter
@Setter
public class C7TimerAggregate {

  @Id
  private String id;

  private String processedBy;

  /**
   * Which kind of start event began this workflow, as the trigger reported it.
   */
  private String startedBy;

  /**
   * Set by the <code>&#64;WorkflowEnded</code> method.
   */
  private String endedAs;

}
