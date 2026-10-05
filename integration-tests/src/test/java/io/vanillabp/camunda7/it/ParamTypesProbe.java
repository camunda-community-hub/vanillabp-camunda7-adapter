package io.vanillabp.camunda7.it;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the handlers of the parameter-type cases received. A cell whose conversion is
 * refused leaves no entry here, and the incident the engine raised is what the test reads
 * instead.
 */
public final class ParamTypesProbe {

  /**
   * @param cell The BPMN element the handler served
   * @param className The class of what arrived, or "null"
   * @param text Its toString()
   */
  public record Arrival(String cell, String className, String text) {
  }

  private static final List<Arrival> ARRIVALS = new CopyOnWriteArrayList<>();

  private ParamTypesProbe() {
  }

  public static void arrived(
      final String cell,
      final Object value) {

    ARRIVALS
        .add(new Arrival(cell, value == null
            ? "null"
            : value.getClass().getName(), String.valueOf(value)));

  }

  public static void clear() {

    ARRIVALS.clear();

  }

  public static Arrival arrivalAt(
      final String cell) {

    return ARRIVALS
        .stream()
        .filter(arrival -> arrival.cell().equals(cell))
        .findFirst()
        .orElse(null);

  }

}
