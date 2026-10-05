package io.vanillabp.camunda7.quarkus.expressions;

/**
 * An order the aggregate has and does not share. An expression reading it reads a top-level
 * name which IS an attribute of the workflow aggregate, which is the case the migration
 * fallback of version 2.0 still answers and a later version no longer will.
 */
public class ExprHiddenOrder {

  public String getStatus() {

    return "OPEN";

  }

}
