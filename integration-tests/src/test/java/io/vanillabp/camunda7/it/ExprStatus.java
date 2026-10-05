package io.vanillabp.camunda7.it;

/**
 * The enum of these tests: the sync model shares an enum as its
 * {@link Enum#name()}, so a model comparing it to a literal meets a string.
 */
public enum ExprStatus {

  OPEN,
  CLOSED

}
