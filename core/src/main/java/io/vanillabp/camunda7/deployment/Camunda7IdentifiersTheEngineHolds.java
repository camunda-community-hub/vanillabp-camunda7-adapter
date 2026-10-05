package io.vanillabp.camunda7.deployment;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.repository.Deployment;
import org.camunda.bpm.engine.repository.ResourceDefinition;

import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.IdentifierHeldElsewhere;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import lombok.extern.slf4j.Slf4j;

/**
 * Which identifiers of a workflow module this engine held before the deployment of this
 * boot - asked once per workflow module, right after the deployment returned.
 *
 * <h2>What the engine can be asked</h2>
 *
 * A BPMN process id and a DMN decision id are keys of the engine's repository, so both are
 * queryable: <code>processDefinitionKeyIn</code> answers for every process of a module in
 * one statement, while a decision has no such batch filter and is asked for one by one. A
 * message name, a signal name, an error code and an escalation code are not kept in any
 * index, they live in the models, and reading every model the engine holds is the growth
 * decision 10 in the repository's DECISIONS.md rules out for a start. So those kinds are not
 * asked about here and nothing is said about them either.
 * <p>
 * The query names no suspension state. A suspended definition still owns its key, which is
 * the rule decision 12 in the repository's DECISIONS.md states for the whole adapter. It
 * names no version either: our own deployment creates the NEWEST version of a key another
 * deployment may have held for years, so asking for the latest version would find nothing
 * but ourselves.
 *
 * <h2>How a foreign holder is told from our own history</h2>
 *
 * The Camunda DEPLOYMENT is what the engine can be asked about, and the part of its stamp which
 * carries here is the NAME: every VanillaBP generation deploys a workflow module under the
 * module's own id, version 1 included, which wrote the application name into the source where
 * this adapter writes the adapter type and id. So a deployment named after the workflow module
 * being deployed is this application's own earlier work, including everything version 1 left
 * behind, and it says nothing. Reading the source instead would report every definition of an
 * upgraded application on its first start.
 * <p>
 * What carries a different name is reported, and the source then says how sure the adapter can
 * be. A source naming a Camunda 7 adapter of VanillaBP means another adapter id or another
 * workflow module deployed it, and either may be this very application, which is what a
 * migration between two engines looks like, so the finding says that it cannot be told apart.
 * Any other source was written by something else entirely, a modelling tool or an application
 * which deploys its models itself.
 * <p>
 * A second application which deploys a workflow module of the same id is invisible here, and
 * nothing in the engine's deployment table could tell it apart: no column names an
 * application. That is the limit of the answer and it is written down rather than worked
 * around, because the alternative is a warning about this application's own history on every
 * boot.
 *
 * <h2>Why nothing here can fail a boot</h2>
 *
 * A finding is a warning which the core words, because whoever holds the name may be an
 * application which is running correctly (see decision 17 in the repository's DECISIONS.md).
 * Every query is wrapped the way {@link Camunda7TenantCheck} wraps its own: a failure is logged
 * at debug and nothing else.
 * Where the question about our own deployments cannot be answered, nothing is reported at
 * all - a finding nobody can attribute would name this application's own history.
 */
@Slf4j
public final class Camunda7IdentifiersTheEngineHolds {

  private Camunda7IdentifiersTheEngineHolds() {
  }

  /**
   * Asks the engine which of the given identifiers it already holds and reports what came
   * back to the core, which words the warning.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module being deployed
   * @param processIdsByKey The PLAIN BPMN process id per process definition key the engine
   *          knows it under
   * @param decisionIdsByKey The PLAIN decision id per decision definition key the engine
   *          knows it under
   * @param tenantId The tenant this adapter deploys into, <code>null</code> for none
   * @param repositoryService The engine's repository service, or <code>null</code> to skip
   * @param scoping The core's name-clash-avoidance support, or <code>null</code> to skip
   */
  public static void reportWhatTheEngineAlreadyHolds(
      final String adapterId,
      final String workflowModuleId,
      final Map<String, String> processIdsByKey,
      final Map<String, String> decisionIdsByKey,
      final String tenantId,
      final RepositoryService repositoryService,
      final NameClashAvoidanceSupport scoping) {

    if ((repositoryService == null) || (scoping == null)) {
      return;
    }
    final Set<String> ourOwnHistory;
    try {
      ourOwnHistory = deploymentsNamedAfterTheModule(workflowModuleId, tenantId, repositoryService);
    } catch (final RuntimeException e) {
      log.debug(
          "Camunda7[{}]: the engine did not answer which deployments carry the name of workflow module "
              + "'{}', so nothing was asked about the identifiers it already holds",
          adapterId,
          workflowModuleId,
          e);
      return;
    }

    final var holders = new HolderDescriptions(adapterId, workflowModuleId, repositoryService);
    final var found = new LinkedList<IdentifierHeldElsewhere>();
    found
        .addAll(
            whatIsHeldUnder(
                ScopedIdentifierKind.BPMN_PROCESS_ID,
                processIdsByKey,
                processDefinitionsOf(processIdsByKey, tenantId, repositoryService, adapterId, workflowModuleId),
                ourOwnHistory,
                holders));
    decisionIdsByKey
        .forEach((
            key,
            plainDecisionId) -> found
                .addAll(
                    whatIsHeldUnder(
                        ScopedIdentifierKind.DMN_DECISION_ID,
                        decisionIdsByKey,
                        decisionDefinitionsOf(key, tenantId, repositoryService, adapterId, workflowModuleId),
                        ourOwnHistory,
                        holders)));

    scoping.reportIdentifiersTheBpmsAlreadyHolds(adapterId, workflowModuleId, found);

  }

