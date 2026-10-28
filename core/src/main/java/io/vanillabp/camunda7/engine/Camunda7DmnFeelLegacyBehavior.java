package io.vanillabp.camunda7.engine;

import java.util.HashSet;
import java.util.Set;

import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which FEEL implementation the engine evaluates decisions with, for both platforms. FEEL is
 * the expression language of decision tables. Camunda 7 has two implementations of it. The
 * default one is the library {@code org.camunda.feel:feel-engine}. The older one is part of
 * the engine and needs no library. Camunda calls it the legacy behaviour.
 * <p>
 * The key <code>vanillabp.adapters.&lt;id&gt;.dmn-feel-legacy-behavior</code> switches an
 * engine to the older one. An application needs it when the Operaton adapter runs next to
 * this one. Operaton brings a newer version of the same library, and the Camunda 7 engine
 * does not start with it. So the application removes Camunda's copy and switches this engine
 * to the older implementation. Decision 50 in the repository's DECISIONS.md says why this is a
 * key of the adapter and not code of the application.
 * <p>
 * The engine fails while it is built when the library does not fit. That is a
 * {@code NoSuchMethodError} where the wrong version comes first on the class path, and a
 * {@code NoClassDefFoundError} where the library was removed but the key is not set.
 * {@link #explainWhyTheEngineDidNotStart} turns both into a message which names the two
 * steps.
 */
public final class Camunda7DmnFeelLegacyBehavior {

  private static final Logger log = LoggerFactory.getLogger(Camunda7DmnFeelLegacyBehavior.class);

  /**
   * The package of the FEEL engine library, as it shows up in a class name and in the
   * message of a {@link LinkageError}.
   */
  private static final String FEEL_LIBRARY_PACKAGE = "org.camunda.feel";

  private Camunda7DmnFeelLegacyBehavior() {
    // static helper
  }

  /**
   * Applies the key to an engine which is about to be built. It is called for every adapter
   * id. An id without the key keeps the engine's own default.
   *
   * @param adapterId The adapter id whose engine is being built
   * @param configuration The engine configuration, before the engine is built
   * @param properties The adapter id's engine settings
   */
  public static void applyTo(
      final String adapterId,
      final ProcessEngineConfigurationImpl configuration,
      final Camunda7EngineProperties properties) {

    if (!properties.isDmnFeelLegacyBehavior()) {
      return;
    }

    configuration.setDmnFeelEnableLegacyBehavior(true);
    log
        .info(
            """
                Camunda7[{}]: decisions are evaluated with the engine's older FEEL implementation \
                ('vanillabp.adapters.{}.dmn-feel-legacy-behavior: true'). It needs no FEEL engine \
                library, and it understands what simple decision tables use: comparisons, ranges and \
                lists of values. Input expressions and output entries without an expression language \
                of their own are read as JUEL.""",
            adapterId,
            adapterId);

  }

  /**
   * Called with what the engine threw while it was built. If the FEEL engine library caused
   * it, this throws an exception whose message names the two steps which fix it. Otherwise
   * it returns, and the caller throws the original.
   *
   * @param adapterId The adapter id whose engine did not start
   * @param properties The adapter id's engine settings
   * @param failure What building the engine threw
   * @throws IllegalStateException If the FEEL engine library did not fit this engine
   */
  public static void explainWhyTheEngineDidNotStart(
      final String adapterId,
      final Camunda7EngineProperties properties,
      final Throwable failure) {

    // with the key set the engine does not touch the library. So the library cannot be
    // what went wrong, and a message about it would send the developer the wrong way
    if (properties.isDmnFeelLegacyBehavior()) {
      return;
    }
    final var cause = feelLibraryFailure(failure);
    if (cause == null) {
      return;
    }

    throw new IllegalStateException(
        """
            Camunda 7 adapter '%s' could not start its engine, because the FEEL engine library \
            'org.camunda.feel:feel-engine' on the class path does not fit Camunda 7 (%s: %s). \
            This happens when the Operaton adapter runs next to this one, because Operaton brings a \
            newer version of that library. Do two things: \
            1) exclude 'org.camunda.feel:feel-engine' from the dependency of the Camunda 7 adapter, \
            and 2) set 'vanillabp.adapters.%s.dmn-feel-legacy-behavior: true'. Then the Camunda 7 \
            engine evaluates decisions with its older FEEL implementation, which needs no library."""
            .formatted(
                adapterId,
                cause.getClass().getSimpleName(),
                cause.getMessage(),
                adapterId), failure);

  }

  /**
   * The linkage error in the chain of causes which comes from the FEEL engine library, or
   * <code>null</code>. A linkage error is how the JVM says that a class or a method is
   * missing which some other class was compiled against. It counts if its message or one of
   * its stack frames names the library's package.
   */
  private static LinkageError feelLibraryFailure(
      final Throwable failure) {

    final Set<Throwable> seen = new HashSet<>();
    for (var current = failure; (current != null) && seen.add(current); current = current.getCause()) {
      if ((current instanceof LinkageError linkageError) && comesFromTheFeelLibrary(linkageError)) {
        return linkageError;
      }
    }
    return null;

  }

  private static boolean comesFromTheFeelLibrary(
      final LinkageError error) {

    final var message = error.getMessage();
    if ((message != null) && (message.contains(FEEL_LIBRARY_PACKAGE) || message
        .contains(FEEL_LIBRARY_PACKAGE.replace('.', '/')))) {
      return true;
    }
    for (final var frame : error.getStackTrace()) {
      if (frame.getClassName().startsWith(FEEL_LIBRARY_PACKAGE
          + ".")) {
        return true;
      }
    }
    return false;

  }

}
