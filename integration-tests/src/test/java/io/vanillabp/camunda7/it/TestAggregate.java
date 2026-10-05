package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate persisted in the same H2 database (and the same transaction) as
 * the embedded Camunda 7 engine. The generated (non-string) ID is used as the Camunda
 * business key of the started workflow.
 */
@Entity
@Table(name = "C7_TEST_AGGREGATE")
@Getter
@Setter
public class TestAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

}
