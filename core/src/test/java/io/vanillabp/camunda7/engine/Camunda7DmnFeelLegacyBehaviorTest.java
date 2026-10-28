package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the key <code>dmn-feel-legacy-behavior</code> does to an engine. The case it exists
 * for is an application which runs the Operaton adapter next to this one. That application
 * removes the FEEL engine library from the Camunda 7 adapter. The test builds that class path:
 * the class path of this module without the library. On it a real engine evaluates a decision
 * with the key, and without the key it fails with the message which names both steps.
 * <p>
 * The other wrong class path, Operaton's newer library in front of Camunda's, is not built
 * here. It needs Operaton's library and Scala as test dependencies of this module. The test
 * hands the check a {@link NoSuchMethodError} instead, of the shape decision 50 in the
 * repository's DECISIONS.md describes.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7DmnFeelLegacyBehaviorTest {

  private static final int[] AMOUNTS = {
      999, 1000, 5000, 5001
  };

  private static final List<String> SIZES = List.of("small", "medium", "medium", "large");

  /**
   * The sentence of the message which names the key. It is asserted to be there and, in
   * the cases which must not report, to be absent.
   */
  private static final String NAMES_THE_KEY = "set 'vanillabp.adapters.c7.dmn-feel-legacy-behavior: true'";

  private static final String NAMES_THE_EXCLUSION = "exclude 'org.camunda.feel:feel-engine' from the dependency";

  @Test
  @DisplayName("the key switches the engine configuration to the older FEEL implementation and says so")
  public void theKeyReachesTheEngineConfiguration(
      final CapturedOutput output) {

    final var properties = new Camunda7EngineProperties();
    properties.setDmnFeelLegacyBehavior(true);
    final var configuration = new StandaloneInMemProcessEngineConfiguration();

    Camunda7DmnFeelLegacyBehavior.applyTo("c7", configuration, properties);

    assertTrue(configuration.isDmnFeelEnableLegacyBehavior());
    assertTrue(output.getAll().contains("('vanillabp.adapters.c7.dmn-feel-legacy-behavior: true')"), output.getAll());

  }

  @Test
  @DisplayName("without the key the engine configuration keeps its default and nothing is logged")
  public void withoutTheKeyNothingChanges(
      final CapturedOutput output) {

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    final var before = output.getAll().length();

    Camunda7DmnFeelLegacyBehavior.applyTo("c7", configuration, new Camunda7EngineProperties());

    assertFalse(new Camunda7EngineProperties().isDmnFeelLegacyBehavior(), "the default");
    assertFalse(configuration.isDmnFeelEnableLegacyBehavior());
    final var logged = output.getAll().substring(before);
    assertFalse(logged.contains("('vanillabp.adapters.c7.dmn-feel-legacy-behavior: true')"), logged);

  }

  @Test
  @DisplayName("with the key an engine without the FEEL engine library evaluates a decision")
  public void withTheKeyTheEngineNeedsNoFeelLibrary() throws Exception {

    final var answer = evaluateWithoutTheFeelLibrary("feel-less-with-key", true);

    assertEquals("FeelEngineFactoryImpl", answer.get(0), () -> answer.toString());
    assertEquals(SIZES, answer.subList(1, answer.size()));

  }

  @Test
  @DisplayName("without the key an engine without the FEEL engine library fails with both steps named")
  public void withoutTheKeyTheMissingLibraryIsExplained() throws Exception {

    final var answer = evaluateWithoutTheFeelLibrary("feel-less-without-key", false);

    assertEquals(1, answer.size(), () -> answer.toString());
    final var message = answer.get(0);
    assertTrue(message.startsWith("FAILED: java.lang.IllegalStateException: Camunda 7 adapter 'feel-less-without-key'"),
        message);
    assertTrue(message.contains("set 'vanillabp.adapters.feel-less-without-key.dmn-feel-legacy-behavior: true'"),
        message);
    assertTrue(message.contains(NAMES_THE_EXCLUSION), message);

  }

  @Test
  @DisplayName("without the key and with the library the engine evaluates as before")
  public void withTheLibraryAndWithoutTheKeyBehaviourIsUnchanged() {

    final var answer = Camunda7FeelLessEngine.evaluate("feel-with-library", false, AMOUNTS);

    assertEquals("ScalaFeelEngineFactory", answer.get(0), () -> answer.toString());
    assertEquals(SIZES, answer.subList(1, answer.size()));

  }

  @Test
  @DisplayName("a NoSuchMethodError of the FEEL engine library is explained")
  public void aLibraryOfTheWrongVersionIsExplained() {

    final var wrongVersion = new NoSuchMethodError(
        "'void org.camunda.feel.valuemapper.ValueMapper$CompositeValueMapper.<init>(scala.collection.immutable.List)'");
    final var failure = new ProcessEngineException("could not build the DMN engine", wrongVersion);

    final var explained = assertThrows(
        IllegalStateException.class,
        () -> Camunda7DmnFeelLegacyBehavior.explainWhyTheEngineDidNotStart("c7", new Camunda7EngineProperties(),
            failure));

    assertTrue(explained.getMessage().contains(NAMES_THE_KEY), explained.getMessage());
    assertTrue(explained.getMessage().contains(NAMES_THE_EXCLUSION), explained.getMessage());
    assertTrue(explained.getMessage().contains("NoSuchMethodError"), explained.getMessage());
    assertSame(failure, explained.getCause());

  }

  @Test
  @DisplayName("a linkage error thrown inside the FEEL engine library is explained, too")
  public void aFailureInsideTheLibraryIsExplained() {

    final var inside = new NoSuchMethodError("a method which is gone");
    inside.setStackTrace(new StackTraceElement[]{
        new StackTraceElement("org.camunda.feel.FeelEngine", "<init>", "FeelEngine.scala", 42)
    });

    final var explained = assertThrows(
        IllegalStateException.class,
        () -> Camunda7DmnFeelLegacyBehavior.explainWhyTheEngineDidNotStart("c7", new Camunda7EngineProperties(),
            inside));

    assertTrue(explained.getMessage().contains(NAMES_THE_KEY), explained.getMessage());

  }

  @Test
  @DisplayName("a failure which has nothing to do with FEEL is left as it is")
  public void otherFailuresAreLeftAlone() {

    final var elsewhere = new ProcessEngineException("the database is gone", new NoSuchMethodError("'void org.h2.Driver.load()'"));

    assertDoesNotThrow(
        () -> Camunda7DmnFeelLegacyBehavior.explainWhyTheEngineDidNotStart("c7", new Camunda7EngineProperties(),
            elsewhere));

  }

  @Test
  @DisplayName("with the key set the library is not blamed, because the engine does not use it")
  public void withTheKeyTheLibraryIsNotBlamed() {

    final var properties = new Camunda7EngineProperties();
    properties.setDmnFeelLegacyBehavior(true);
    final var failure = new NoClassDefFoundError("org/camunda/feel/FeelEngine");

    assertDoesNotThrow(
        () -> Camunda7DmnFeelLegacyBehavior.explainWhyTheEngineDidNotStart("c7", properties, failure));

  }

  /**
   * Runs {@link Camunda7FeelLessEngine} in a class loader which sees the class path of this
   * module without the FEEL engine library. Its parent is the platform class loader, so no
   * class of the engine comes from the test's own class loader.
   */
  @SuppressWarnings("unchecked")
  private static List<String> evaluateWithoutTheFeelLibrary(
      final String name,
      final boolean legacy) throws Exception {

    final var withoutFeel = Arrays
        .stream(System.getProperty("java.class.path").split(File.pathSeparator))
        .filter(entry -> !new File(entry).getName().startsWith("feel-engine-"))
        .map(Camunda7DmnFeelLegacyBehaviorTest::toUrl)
        .toArray(URL[]::new);
    assertTrue(
        Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
            .anyMatch(entry -> new File(entry).getName().startsWith("feel-engine-")),
        "the FEEL engine library is expected on the test class path, so there is something to remove");

    final var previous = Thread.currentThread().getContextClassLoader();
    try (var loader = new URLClassLoader(withoutFeel, ClassLoader.getPlatformClassLoader())) {
      Thread.currentThread().setContextClassLoader(loader);
      return (List<String>) loader
          .loadClass(Camunda7FeelLessEngine.class.getName())
          .getMethod("evaluate", String.class, boolean.class, int[].class)
          .invoke(null, name, legacy, AMOUNTS);
    } finally {
      Thread.currentThread().setContextClassLoader(previous);
    }

  }

  private static URL toUrl(
      final String entry) {

    try {
      return new File(entry).toURI().toURL();
    } catch (final MalformedURLException e) {
      throw new IllegalStateException(e);
    }

  }

}
