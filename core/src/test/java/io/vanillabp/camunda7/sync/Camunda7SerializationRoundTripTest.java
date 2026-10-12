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
 * What the probe of the startup check answers. It is measured against an engine with two
 * dataformats: one which loses something, and one which carries everything.
 * <p>
 * A warning depends on the three answers the probe can give. A format which carries the type
 * says nothing. A format which changes the value says what it changes it into. A format with
 * nothing registered for it says nothing at all. A probe which cannot measure must not
 * produce a warning about a model which may well be fine.
 * <p>
 * The lossy dataformat here is written by hand, not taken from SPIN. So the measurement is
 * about the probe, not about somebody else's Jackson version.
 * {@code Camunda7LossyFormatCheckIT} reads the same probe through the real SPIN JSON
 * dataformat, at both levels a value can travel at.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7SerializationRoundTripTest {

  /**
   * The format of the serializer below. No application registers this one. It stands for a
   * dataformat which does with a decimal what JSON does, and the name says so.
   */
  private static final String LOSSY_FORMAT = "application/measured-numbers";

  private static final String JAVA_FORMAT = "application/x-java-serialized-object";

  private ProcessEngine engine;

  /**
   * A dataformat which writes a number as its text and reads every number back as a double.
   * A JSON dataformat does the same with a decimal.
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

    // Java serialization writes the object and reads it back as it was, scale included.
    // So the probe answers nothing. It answers nothing as well where the measurement fails,
    // and this test alone cannot tell the two apart. aFormatWhichChangesTheValueSaysSo shows
    // that the measurement runs on this engine
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

    // the sample is the value which notices a change. BigDecimal.ONE survives every format
    assertEquals("120.50", whatComesBack.written());
    assertEquals(Double.class, whatComesBack.readBackType());
    assertEquals("120.5", whatComesBack.readBack());

  }

  @Test
  @DisplayName("A format no serializer is registered for says nothing")
  public void aFormatWithoutASerializerSaysNothing() {

    // the engine reports this one loudly by itself. It refuses the first push with 'Cannot
    // find serializer for value'. A warning about a lost scale would miss the point
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

    // a String needs no probe. The engine has a variable type for it, so nothing
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

    // a boot with twenty processes must not serialize the same sample twenty times. So
    // the second question gets the answer of the first
    assertSame(first.orElseThrow(), second.orElseThrow());

  }

  @Test
  @DisplayName("An engine which hands over no configuration has no probe")
  public void anEngineWithoutAConfigurationHasNoProbe() {

    // the deployment service keeps the null and asks nothing. A test which builds the
    // adapter without an engine relies on that
    assertNull(Camunda7SerializationRoundTrip.of(null));

  }

}
