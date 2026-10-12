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
 * The aggregate of the caller and of the called process of the test about the iteration
 * which a process called by an expression runs in. One class serves both, and that makes
 * them one business case.
 */
@Entity
@Table(name = "C7_MI_CALL_EXPR")
@Getter
@Setter
public class MiCallExprAggregate {

  /**
   * An id range of its own. The Camunda business key is the aggregate's id. With overlapping
   * id spaces, a business-key query of another test could match the workflows of this test.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7MiCallExprSeq")
  @SequenceGenerator(name = "c7MiCallExprSeq", initialValue = 930000, allocationSize = 1)
  private Long id;

  /**
   * What the expression of the first call activity reads, which is the id of the process to
   * call.
   */
  @io.vanillabp.spi.service.SyncWithBPMS
  private String processToCall;

  /**
   * What every task of the called process reported about its iteration. The entries stand
   * in the order the tasks ran, separated by commas. A list attribute would need a table of
   * its own for what one string does here.
   */
  private String reported;

  /**
   * The collection of the outer multi-instance subprocess, read live by the engine.
   *
   * @return The groups
   */
  @Transient
  public List<String> getGroups() {

    return List.of("c1", "c2");

  }

  /**
   * The collection of the multi-instance call activity inside that subprocess.
   *
   * @return The positions
   */
  @Transient
  public List<String> getPositions() {

    return List.of("p1", "p2");

  }

  /**
   * The collection of the subprocess whose call activity names the called process by its
   * id. It has one round, because this half is the comparison and not the measurement.
   *
   * @return The rounds
   */
  @Transient
  public List<String> getRounds() {

    return List.of("s1");

  }

}
