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
 * {@link #handlerRuns} counts what the handler wrote INTO THE AGGREGATE, which is the
 * half a rolled-back delivery takes with it - the handler's own invocations are counted
 * by {@link RepeatedDeliveryProbe}, which no rollback reaches.
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
