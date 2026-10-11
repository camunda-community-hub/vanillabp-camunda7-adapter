package io.vanillabp.camunda7.quarkus.expressions;

/**
 * An order the aggregate has and does not share. An expression which reads it reads a
 * top-level name which IS an attribute of the workflow aggregate. The migration fallback of
 * version 2.0 still answers this case. A later version will not.
 */
public class ExprHiddenOrder {

  public String getStatus() {

    return "OPEN";

  }

}
