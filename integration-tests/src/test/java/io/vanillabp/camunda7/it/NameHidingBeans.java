package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Spring beans named like a name of the model. Each exists only under the profile of the
 * test which needs it.
 */
public final class NameHidingBeans {

  private NameHidingBeans() {
  }

  /**
   * Named like the task definition 'chargeCard'. The engine would hand the expression to
   * this bean, so the start ends.
   */
  @Component("chargeCard")
  @Profile("name-hiding-bean")
  public static class ChargeCardBean {
  }

  /**
   * Named like the element id 'reviewOrder'. An element id is never an EL name, so the
   * start goes on.
   */
  @Component("reviewOrder")
  @Profile("name-hiding-element-id")
  public static class ReviewOrderBean {
  }

}
