package io.vanillabp.camunda7.quarkus.listeners;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the modelled-listener test. Each flag is set by one
 * <code>&#64;WorkflowTask</code> method, so which of them are true says which of the two
 * listener forms reached the application.
 */
@Entity
@Table(name = "C7_LISTENER_AGGREGATE")
@Getter
@Setter
public class ListenerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private boolean theWorkWasDone;

  /**
   * Set by the listener written as a <code>camunda:expression</code>.
   */
  private boolean theEndWasReached;

  /**
   * Set by the listener written as a <code>camunda:delegateExpression</code>.
   */
  private boolean theEndWasDone;

}
