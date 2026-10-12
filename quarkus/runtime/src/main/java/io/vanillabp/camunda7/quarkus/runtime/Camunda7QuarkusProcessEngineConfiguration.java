package io.vanillabp.camunda7.quarkus.runtime;

import org.camunda.bpm.engine.impl.cfg.JakartaTransactionProcessEngineConfiguration;

import jakarta.transaction.TransactionManager;

/**
 * Engine configuration for the plain Camunda 7 engine on Quarkus. It is the
 * {@link JakartaTransactionProcessEngineConfiguration} which ships with the engine, with no
 * {@code camunda-engine-cdi-jakarta} and no Camunda Quarkus extension. It uses the CDI
 * {@link TransactionManager} (Narayana). So engine commands join the caller's JTA
 * transaction, with the {@code REQUIRED} semantics of the engine's
 * {@code JakartaTransactionInterceptor}. That is the embedded engine's guarantee for phase
 * one.
 * <p>
 * <b>Schema operations:</b> by default the engine runs schema commands OUTSIDE any JTA
 * transaction, with a non-JTA command executor. But Agroal has no deferred enlistment. So
 * connections outside a JTA transaction cannot be used, and the schema update fails. This
 * class overrides that and runs schema commands in their own JTA transaction instead
 * ({@code REQUIRES_NEW} executor).
 */
public class Camunda7QuarkusProcessEngineConfiguration extends JakartaTransactionProcessEngineConfiguration {

  /**
   * Wires the engine to Narayana. It hands the schema commands a transaction of their own,
   * because Agroal has no connection outside one.
   *
   * @param transactionManager The Narayana transaction manager which owns every
   *          transaction the engine takes part in
   */
  public Camunda7QuarkusProcessEngineConfiguration(
      final TransactionManager transactionManager) {

    setTransactionManager(transactionManager);
    // Narayana owns all transactions. The engine never begins or commits on its own
    setTransactionsExternallyManaged(true);

  }

  /**
   * Agroal has no deferred enlistment. So schema operations run in their own JTA
   * transaction, not in the engine's non-JTA default (see class comment).
   */
  @Override
  protected void initCommandExecutorDbSchemaOperations() {

    if (commandExecutorSchemaOperations == null) {
      commandExecutorSchemaOperations = commandExecutorTxRequiresNew;
    }

  }

}
