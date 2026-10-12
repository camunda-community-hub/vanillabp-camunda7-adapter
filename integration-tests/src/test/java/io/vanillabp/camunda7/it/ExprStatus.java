package io.vanillabp.camunda7.it;

/**
 * The enum of these tests. The sync model shares an enum as its {@link Enum#name()}.
 * So a model which compares it to a literal gets a string.
 */
public enum ExprStatus {

  OPEN,
  CLOSED

}
