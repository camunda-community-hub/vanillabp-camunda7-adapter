package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The JPA workflow aggregate of the timer-start integration test. Its ID is a String and
 * no <code>@GeneratedValue</code> is involved, which is what lets the
 * <code>@WorkflowStartedByBpms</code> method name the workflow itself.
 */
@Entity
@Table(name = "C7_TIMER_START_AGGREGATE")
@Getter
@Setter
public class TimerStartTestAggregate {

  @Id
  private String id;

  private String processedBy;

  /**
   * Which kind of start event began this workflow, as the trigger reported it.
   */
  private String startedBy;

  /**
   * Set by the <code>@WorkflowEnded</code> method.
   */
  private String endedAs;

}
