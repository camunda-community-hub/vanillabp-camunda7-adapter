package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A workflow aggregate which shares an attribute named like a task definition of its
 * model. It shares everything, which is what an aggregate without annotations does.
 */
@Entity
@Table(name = "C7_SHARED_NAME_AGGREGATE")
@Getter
@Setter
public class SharedNameAggregate {

  @Id
  private String id;

  /**
   * Shared with the engine as the process variable 'chargeCard', which is also the task
   * definition of the model.
   */
  private String chargeCard;

}
