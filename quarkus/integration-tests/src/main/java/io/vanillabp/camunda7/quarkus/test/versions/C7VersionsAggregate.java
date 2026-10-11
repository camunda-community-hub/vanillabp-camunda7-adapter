package io.vanillabp.camunda7.quarkus.test.versions;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the old-process-versions test.
 */
@Entity
@Table(name = "C7_VERSIONS_AGGREGATE")
@Getter
@Setter
public class C7VersionsAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * Which method served the task which survived into the new model.
   */
  private String servedBy;

  /**
   * The id of the task which exists in the old model only. It stays open, which keeps a
   * workflow of that version running while the application boots again.
   */
  private String openTaskId;

}
