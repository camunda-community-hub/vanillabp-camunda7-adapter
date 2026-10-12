package io.vanillabp.camunda7.sync;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.variable.serializer.TypedValueSerializer;
import org.camunda.bpm.engine.impl.variable.serializer.ValueFieldsImpl;
import org.camunda.bpm.engine.variable.Variables;
import org.camunda.bpm.engine.variable.value.ObjectValue;

/**
 * What a serialization format does to a value on the way through the engine. It is
 * MEASURED, not looked up.
 * <p>
 * A value the engine has no variable type for keeps its class in an object variable. An
 * expression then reads back the serializer's answer, not the application's value. Which
 * types a format carries unchanged depends on a dataformat VanillaBP does not own. An
 * application may register a JSON dataformat which keeps the scale of a decimal. And a
 * warning about something which works is worse than a missing one. So nothing here is
 * claimed from a list. A sample of the type is written and read through the engine's own
 * serializer for the configured format. Then what comes back is compared to what went in.
 * <p>
 * The comparison reads the class and the TEXT, not <code>equals</code> and not
 * <code>compareTo</code>. Under JSON, <code>1E+3</code> comes back as <code>1000.0</code>.
 * The two compare equal, but to a reader or a renderer they are not the same value.
 * <p>
 * A sample has to be the awkward one, otherwise the measurement says nothing.
 * <code>BigDecimal.ONE</code> survives every format, <code>120.50</code> does not. Only
 * types this class can build such a sample of get an answer at all. For anything else, and
 * for a format without a registered serializer, the answer is empty and the caller says
 * nothing. A format without its dataformat is loud on its own: the engine refuses the
 * first push with "Cannot find serializer for value".
 * <p>
 * The measurement runs inside an engine COMMAND, because that is where the engine
 * serializes a variable. Writing a variable asks the engine for the charset its dataformat
 * writes with. The engine refuses to answer outside a command.
 * <p>
 * Serializing the same sample once per deployed process would repeat the same work twenty
 * times on a boot with twenty processes. So an answer is remembered per format, per type and
 * per placement. The message is NOT remembered. It names the process and the attribute, and
 * that is the caller's business.
 */
public class Camunda7SerializationRoundTrip {

  /**
   * The key the nested sample travels under. A nested value reaches the engine inside the
   * map the sync model built. So it is measured that way.
   */
  private static final String SAMPLE_MEMBER = "value";

  /**
   * One sample per type. Each one is the value which notices a change: a decimal with a
   * scale, an integer above 2^53 where a double stops being exact, and a float whose double
   * form reads differently.
   */
  private static final Map<Class<?>, Object> SAMPLES = Map
      .of(
          BigDecimal.class, new BigDecimal("120.50"),
          BigInteger.class, new BigInteger("9007199254740993"),
          Float.class, 0.1f);

  /**
   * What the engine's serializers and the charset of a dataformat are taken from.
   */
  private final ProcessEngineConfigurationImpl configuration;

  /**
   * The answers already measured, keyed by format, type and placement.
   */
  private final Map<String, Optional<WhatComesBack>> answers = new ConcurrentHashMap<>();

  /**
   * What a format made of the sample, when it made anything of it at all.
   *
   * @param written What went in, as its text
   * @param readBackType The class it came back as
   * @param readBack What came back, as its text
   */
  public record WhatComesBack(
                              String written,
                              Class<?> readBackType,
                              String readBack) {
  }

  /**
   * Measures what a value becomes on its way into the engine and back. That is the only
   * honest way to say whether a model will read what the application wrote.
   *
   * @param configuration The engine configuration whose serializers are measured - the
   *          real ones, because what a value becomes depends on which dataformats the
   *          application installed
   */
  public Camunda7SerializationRoundTrip(
      final ProcessEngineConfigurationImpl configuration) {

    this.configuration = configuration;

  }

