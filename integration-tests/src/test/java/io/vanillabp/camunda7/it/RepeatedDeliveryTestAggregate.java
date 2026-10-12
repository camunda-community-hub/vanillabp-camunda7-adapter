package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The JPA workflow aggregate of the repeated-delivery integration test. Its
 * {@link #handlerRuns} counts what the handler wrote INTO THE AGGREGATE. A rolled-back delivery
 * takes this half with it. {@link RepeatedDeliveryProbe} counts the calls of the handler
 * itself, and no rollback reaches it.
 */
@Entity
@Table(name = "C7_REPEATED_DELIVERY_AGGREGATE")
@Getter
@Setter
public class RepeatedDeliveryTestAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private int handlerRuns;

}
