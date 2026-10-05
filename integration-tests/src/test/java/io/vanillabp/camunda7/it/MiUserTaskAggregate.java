package io.vanillabp.camunda7.it;

import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the test about the levels a user task is told about.
 */
@Entity
@Table(name = "C7_MI_USER_TASK")
@Getter
@Setter
public class MiUserTaskAggregate {

  /**
   * An id range of its own: the Camunda business key is the aggregate's id, so overlapping
   * id spaces would let another test's business-key query match this test's workflows.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7MiUserTaskSeq")
  @SequenceGenerator(name = "c7MiUserTaskSeq", initialValue = 940000, allocationSize = 1)
  private Long id;

  /**
   * What every notification about a user task reported about its iteration, in the order
   * the notifications arrived, comma-separated. A list attribute would need a table of its
   * own for what one string does here.
   */
  private String reported;

  /**
   * The collection of the multi-instance subprocess, read live by the engine.
   *
   * @return The groups
   */
  @Transient
  public List<String> getGroups() {

    return List.of("c1", "c2");

  }

  /**
   * The collection of the multi-instance user task inside that subprocess.
   *
   * @return The positions
   */
  @Transient
  public List<String> getPositions() {

    return List.of("p1", "p2");

  }

}
