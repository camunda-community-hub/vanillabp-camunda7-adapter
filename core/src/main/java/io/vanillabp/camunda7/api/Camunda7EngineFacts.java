package io.vanillabp.camunda7.api;

import java.util.function.Function;

import io.vanillabp.camunda7.wiring.Camunda7ConfiguredTenant;
import io.vanillabp.camunda7.wiring.Camunda7Scoping;
import io.vanillabp.camunda7.wiring.Camunda7TaskRegistry;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;

/**
 * What this adapter knows about the engine of ONE configured adapter id, for something
 * running inside that engine.
 * <p>
 * There is one of these per configured <code>camunda7</code> adapter id, on both platforms,
 * built by the platform registrar from what it computes for the engine anyway. Two adapter
 * ids are two engines, so an extension asks the one belonging to the id it is dealing with
 * and tells them apart by {@link #adapterId()}.
 *
 * <h2>What it promises</h2>
 *
 * Every answer here is the answer this adapter itself acts on. None of them is derived from
 * a property a second time: the tenant is resolved through the very method the deployment
 * resolves the tenant it deploys under, and whether the engine joins the application's
 * transaction is read from the engine the adapter built. A reader therefore cannot disagree
 * with the adapter.
 *
 * <h2>What it does not promise</h2>
 *
 * Nothing before the deployment pipeline ran. {@link #taskRegistry()} fills up while
 * <code>wireBpmn</code> runs and {@link #definitionOf(String)} answers <code>null</code>
 * until the deployment service of this adapter id exists. Both are documented where they
 * stand.
 *
 * <p>
 * The promises above are held by <code>Camunda7EngineFactsTest</code>.
 */
public final class Camunda7EngineFacts {

  private final String adapterId;

  private final NameClashAvoidanceSupport scoping;

  private final Function<String, Camunda7ConfiguredTenant> configuredTenants;

  private final Camunda7TaskRegistry taskRegistry;

  /**
   * Collects what a caller outside this adapter needs to ask the engine itself. Nothing is
   * read here: every answer below is computed when it is asked for.
   *
   * @param adapterId The configured adapter id this engine belongs to
   * @param scoping The core's name-clash avoidance, or <code>null</code>
   * @param configuredTenants What the application configured as the tenant of a workflow
   *          module, resolved over the levels it may be set at, or <code>null</code>
   * @param taskRegistry The task registry of this engine
   */
  public Camunda7EngineFacts(
      final String adapterId,
      final NameClashAvoidanceSupport scoping,
      final Function<String, Camunda7ConfiguredTenant> configuredTenants,
      final Camunda7TaskRegistry taskRegistry) {

    this.adapterId = adapterId;
    this.scoping = scoping;
    this.configuredTenants = configuredTenants;
    this.taskRegistry = taskRegistry;

  }

  /**
   * Which adapter id these facts belong to. An application may configure two engines of
   * this type, so an answer without the id says nothing.
   *
   * @return The configured adapter id
   */
  public String adapterId() {

    return adapterId;

  }

  /**
   * The Camunda tenant this engine holds a workflow module under, which is what a query has
   * to spell to find that module's workflows.
   * <p>
   * It is <code>null</code> where the module's name-clash avoidance uses no tenant: under
   * <code>use-prefix</code> the prefixed identifiers are the isolation, and under
   * <code>none</code> there is none. Why a tenant is resolvable per workflow module is
   * decision 19 in the repository's DECISIONS.md.
   *
   * @param workflowModuleId The workflow module
   * @return The tenant, or <code>null</code>
   */
  public String tenantIdOf(
      final String workflowModuleId) {

    final var configured = configuredTenants == null
        ? null
        : configuredTenants.apply(workflowModuleId);
    return Camunda7Scoping
        .tenantIdFor(
            scoping,
            workflowModuleId,
            adapterId,
            configured == null
                ? null
                : configured.tenantId());

  }

  /**
   * Whether what this engine does happens inside the transaction the caller is in.
   * <p>
   * It does while the engine runs on the application's own data source, which is the normal
   * case and the one an embedded engine is chosen for. It does NOT once the adapter id was
   * given a data source of its own
   * (<code>vanillabp.adapters.&lt;id&gt;.data-source-name</code>): the engine then writes to
   * a resource the application's persistence does not take part in, so the two commit
   * separately even where a transaction manager enlists both. That holds on Spring Boot and
   * on Quarkus alike - a JTA transaction around two independent data sources is still two
   * commits, and this adapter already treats such an engine as separate everywhere else
   * (its task deliveries are repeatable and carry an identity for that reason).
   * <p>
   * A reader deciding where to write something of its own therefore has one answer, not two:
   * where this is <code>true</code> the write belongs in the caller's transaction, where it
   * is <code>false</code> it needs one of its own.
   *
   * @return Whether the engine's commands run in the caller's transaction
   */
  public boolean joinsTheApplicationTransaction() {

    return !taskRegistry.engineRunsOnItsOwnDataSource();

  }

  /**
   * The registry of what this adapter deployed into this engine - the way back from the
   * identifiers the engine reports to the workflow module and the BPMN process the
   * application wrote.
   * <p>
   * It fills up while the deployment pipeline runs, see
   * {@link Camunda7TaskRegistry#resolve(String, String)}.
   *
   * @return The registry, never <code>null</code>
   */
  public Camunda7TaskRegistry taskRegistry() {

    return taskRegistry;

  }

  /**
   * The deployed version behind a process definition id the engine reported, answered from
   * the adapter's cache: the adapter resolves a definition once and every later question
   * about it is free. A caller which would otherwise query the repository service per task
   * or per rendered page asks here instead.
   * <p>
   * How an operator reads that version is
   * {@link DeployedProcessVersion#displayVersion()} - the platform writes that string, so
   * every consumer of every BPMS spells one deployment the same way.
   *
   * @param processDefinitionId The engine's process definition id
   * @return The version, or <code>null</code> where the engine does not know that definition
   *         (any more) or the deployment service of this adapter id does not exist yet
   */
  public DeployedProcessVersion definitionOf(
      final String processDefinitionId) {

    return taskRegistry.definitionOf(processDefinitionId);

  }

}
