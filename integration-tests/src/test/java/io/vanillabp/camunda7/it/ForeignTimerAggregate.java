package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The JPA workflow aggregate of the timer-started process of the foreign-start integration
 * test. Its id is a String. So a business key which somebody else chose has the shape of an
 * id here. The test can show that it is refused all the same.
 */
@Entity
@Table(name = "C7_FOREIGN_TIMER_AGGREGATE")
@Getter
@Setter
public class ForeignTimerAggregate {

  @Id
  private String id;

  private String processedBy;

  /**
   * Set by the application before it starts the workflow. Suppose VanillaBP mistook this
   * start for a foreign one. Then it would ask the application for a second aggregate and
   * save it over this one, and the value would be gone.
   */
  private String startedBy;

}
