package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The workflow aggregate of the test about methods wired by the BPMN element id. It has one
 * of its own, because a workflow aggregate belongs to exactly one workflow service. With
 * the aggregate of another test, the boot would end with that message instead of running
 * the model under test.
 */
@Entity
@Table(name = "C7_WIRED_BY_ELEMENT_ID_AGGREGATE")
@Getter
@Setter
public class WiredByElementIdAggregate {

  @Id
  private String id;

  /**
   * What the service task wrote. It proves that the delivery found the method, although the
   * model names an expression which the method does not name.
   */
  private String serviceTaskRanAs;

  /**
   * The engine's id of the open user task. The CREATED notification writes it. A user task
   * with a form key used to reach no method at all.
   */
  private String userTaskId;

}
