package io.vanillabp.camunda7.it;

import java.util.List;

import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The JPA workflow aggregate of the task-processing integration tests.
 * BPMN expressions reference its attributes through VanillaBP's EL resolver:
 * {@code ${approved}} (gateway condition, via {@link #isApproved()}) and
 * {@code ${items}} (multi-instance collection, via {@link #getItems()}).
 */
@Entity
@Table(name = "C7_TASK_TEST_AGGREGATE")
@Getter
@Setter
public class TaskTestAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private boolean approved;

  private String results;

  private String taskId;

  @ElementCollection(fetch = FetchType.EAGER)
  @OrderColumn
  private List<String> items;

  /**
   * Appends one result entry (the handlers record their execution here).
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