  /**
   * The deployments this engine holds under the name of the workflow module being deployed -
   * this application's own history, whichever VanillaBP version wrote it.
   *
   * @param workflowModuleId The workflow module being deployed
   * @param tenantId The tenant this adapter deploys into, <code>null</code> for none
   * @param repositoryService The engine's repository service
   * @return The deployment ids named after this workflow module
   */
  private static Set<String> deploymentsNamedAfterTheModule(
      final String workflowModuleId,
      final String tenantId,
      final RepositoryService repositoryService) {

    var query = repositoryService
        .createDeploymentQuery()
        .deploymentName(workflowModuleId);
    query = tenantId == null
        ? query.withoutTenantId()
        : query.tenantIdIn(tenantId);
    return query
        .list()
        .stream()
        .map(Deployment::getId)
        .collect(java.util.stream.Collectors.toSet());

  }

  /**
   * Every version the engine holds of the module's process definition keys - one statement
   * for the whole module, since a process definition key takes a batch filter.
   * <p>
   * The rows grow with the number of deployments which changed a model, which is the growth
   * decision 10 in the repository's DECISIONS.md accepts, and this one list replaces the
   * query per process the question would cost otherwise.
   */
  private static Collection<? extends ResourceDefinition> processDefinitionsOf(
      final Map<String, String> processIdsByKey,
      final String tenantId,
      final RepositoryService repositoryService,
      final String adapterId,
      final String workflowModuleId) {

    if (processIdsByKey.isEmpty()) {
      return java.util.List.of();
    }
    try {
      var query = repositoryService
          .createProcessDefinitionQuery()
          .processDefinitionKeyIn(processIdsByKey.keySet().toArray(String[]::new));
      query = tenantId == null
          ? query.withoutTenantId()
          : query.tenantIdIn(tenantId);
      return query.list();
    } catch (final RuntimeException e) {
      log.debug(
          "Camunda7[{}]: the engine did not answer which process definitions of workflow module '{}' it "
              + "holds, so the identifiers it already holds stay unreported",
          adapterId,
          workflowModuleId,
          e);
      return java.util.List.of();
    }

  }

  /**
   * Every version the engine holds of ONE decision definition key. A decision query has no
   * batch filter for its key, and the alternative of one
   * {@code decisionDefinitionKeyLike} per module would only work where the mode prefixes
   * the ids and would ask a different question in the other two modes. How many decisions a
   * module brings is a property of the application rather than of its history, so one
   * statement each is a number which does not grow while the application runs.
   */
  private static Collection<? extends ResourceDefinition> decisionDefinitionsOf(
      final String decisionDefinitionKey,
      final String tenantId,
      final RepositoryService repositoryService,
      final String adapterId,
      final String workflowModuleId) {

    try {
      var query = repositoryService
          .createDecisionDefinitionQuery()
          .decisionDefinitionKey(decisionDefinitionKey);
      query = tenantId == null
          ? query.withoutTenantId()
          : query.tenantIdIn(tenantId);
      return query.list();
    } catch (final RuntimeException e) {
      log.debug(
          "Camunda7[{}]: the engine did not answer who holds decision '{}' of workflow module '{}', so "
              + "that decision stays unreported",
          adapterId,
          decisionDefinitionKey,
          workflowModuleId,
          e);
      return java.util.List.of();
    }

  }

