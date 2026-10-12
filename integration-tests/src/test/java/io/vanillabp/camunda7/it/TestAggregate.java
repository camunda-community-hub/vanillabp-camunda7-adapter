package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The JPA workflow aggregate. It is saved in the same H2 database as the embedded Camunda 7
 * engine, and in the same transaction. The generated ID is not a string. It becomes the
 * Camunda business key of the started workflow.
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
