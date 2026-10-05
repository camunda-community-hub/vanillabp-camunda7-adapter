package io.vanillabp.camunda7.quarkus.task;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * JPA workflow aggregate of the task-processing test - persisted in
 * the same H2 database and the same JTA transaction as the embedded engine.
 */
@Entity
@Table(name = "C7_QTASK_AGGREGATE")
@Getter
@Setter
public class QTaskAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String results;

  private String taskId;

  public void appendResult(
      final String result) {

    results = results == null
        ? result
        : results
            + "|"
            + result;

  }

}