  /**
   * The findings among the definitions the engine answered with: everything this adapter id
   * did not deploy for this workflow module.
   *
   * @param kind Which kind of identifier those definitions are keyed by
   * @param plainIdentifiersByKey The plain identifier per key the engine knows
   * @param definitions What the engine answered
   * @param ourOwnHistory The deployment ids named after this workflow module
   * @param holders Where a deployment is described
   * @return One finding per definition somebody else deployed
   */
  private static Collection<IdentifierHeldElsewhere> whatIsHeldUnder(
      final ScopedIdentifierKind kind,
      final Map<String, String> plainIdentifiersByKey,
      final Collection<? extends ResourceDefinition> definitions,
      final Set<String> ourOwnHistory,
      final HolderDescriptions holders) {

    return definitions
        .stream()
        .filter(definition -> !ourOwnHistory.contains(definition.getDeploymentId()))
        .map(definition -> holders
            .describe(definition)
            .map(
                holder -> new IdentifierHeldElsewhere(
                    kind, plainIdentifiersByKey.get(definition.getKey()),
                    // only a task definition carries a process, and Camunda 7 does not
                    // scope those at all
                    null, "%s, holding version %d of it".formatted(holder.heldBy(), definition.getVersion()), holder
                        .certainlyForeign()))
            .orElse(null))
        .filter(java.util.Objects::nonNull)
        .filter(held -> held.plainIdentifier() != null)
        .toList();

  }

  /**
   * What the engine says about the deployment behind a definition, asked once per
   * deployment: several versions of several processes usually come from the same one.
   */
  private static final class HolderDescriptions {

    private final String adapterId;

    private final String workflowModuleId;

    private final RepositoryService repositoryService;

    private final Map<String, java.util.Optional<Holder>> byDeploymentId = new HashMap<>();

    HolderDescriptions(
        final String adapterId,
        final String workflowModuleId,
        final RepositoryService repositoryService) {

      this.adapterId = adapterId;
      this.workflowModuleId = workflowModuleId;
      this.repositoryService = repositoryService;

    }

    /**
     * @param definition A definition this adapter did not deploy
     * @return How to name its holder, or empty where the engine does not say
     */
    java.util.Optional<Holder> describe(
        final ResourceDefinition definition) {

      return byDeploymentId
          .computeIfAbsent(definition.getDeploymentId(), this::askTheEngineAboutTheDeployment);

    }

    private java.util.Optional<Holder> askTheEngineAboutTheDeployment(
        final String deploymentId) {

      try {
        final var deployment = repositoryService
            .createDeploymentQuery()
            .deploymentId(deploymentId)
            .singleResult();
        if (deployment == null) {
          // the deployment was removed between the two queries: there is nobody left to
          // name, and a finding nobody can attribute is worse than none
          return java.util.Optional.empty();
        }
        return java.util.Optional.of(holderOf(deployment));
      } catch (final RuntimeException e) {
        log.debug(
            "Camunda7[{}]: the engine did not answer who deployment '{}' belongs to, so what it holds of "
                + "workflow module '{}' stays unreported",
            adapterId,
            deploymentId,
            workflowModuleId,
            e);
        return java.util.Optional.empty();
      }

    }

    /**
     * The sentence the core puts behind "is already held by", plus how sure this adapter
     * is that the holder is somebody else.
     */
    private Holder holderOf(
        final Deployment deployment) {

      final var source = deployment.getSource();
      final var deployedByAVanillaBpAdapter = (source != null) && source
          .startsWith(Camunda7DeploymentService.ADAPTER_TYPE
              + ":");
      final var heldBy = "Camunda deployment '%s' (source '%s', deployed at %s)"
          .formatted(
              deployment.getName() != null
                  ? deployment.getName()
                  : "<unnamed>",
              source != null
                  ? source
                  : "<none>",
              deployment.getDeploymentTime());
      return new Holder(
          deployedByAVanillaBpAdapter
              ? heldBy
                  + ", which a VanillaBP Camunda 7 adapter deployed under another name"
              : heldBy
                  + ", which nothing of this workflow module deployed",
          // a source naming a Camunda 7 adapter of VanillaBP may be this very application:
          // another adapter id is what a migration between two engines looks like, and
          // another workflow module of the application writes its own name as well
          !deployedByAVanillaBpAdapter);

    }

  }

  /**
   * One holder as the warning names it.
   *
   * @param heldBy How the engine names the deployment
   * @param certainlyForeign Whether this cannot be an earlier deployment of this
   *          application
   */
  private record Holder(
                        String heldBy,
                        boolean certainlyForeign) {
  }

}
