package io.vanillabp.camunda7.quarkus.test;

import java.util.List;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the Quarkus end-to-end application - a JPA entity on H2,
 * because the embedded Camunda 7 engine shares its datasource and its JTA
 * transaction, and that sharing is what the rollback assertions are about.
 * <p>
 * BPMN expressions reach its attributes through VanillaBP's EL resolver:
 * <code>${approved}</code> is the gateway condition of {@code TaskProcess},
 * <code>${items}</code> the collection of the multi-instance activity.
 */
@Entity
@Table(name = "C7_E2E_AGGREGATE")
@Getter
@Setter
public class C7E2eAggregate {

  /**
   * An id range of its own: the Camunda business key is the aggregate's id, so the id
   * spaces of the application's aggregates must not overlap.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7E2eSeq")
  @SequenceGenerator(name = "c7E2eSeq", initialValue = 100000, allocationSize = 1)
  private Long id;

  /**
   * What the exclusive gateway of {@code TaskProcess} branches on.
   */
  private boolean approved;

  /**
   * What the handlers record, appended in the order they ran.
   */
  private String results;

  /**
   * The parked execution an asynchronous task or a user task left behind.
   */
  private String taskId;

  /**
   * The collection of the multi-instance activity.
   */
  @ElementCollection(fetch = FetchType.EAGER)
  @OrderColumn
  private List<String> items;

  /**
   * Appends one result entry.
   *
   * @param result What the handler wants to record
   */
  public void appendResult(
      final String result) {

    results = results == null
        ? result
        : results
            + "|"
            + result;

  }

}
