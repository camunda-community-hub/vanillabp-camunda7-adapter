package io.vanillabp.camunda7.quarkus.callactivity;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The aggregate of the call-activity test: the called process works on it as well,
 * which is what the business key has to carry into the child.
 */
@Entity
@Table(name = "C7_CALLACTIVITY_AGGREGATE")
@Getter
@Setter
public class CallActivityAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String calledProcessDid;

}
