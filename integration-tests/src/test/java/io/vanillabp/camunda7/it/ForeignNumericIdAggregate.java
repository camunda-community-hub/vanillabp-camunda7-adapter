package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The JPA workflow aggregate of the process of the foreign-start integration test whose id
 * is a NUMBER the persistence layer assigns. A business key somebody chose is a text, so
 * it can never be the id of this aggregate, and that is the case the adapter refuses
 * instead of renaming the workflow.
 */
@Entity
@Table(name = "C7_FOREIGN_NUMERIC_ID_AGGREGATE")
@Getter
@Setter
public class ForeignNumericIdAggregate {

  @Id
  @GeneratedValue
  private Long id;

  private String processedBy;

}
