package io.vanillabp.camunda7.sync;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.camunda.bpm.engine.variable.Variables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns the values a workflow aggregate shares with the BPMS into Camunda 7 process
 * variables.
 * <p>
 * The core already hands over a normalized shape: scalars, {@link Map}s of nested types
 * and {@link Collection}s of them. The sync model has turned everything the engine cannot
 * compare into its string form. What is left is the question which variable the engine
 * gets:
 * <ul>
 * <li><b>a value the engine has a variable type for is written as it is</b>: a short, an
 * integer, a long, a double, a boolean, a string, a date and bytes. A BPMN condition
 * compares it (<code>${amount &gt; 1000}</code>), and Cockpit shows a value instead of a
 * document. A <code>Character</code> is written as a string, which costs nothing. EL
 * compares a character to <code>'A'</code> and to <code>"A"</code> alike, and a string
 * variable is readable;</li>
 * <li><b>everything else keeps its class in an object variable</b> of the configured
 * serialization format (see {@link Camunda7SerializationFormats}). That is a nested
 * structure, or a number this engine has no type for: a <code>BigDecimal</code>, a
 * <code>BigInteger</code> or a <code>Float</code>. These numbers keep their class because a
 * model should read the application's own value. <code>120.50</code> widened to a double
 * renders as <code>120.5</code>. A <code>BigInteger</code> above 2^53 loses its last
 * digits. Neither says a word about it. And keeping the class loses nothing. EL turns both
 * sides of a comparison into <code>BigDecimal</code> as soon as one of them is one. So
 * <code>${total &gt; 100}</code> compares numbers, whatever the class is. The format also
 * keeps dot-notated expressions working. The engine deserializes the variable and EL
 * navigates it, so <code>${order.customer.name}</code> reads what it says. The application
 * chooses the format: <code>application/json</code> (the SPIN JSON dataformat),
 * <code>application/xstream</code>
 * (<a href="https://github.com/RasPelikan/camunda-xstream">camunda-xstream</a>) or any
 * dataformat it registers. The adapter only passes it on.</li>
 * </ul>
 * Without a configured format, the engine falls back to Java serialization. Nobody can read
 * that in Cockpit, and the engine's database then holds the application's class versions.
 * That is worth exactly one warning. This class logs it the first time it writes such a
 * value.
 * <p>
 * Decision 1 in the repository's DECISIONS.md says what is written. Decision 9 in the
 * repository's DECISIONS.md says in which format. Decision 16 in the repository's
 * DECISIONS.md says that a number the engine has no type for keeps its class and is not
 * widened.
 */
public final class Camunda7Variables {

  private static final Logger log = LoggerFactory.getLogger(Camunda7Variables.class);

  /**
   * The missing-format warning is logged once per JVM. It names a configuration gap, and one
   * line per variable would bury it.
   */
  private static final AtomicBoolean MISSING_FORMAT_REPORTED = new AtomicBoolean();

  private Camunda7Variables() {
  }

  /**
   * Converts the shared values into process variables.
   *
   * @param sharedValues What the aggregate shares (may be empty)
   * @param serializationFormat The format a value the engine has no type for is stored
   *          in, or <code>null</code> to leave that to the engine's default
   * @return The variables, in the order given
   */
  public static Map<String, Object> of(
      final Map<String, Object> sharedValues,
      final String serializationFormat) {

    final var variables = new LinkedHashMap<String, Object>();
    if (sharedValues == null) {
      return variables;
    }
    // a plain put, NOT Map.of. A shared attribute may well be null, and the engine
    // stores a null variable just fine
    sharedValues.forEach((
        name,
        value) -> variables.put(name, variableValueOf(name, value, serializationFormat)));
    return variables;

  }

  /**
   * One value as the engine stores it best.
   */
  private static Object variableValueOf(
      final String name,
      final Object value,
      final String serializationFormat) {

    if (camunda7HasNoVariableTypeFor(value)) {
      return objectValue(name, value, serializationFormat);
    }
    if (value instanceof Character) {
      return String.valueOf(value);
    }
    return value;

  }

  /**
   * Whether the engine would have to invent a type for this value.
   * <p>
   * Camunda 7 stores a short, an integer, a long, a double, a boolean, a string, a date
   * and bytes as themselves. A structure has never fitted any of those. A
   * <code>BigDecimal</code>, a <code>BigInteger</code> and a <code>Float</code> do not
   * fit either. This adapter used to widen them to a double. Then the model read a different
   * value than the one the application holds. Nobody was told about that price, and it bought
   * nothing.
   *
   * @param value One shared value, which may be <code>null</code>
   * @return Whether it can only travel as an object variable
   */
  private static boolean camunda7HasNoVariableTypeFor(
      final Object value) {

    if ((value instanceof Map<?, ?>) || (value instanceof Collection<?>)) {
      return true;
    }
    return (value instanceof BigDecimal) || (value instanceof BigInteger) || (value instanceof Float);

  }

  /**
   * A value the engine has no variable type for, as an object variable of the configured
   * format.
   */
  private static Object objectValue(
      final String name,
      final Object value,
      final String serializationFormat) {

    if ((serializationFormat == null) || serializationFormat.isBlank()) {
      reportMissingFormat(name);
      // no format configured, so the engine's own default decides. An application may well
      // have set that default on the engine itself
      return Variables
          .objectValue(value)
          .create();
    }
    return Variables
        .objectValue(value)
        .serializationDataFormat(serializationFormat)
        .create();

  }

  /**
   * Says once that a value the engine has no variable type for is stored in whatever the
   * engine defaults to.
   * <p>
   * A structure has always caused this warning. Now a number the engine has no type for
   * causes it as well. So an application which shares nothing but plain attributes may see
   * it for the first time. That is right, because Java serialization really stores such a
   * value.
   */
  private static void reportMissingFormat(
      final String name) {

    if (!MISSING_FORMAT_REPORTED.compareAndSet(false, true)) {
      return;
    }
    log.warn(
        """
            The workflow aggregate shares '{}', a value Camunda 7 has no variable type for, but no \
            serialization format is configured for it - the engine stores it in whatever its \
            'defaultSerializationFormat' says, which without a dataformat plugin is JAVA \
            serialization: unreadable in Cockpit, and the engine's database then depends on your \
            class versions. Configure a format VanillaBP passes to the engine, per workflow, per \
            workflow module or per adapter:
            vanillabp.adapters.<id>.serialization-format: application/json
            and put the matching dataformat on the classpath (the SPIN JSON dataformat for \
            'application/json', camunda-xstream for 'application/xstream'). Where the value is a \
            structure, a format keeps dot-notated expressions working as well, since the engine \
            deserializes the variable before evaluating '{}.something'.""",
        name,
        name);

  }

}
