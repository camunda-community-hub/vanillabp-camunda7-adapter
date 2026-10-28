package io.vanillabp.camunda7.engine;

import java.util.ArrayList;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.cfg.StandaloneInMemProcessEngineConfiguration;
import org.camunda.bpm.engine.variable.Variables;

/**
 * Builds an engine the way both platforms do it, and evaluates the decision
 * {@code dmn-feel/loan-size.dmn} with the DMN engine of that engine.
 * {@link Camunda7DmnFeelLegacyBehaviorTest} runs it in a class loader without the FEEL engine
 * library, which is what an application next to the Operaton adapter has. Everything crosses
 * the class loader boundary as a string, because the two sides do not share a class.
 */
public class Camunda7FeelLessEngine {

  /**
   * Builds an engine and evaluates the decision for every amount.
   *
   * @param name The engine and database name, one per call
   * @param legacy The value of the key <code>dmn-feel-legacy-behavior</code>
   * @param amounts The amounts the decision is asked about
   * @return The sizes the decision answered, one per amount, or the message the engine did
   *         not start with, after {@code FAILED: }
   */
  public static List<String> evaluate(
      final String name,
      final boolean legacy,
      final int[] amounts) {

    final var properties = new Camunda7EngineProperties();
    properties.setDmnFeelLegacyBehavior(legacy);

    final var configuration = new StandaloneInMemProcessEngineConfiguration();
    configuration.setProcessEngineName(name);
    configuration.setJdbcUrl("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name));
    configuration.setDatabaseSchemaUpdate("create-drop");
    configuration.setHistoryTimeToLive("P180D");
    configuration.setJobExecutorActivate(false);
    Camunda7DmnFeelLegacyBehavior.applyTo(name, configuration, properties);

    final ProcessEngine engine;
    try {
      try {
        engine = configuration.buildProcessEngine();
      } catch (final RuntimeException | LinkageError e) {
        Camunda7DmnFeelLegacyBehavior.explainWhyTheEngineDidNotStart(name, properties, e);
        throw e;
      }
    } catch (final RuntimeException | LinkageError e) {
      return List.of("FAILED: %s: %s".formatted(e.getClass().getName(), e.getMessage()));
    }

    try {
      final var sizes = new ArrayList<String>();
      sizes.add(((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
          .getDmnEngineConfiguration()
          .getFeelEngineFactory()
          .getClass()
          .getSimpleName());
      engine
          .getRepositoryService()
          .createDeployment()
          .addClasspathResource("dmn-feel/loan-size.dmn")
          .deploy();
      for (final var amount : amounts) {
        sizes.add(engine
            .getDecisionService()
            .evaluateDecisionTableByKey("loanSize", Variables.putValue("amount", amount))
            .getSingleResult()
            .getSingleEntry()
            .toString());
      }
      return sizes;
    } finally {
      engine.close();
    }

  }

}