  /**
   * The probe of one engine, or <code>null</code> where the engine does not hand its
   * configuration over.
   *
   * @param engine The engine of one adapter id
   * @return The probe reading that engine's serializers
   */
  public static Camunda7SerializationRoundTrip of(
      final ProcessEngine engine) {

    if ((engine == null) || !(engine
        .getProcessEngineConfiguration() instanceof ProcessEngineConfigurationImpl configuration)) {
      return null;
    }
    return new Camunda7SerializationRoundTrip(configuration);

  }

  /**
   * What the given format changes about a value of that type.
   *
   * @param serializationFormat The format configured for the workflow, or
   *          <code>null</code> where none is
   * @param declaredType The type the application declares the value as
   * @param nested Whether the value travels inside the map of a nested structure, which
   *          is where a format loses the type rather than a digit
   * @return What comes back instead, or nothing where the format carries the value or
   *         where this cannot be measured
   */
  public Optional<WhatComesBack> whatTheFormatChangesAbout(
      final String serializationFormat,
      final Class<?> declaredType,
      final boolean nested) {

    if ((serializationFormat == null) || serializationFormat.isBlank() || (declaredType == null)) {
      return Optional.empty();
    }
    final var sample = SAMPLES.get(declaredType);
    if (sample == null) {
      return Optional.empty();
    }
    return answers
        .computeIfAbsent(
            "%s|%s|%s".formatted(serializationFormat, declaredType.getName(), nested),
            key -> whatComesBackOf(serializationFormat, sample, nested));

  }

  /**
   * Writes the sample and reads it again, through the serializer the engine would use.
   * <p>
   * A failure here is an answer nobody can act on, so it stays silent. The probe borrows a
   * serializer it does not own. A dataformat which refuses the sample must not cause a
   * message about a model which may well be fine. Neither must a format with nothing
   * registered for it.
   *
   * @param serializationFormat The configured format
   * @param sample The value which notices
   * @param nested Whether to measure it as a member of a map
   * @return What came back, where it differs from what went in
   */
  private Optional<WhatComesBack> whatComesBackOf(
      final String serializationFormat,
      final Object sample,
      final boolean nested) {

    try {
      // inside a COMMAND, because that is where the engine serializes a variable. Writing
      // one asks the engine for the charset of its dataformat. The engine refuses to answer
      // without an active command context
      return configuration
          .getCommandExecutorTxRequired()
          .execute(commandContext -> whatTheSerializerAnswers(serializationFormat, sample, nested));
    } catch (final RuntimeException e) {
      return Optional.empty();
    }

  }

  /**
   * The measurement itself: what the serializer of the configured format writes, and what
   * it reads back.
   *
   * @param serializationFormat The configured format
   * @param sample The value which notices
   * @param nested Whether to measure it as a member of a map
   * @return What came back, where it differs from what went in
   */
  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  private Optional<WhatComesBack> whatTheSerializerAnswers(
      final String serializationFormat,
      final Object sample,
      final boolean nested) {

    final Object written;
    if (nested) {
      // a nested value reaches the engine inside the map the sync model built. Nothing in
      // that map records what its members were. That is why the same type answers
      // differently one level down
      final var members = new LinkedHashMap<String, Object>();
      members.put(SAMPLE_MEMBER, sample);
      written = members;
    } else {
      written = sample;
    }
    final var variable = Variables
        .objectValue(written)
        .serializationDataFormat(serializationFormat)
        .create();
    final TypedValueSerializer serializer = configuration
        .getVariableSerializers()
        .findSerializerForValue(variable);
    final var fields = new ValueFieldsImpl();
    serializer.writeValue(variable, fields);
    final var readVariable = (ObjectValue) serializer.readValue(fields, true, false);
    final var readValue = readVariable.getValue();
    final var readSample = nested && (readValue instanceof Map<?, ?> members)
        ? members.get(SAMPLE_MEMBER)
        : readValue;
    if (readSample == null) {
      return Optional.empty();
    }
    if (readSample
        .getClass()
        .equals(sample.getClass()) && readSample
            .toString()
            .equals(sample.toString())) {
      return Optional.empty();
    }
    return Optional.of(new WhatComesBack(sample.toString(), readSample.getClass(), readSample.toString()));

  }

}
