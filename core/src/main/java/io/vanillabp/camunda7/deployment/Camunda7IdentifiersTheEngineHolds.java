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
 * boot. It is asked once per workflow module, right after the deployment returned.
 *
 * <h2>What the engine can be asked</h2>
 *
 * A BPMN process id and a DMN decision id are keys of the engine's repository, so both can
 * be queried. <code>processDefinitionKeyIn</code> answers for every process of a module in
 * one statement. A decision has no such batch filter, so decisions are asked one by one. A
 * message name, a signal name, an error code and an escalation code are not kept in any
 * index. They live in the models. Reading every model the engine holds would be the growth
 * which decision 10 in the repository's DECISIONS.md rules out for a start. So these kinds
 * are not asked about here, and nothing is said about them either.
 * <p>
 * The query names no suspension state. A suspended definition still owns its key. Decision
 * 12 in the repository's DECISIONS.md states this rule for the whole adapter. The query
 * names no version either. Our own deployment creates the NEWEST version of a key, which
 * another deployment may have held for years. So asking for the latest version would find
 * nothing but ourselves.
 *
 * <h2>How a foreign holder is told from our own history</h2>
 *
 * The engine can be asked about the Camunda DEPLOYMENT. The part of its stamp which counts
 * here is the NAME. Every VanillaBP generation deploys a workflow module under the module's
 * own id, version 1 included. Version 1 wrote the application name into the source, where
 * this adapter writes the adapter type and id. So a deployment named after the workflow
 * module being deployed is this application's own earlier work. This includes everything
 * version 1 left behind, and nothing is said about it. Reading the source instead would
 * report every definition of an upgraded application on its first start.
 * <p>
 * A deployment with a different name is reported. Its source then says how sure the adapter
 * can be. A source which names a Camunda 7 adapter of VanillaBP means that another adapter
 * id or another workflow module deployed it. Either may be this very application, which is
 * what a migration between two engines looks like. So the finding says that it cannot tell
 * them apart. Any other source was written by something else entirely: a modelling tool, or
 * an application which deploys its models itself.
 * <p>
 * A second application which deploys a workflow module of the same id is invisible here.
 * Nothing in the engine's deployment table could tell it apart, because no column names an
 * application. That is the limit of the answer. It is written down here, not worked around.
 * The alternative would be a warning about this application's own history on every boot.
 *
 * <h2>Why nothing here can fail a boot</h2>
 *
 * A finding is a warning which the core words. Whoever holds the name may be an application
 * which runs correctly (see decision 17 in the repository's DECISIONS.md). Every query is
 * wrapped the way {@link Camunda7TenantCheck} wraps its own: a failure is logged at debug,
 * and nothing else happens.
 * If the question about our own deployments cannot be answered, nothing is reported at all.
 * A finding which nobody can attribute would name this application's own history.
 */
@Slf4j
public final class Camunda7IdentifiersTheEngineHolds {

  private Camunda7IdentifiersTheEngineHolds() {
  }

  /**
   * Asks the engine which of the given identifiers it already holds. It reports the answer
   * to the core, which words the warning.
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
   * The deployments this engine holds under the name of the workflow module being deployed.
   * This is the application's own history, whichever VanillaBP version wrote it.
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
   * Every version the engine holds of the module's process definition keys. This takes one
   * statement for the whole module, because a process definition key takes a batch filter.
   * <p>
   * The rows grow with the number of deployments which changed a model. Decision 10 in the
   * repository's DECISIONS.md accepts this growth. Otherwise the question would cost one
   * query per process, and this one list replaces them.
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
   * batch filter for its key. One {@code decisionDefinitionKeyLike} per module would only
   * work where the mode prefixes the ids. In the other two modes it would ask a different
   * question. How many decisions a module brings depends on the application, not on its
   * history. So one statement per decision is a number which does not grow while the
   * application runs.
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
   * What the engine says about the deployment behind a definition. It is asked once per
   * deployment, because several versions of several processes usually come from the same one.
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
          // the deployment was removed between the two queries. There is nobody left to
          // name, and a finding which nobody can attribute is worse than none
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
     * The sentence which the core puts behind "is already held by". It also says how sure
     * this adapter is that the holder is somebody else.
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
          // a source which names a Camunda 7 adapter of VanillaBP may be this very
          // application. Another adapter id is what a migration between two engines looks
          // like. And another workflow module of the application writes its own name, too
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
