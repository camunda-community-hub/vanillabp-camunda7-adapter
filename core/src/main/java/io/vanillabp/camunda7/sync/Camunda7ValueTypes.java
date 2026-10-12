package io.vanillabp.camunda7.sync;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Set;

import io.vanillabp.integration.adapter.spi.values.ValueDirection;
import io.vanillabp.integration.adapter.spi.values.ValueTypeVerdict;

/**
 * What Camunda 7 does with one Java type. The startup check asks this to find out whether a
 * value survives the way to the engine and back.
 * <p>
 * Camunda 7 keeps a value in a variable of its own type where it HAS a type for it. Otherwise
 * it keeps it in an object variable. The types it has are the texts, the boolean, the
 * numbers of the JDK, a date and a byte array. An arbitrary-precision number is not among
 * them. So a {@link BigDecimal} is written in the serialization format configured for the
 * workflow. An expression then reads back what that format made of it, not the value the
 * application had.
 * <p>
 * This class does not guess what the format costs exactly.
 * {@link Camunda7SerializationRoundTrip} measures it by writing a sample through the
 * engine's own serializer, and the deployment reports what it measured. This class answers
 * the shorter question of the startup check: does the type reach the engine as itself, and
 * does it come back as itself?
 */
public final class Camunda7ValueTypes {

  /**
   * The types Camunda 7 has a variable type for. A value of one of them is stored as
   * itself, and an expression reads it as itself.
   */
  private static final Set<Class<?>> ENGINE_HAS_A_TYPE = Set
      .of(
          String.class,
          Boolean.class,
          boolean.class,
          Integer.class,
          int.class,
          Long.class,
          long.class,
          Short.class,
          short.class,
          Byte.class,
          byte.class,
          Double.class,
          double.class,
          Float.class,
          float.class,
          java.util.Date.class,
          byte[].class);

  private Camunda7ValueTypes() {
  }

  /**
   * What the engine does with that type.
   *
   * @param valueType The declared type of the value
   * @param direction Which way the value travels
   * @return The verdict for the startup check
   */
  public static ValueTypeVerdict verdictFor(
      final Class<?> valueType,
      final ValueDirection direction) {

    if (valueType == null) {
      return ValueTypeVerdict.cannotSay("no type was named");
    }
    if (valueType.isEnum()) {
      // VanillaBP hands an enum over as its name, and a text is a type the engine has
      return ValueTypeVerdict.survives();
    }
    if (ENGINE_HAS_A_TYPE.contains(valueType) || CharSequence.class.isAssignableFrom(valueType)) {
      return ValueTypeVerdict.survives();
    }
    if ((valueType == BigDecimal.class) || (valueType == BigInteger.class)) {
      return direction == ValueDirection.TO_BPMS
          ? ValueTypeVerdict
              .changed(
                  """
                      that it has no variable type for an arbitrary-precision number: the value is \
                      written in the serialization format configured for the workflow, and an \
                      expression reads back whatever that format made of it""")
          : ValueTypeVerdict
              .changed(
                  """
                      that the number arrives as the declared type but not with the scale it was \
                      written with: 120.50 comes back as 120.5, because the format carries the \
                      number and not the way it was written""");
    }
    return ValueTypeVerdict
        .cannotSay(
            """
                that it has no variable type for %s and keeps the value in the serialization format \
                configured for the workflow, which VanillaBP does not own"""
                .formatted(valueType.getSimpleName()));

  }

}
