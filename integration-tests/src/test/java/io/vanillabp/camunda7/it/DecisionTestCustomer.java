package io.vanillabp.camunda7.it;

import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.Setter;

/**
 * A nested type of the decision aggregate: shared values which are not scalars
 * become object variables in the configured serialization format.
 */
@Embeddable
@Getter
@Setter
public class DecisionTestCustomer {

  private String customerName;

  private boolean vip;

}
