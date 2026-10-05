package io.vanillabp.camunda7.quarkus.test;

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
 * The workflow aggregate of pushing a changed aggregate. It annotates nothing, so all of
 * it travels to the engine and the test application allows that (see "Sharing the workflow
 * aggregate" in the README). The engine looks at the condition of a conditional event when
 * a variable of that event's scope changes, and the push is what changes one.
 */
@Entity
@Table(name = "C7_E2E_PUSH_AGGREGATE")
@Getter
@Setter
public class C7PushAggregate {

  /**
   * An id range of its own: the Camunda business key is the aggregate's id, so the id
   * spaces of the application's aggregates must not overlap.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7E2ePushSeq")
  @SequenceGenerator(name = "c7E2ePushSeq", initialValue = 700000, allocationSize = 1)
  private Long id;

  /**
   * What the conditional event of {@code AggregateChangedProcess} waits for.
   */
  private boolean readyToGo;

  /**
   * What the conditional start event of the event subprocess waits for.
   */
  private boolean escalate;

  private String processedBy;

  /**
   * The parked executions as "item=taskId", comma-separated - a list attribute would
   * need a table of its own for what two strings do here.
   */
  private String taskIds;

  /**
   * The items whose iteration reached the event subprocess, comma-separated.
   */
  private String escalatedItems;

  /**
   * The collection of the multi-instance activity, read live by the engine.
   *
   * @return The items
   */
  @Transient
  public List<String> getItems() {

    return List.of("first", "second");

  }

  /**
   * Records one parked execution.
   *
   * @param entry What to record
   */
  public void appendTaskId(
      final String entry) {

    taskIds = taskIds == null
        ? entry
        : taskIds
            + ","
            + entry;

  }

  /**
   * Records one iteration which reached the event subprocess.
   *
   * @param item The iteration's element
   */
  public void appendEscalatedItem(
      final String item) {

    escalatedItems = escalatedItems == null
        ? item
        : escalatedItems
            + ","
            + item;

  }

}
