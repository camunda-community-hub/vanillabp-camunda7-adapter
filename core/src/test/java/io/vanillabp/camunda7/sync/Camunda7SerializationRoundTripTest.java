package io.vanillabp.camunda7.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.variable.serializer.AbstractObjectValueSerializer;
import org.camunda.bpm.engine.impl.variable.serializer.TypedValueSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the probe of the startup check answers, measured against an engine which carries
 * one dataformat losing something and one carrying everything.
 * <p>
 * The three answers it can give are what a warning depends on: a format which carries the
 * type says nothing, a format which changes the value says what it changes it into, and a
 * format nothing is registered for says nothing at all, because a probe which cannot
 * measure must not produce a warning about a model which may well be fine.
 * <p>
 * The lossy dataformat here is written by hand rather than taken from SPIN, so the
 * measurement is about the probe instead of about somebody else's Jackson version.
 * {@code Camunda7LossyFormatCheckIT} reads the same probe through the real SPIN JSON
 * dataformat, at both levels a value can travel at.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7SerializationRoundTripTest {

  /**
   * The format of the serializer below. No application registers this one - it stands for
   * a dataformat which does what JSON does with a decimal, and the name says so.
   */
  private static final String LOSSY_FORMAT = "application/measured-numbers";

  private static final String JAVA_FORMAT = "application/x-java-serialized-object";

  private ProcessEngine engine;

  /**
   * A dataformat which writes a number as its text and reads every number back as a
   * double, which is what a JSON dataformat does with a decimal.
   */
  private static final class NumbersComeBackAsDoubles extends AbstractObjectValueSerializer {

    NumbersComeBackAsDoubles() {

      super(LOSSY_FORMAT);

    }

    @Override
    public String getName() {

      return LOSSY_FORMAT;

    }

    @Override
    protected String getTypeNameForDeserialized(
        final Object deserializedObject) {

      return deserializedObject
          .getClass()
          .getName();

    }

    @Override
    protected byte[] serializeToByteArray(
        final Object deserializedObject) {

      return String
          .valueOf(deserializedObject)
          .getBytes(StandardCharsets.UTF_8);

    }

    @Override
    protected Object deserializeFromByteArray(
        final byte[] bytes,
        final String objectTypeName) {

      return Double.valueOf(new String(bytes, StandardCharsets.UTF_8));

    }

    @Override
    protected boolean canSerializeValue(
        final Object value) {

      return value instanceof Number;

    }

    @Override
    protected boolean isSerializationTextBased() {

      return true;

    }

  }

  @BeforeEach
  public void bootTheEngine() {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration
        .setJdbcUrl("jdbc:h2:mem:serialization-round-trip-%s;DB_CLOSE_DELAY=-1".formatted(System.nanoTime()));
    configuration.setJobExecutorActivate(false);
    configuration
        .setCustomPostVariableSerializers(List.<TypedValueSerializer>of(new NumbersComeBackAsDoubles()));
    engine = configuration.buildProcessEngine();

  }

  @AfterEach
  public void closeTheEngine() {

    engine.close();

  }

  @Test
  @DisplayName("A format which carries the type says nothing")
  public void aFormatWhichCarriesTheTypeSaysNothing() {

    // Java serialization writes the object and reads it back as it was, scale included -
    // which is why an application configuring no format hears nothing about round-tripping
    assertTrue(
        Camunda7SerializationRoundTrip
            .of(engine)
            .whatTheFormatChangesAbout(JAVA_FORMAT, BigDecimal.class, false)
            .isEmpty());

  }

  @Test
  @DisplayName("A format which changes the value says what it changes it into")
  public void aFormatWhichChangesTheValueSaysSo() {

    final var whatComesBack = Camunda7SerializationRoundTrip
        .of(engine)
        .whatTheFormatChangesAbout(LOSSY_FORMAT, BigDecimal.class, false)
        .orElseThrow();

    // the sample is the value which notices: BigDecimal.ONE survives every format
    assertEquals("120.50", whatComesBack.written());
    assertEquals(Double.class, whatComesBack.readBackType());
    assertEquals("120.5", whatComesBack.readBack());

  }

  @Test
  @DisplayName("A format no serializer is registered for says nothing")
  public void aFormatWithoutASerializerSaysNothing() {

    // the engine says this one loudly by itself: it refuses the first push with 'Cannot
    // find serializer for value'. A warning about a lost scale would be beside the point
    assertTrue(
        Camunda7SerializationRoundTrip
            .of(engine)
            .whatTheFormatChangesAbout("application/nobody-registered-this", BigDecimal.class, false)
            .isEmpty());

  }

  @Test
  @DisplayName("A type the probe has no sample of says nothing, and neither does a missing format")
  public void whatCannotBeMeasuredSaysNothing() {

    final var probe = Camunda7SerializationRoundTrip.of(engine);

    // a String needs no probe: the engine has a variable type for it, so nothing
    // serializes it in the first place
    assertTrue(probe.whatTheFormatChangesAbout(LOSSY_FORMAT, String.class, false).isEmpty());
    assertTrue(probe.whatTheFormatChangesAbout(null, BigDecimal.class, false).isEmpty());
    assertTrue(probe.whatTheFormatChangesAbout("  ", BigDecimal.class, false).isEmpty());
    assertTrue(probe.whatTheFormatChangesAbout(LOSSY_FORMAT, null, false).isEmpty());

  }

  @Test
  @DisplayName("The same question is measured once and answered from then on")
  public void theSameQuestionIsMeasuredOnce() {

    final var probe = Camunda7SerializationRoundTrip.of(engine);

    final var first = probe.whatTheFormatChangesAbout(LOSSY_FORMAT, BigDecimal.class, false);
    final var second = probe.whatTheFormatChangesAbout(LOSSY_FORMAT, BigDecimal.class, false);

    // a boot with twenty processes must not serialize the same sample twenty times, so
    // the answer of the second question is the answer of the first
    assertSame(first.orElseThrow(), second.orElseThrow());

  }

  @Test
  @DisplayName("An engine which hands over no configuration has no probe")
  public void anEngineWithoutAConfigurationHasNoProbe() {

    // the deployment service keeps the null and asks nothing, which is what a test
    // building the adapter without an engine relies on
    assertNull(Camunda7SerializationRoundTrip.of(null));

  }

}
