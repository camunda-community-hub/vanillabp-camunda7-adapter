package io.vanillabp.camunda7.it;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The workflow aggregate of the modelled-listener scenario. Each of the two listeners of the
 * end event writes its own attribute, so the test can tell from the aggregate alone that both
 * listeners reached a method of the application and which one did.
 * <p>
 * Written by a listener method rather than read by the model on purpose: on Camunda 7 the
 * shared values of a listener are written in the engine's own transaction, which is the
 * difference to Camunda 8 worth proving against a real engine.
 */
@Entity
@Table(name = "C7_MODELLED_LISTENER_AGGREGATE")
public class ModelledListenerAggregate {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /**
   * Written by the <code>camunda:expression</code> listener on the <code>start</code> event of
   * the end event.
   */
  private boolean theEndWasReached;

  /**
   * Written by the <code>camunda:delegateExpression</code> listener on its <code>end</code>
   * event, which is the form where the EL resolver has to hand the engine a listener object.
   */
  private boolean theEndWasDone;

  /**
   * What the task before the end event wrote, so a workflow which never got there is told
   * apart from one whose listeners were not served.
   */
  private boolean theWorkWasDone;

  /**
   * Written by the listener of the user task when that listener FIRES, which is the ordinary
   * notification every served listener gets.
   */
  private boolean theWaitBegan;

  /**
   * Written by the same listener method when the boundary timer takes the user task away. The
   * method is called a second time, with CANCELED, through the listener VanillaBP attaches
   * itself.
   */
  private boolean theWaitWasCanceled;

  public Long getId() {

    return id;

  }

  public boolean isTheEndWasReached() {

    return theEndWasReached;

  }

  public void setTheEndWasReached(
      final boolean theEndWasReached) {

    this.theEndWasReached = theEndWasReached;

  }

  public boolean isTheEndWasDone() {

    return theEndWasDone;

  }

  public void setTheEndWasDone(
      final boolean theEndWasDone) {

    this.theEndWasDone = theEndWasDone;

  }

  public boolean isTheWaitBegan() {

    return theWaitBegan;

  }

  public void setTheWaitBegan(
      final boolean theWaitBegan) {

    this.theWaitBegan = theWaitBegan;

  }

  public boolean isTheWaitWasCanceled() {

    return theWaitWasCanceled;

  }

  public void setTheWaitWasCanceled(
      final boolean theWaitWasCanceled) {

    this.theWaitWasCanceled = theWaitWasCanceled;

  }

  public boolean isTheWorkWasDone() {

    return theWorkWasDone;

  }

  public void setTheWorkWasDone(
      final boolean theWorkWasDone) {

    this.theWorkWasDone = theWorkWasDone;

  }

}
