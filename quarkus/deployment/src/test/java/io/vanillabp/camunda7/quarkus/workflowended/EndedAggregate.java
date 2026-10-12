package io.vanillabp.camunda7.quarkus.workflowended;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the workflow-ended test. The test looks only at what the notification
 * wrote.
 */
@Entity
@Table(name = "C7_ENDED_AGGREGATE")
@Getter
@Setter
public class EndedAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String content;

  private String endedWith;

}
