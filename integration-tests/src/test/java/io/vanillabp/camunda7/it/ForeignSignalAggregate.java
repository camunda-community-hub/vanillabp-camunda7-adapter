package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The JPA workflow aggregate of the signal-started process of the foreign-start
 * integration test. Its id is a String, and the application's
 * <code>@WorkflowStartedByBpms</code> method puts it there: a broadcast brings no name,
 * so the method names the workflow.
 */
@Entity
@Table(name = "C7_FOREIGN_SIGNAL_AGGREGATE")
@Getter
@Setter
public class ForeignSignalAggregate {

  @Id
  private String id;

  private String processedBy;

}
