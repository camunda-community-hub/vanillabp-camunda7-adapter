package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the orphan-method test. It has one of its own because a
 * workflow aggregate belongs to exactly one workflow service, and reusing another test's
 * aggregate ends the boot with that message instead of the one under test.
 */
@Entity
@Table(name = "C7_ORPHAN_METHOD_AGGREGATE")
@Getter
@Setter
public class OrphanMethodAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

}
