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
 * The aggregate of the multi-instance half of the aggregateChanged integration test. The
 * workflow parks in every iteration of a multi-instance embedded subprocess. So the test
 * can push into the scope of ONE iteration and see that only the event subprocess of that
 * iteration reacts.
 */
@Entity
@Table(name = "C7_MI_PUSH_TEST")
@Getter
@Setter
public class MultiInstancePushTestAggregate {

  /**
   * An id range of its own - the Camunda business key is the aggregate's id.
   */
  @Id
  @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "c7MiPushSeq")
  @SequenceGenerator(name = "c7MiPushSeq", initialValue = 710000, allocationSize = 1)
  private Long id;

  /**
   * The parked executions of the iterations as "item=taskId", separated by commas. A list
   * attribute would need a table of its own for what two strings do here.
   */
  private String taskIds;

  /**
   * What the conditional start event of the event subprocess waits for. Not shared
   * with the BPMS: Camunda 7 reads the aggregate live, so the condition sees it
   * either way.
   */
  private boolean escalate;

  /**
   * The items whose iteration reached the event subprocess, separated by commas.
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

}
