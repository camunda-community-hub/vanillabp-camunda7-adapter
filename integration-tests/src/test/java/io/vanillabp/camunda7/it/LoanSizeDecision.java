package io.vanillabp.camunda7.it;

import java.util.ArrayList;
import java.util.List;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.variable.Variables;

/**
 * Deploys {@code dmn-feel/loan-size.dmn} into the application's engine and asks it about a
 * few amounts. The decision uses a comparison and a range, which both FEEL implementations
 * of Camunda 7 understand. It lives outside the workflow module's resources location, so no
 * other test deploys it.
 */
final class LoanSizeDecision {

  static final List<Integer> AMOUNTS = List.of(999, 1000, 5000, 5001);

  static final List<String> SIZES = List.of("small", "medium", "medium", "large");

  private LoanSizeDecision() {
    // static helper
  }

  /**
   * Which FEEL implementation the engine's DMN engine was built with, by simple class name.
   *
   * @param engine The application's engine
   * @return {@code FeelEngineFactoryImpl} for the older one, {@code ScalaFeelEngineFactory}
   *         for the library
   */
  static String feelImplementation(
      final ProcessEngine engine) {

    return ((ProcessEngineConfigurationImpl) engine.getProcessEngineConfiguration())
        .getDmnEngineConfiguration()
        .getFeelEngineFactory()
        .getClass()
        .getSimpleName();

  }

  /**
   * Deploys the decision and evaluates it for every amount of {@link #AMOUNTS}.
   *
   * @param engine The application's engine
   * @return The sizes the decision answered, one per amount
   */
  static List<String> evaluate(
      final ProcessEngine engine) {

    engine
        .getRepositoryService()
        .createDeployment()
        .addClasspathResource("dmn-feel/loan-size.dmn")
        .enableDuplicateFiltering(false)
        .name("loan-size")
        .deploy();
    final var sizes = new ArrayList<String>();
    for (final var amount : AMOUNTS) {
      sizes
          .add(engine
              .getDecisionService()
              .evaluateDecisionTableByKey("loanSize", Variables.putValue("amount", amount))
              .getSingleResult()
              .getSingleEntry()
              .toString());
    }
    return sizes;

  }

}
