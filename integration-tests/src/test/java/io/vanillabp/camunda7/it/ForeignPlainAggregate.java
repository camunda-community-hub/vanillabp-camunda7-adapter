package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The JPA workflow aggregate of the process which starts with a plain start event. Its
 * workflow service has no <code>@WorkflowStartedByBpms</code> method, which is legal: the
 * application starts this process itself. Somebody starting it past VanillaBP is refused.
 */
@Entity
@Table(name = "C7_FOREIGN_PLAIN_AGGREGATE")
@Getter
@Setter
public class ForeignPlainAggregate {

  @Id
  private String id;

  private String processedBy;

}
