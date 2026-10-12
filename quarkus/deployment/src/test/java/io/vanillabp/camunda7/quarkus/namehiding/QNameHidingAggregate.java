package io.vanillabp.camunda7.quarkus.namehiding;

import io.vanillabp.spi.service.NoSyncWithBPMS;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the model whose names are NOT hidden. Two of its attributes
 * carry a name the model uses. Neither of them takes a task away from its method.
 */
@Entity
@Table(name = "C7_QNAME_HIDING_AGGREGATE")
@Getter
@Setter
public class QNameHidingAggregate {

  @Id
  private String id;

  /**
   * Named like the task definition, but kept away from the engine. So no process variable
   * of this name exists, and the expression reaches the method.
   */
  @NoSyncWithBPMS
  private String chargeCard;

  /**
   * Named like the element id of the task which a method serves by that id. It is shared,
   * and it does not matter, because an element id is never an EL name.
   */
  private String reviewOrder;

  /**
   * What the method of the task definition 'chargeCard' wrote.
   */
  private boolean charged;

  /**
   * What the method of the element 'reviewOrder' wrote.
   */
  private boolean reviewed;

}
