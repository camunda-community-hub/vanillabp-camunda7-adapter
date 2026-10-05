package io.vanillabp.camunda7.deployment;

import org.camunda.bpm.engine.IdentityService;
import org.camunda.bpm.engine.identity.Tenant;

import lombok.extern.slf4j.Slf4j;

/**
 * What can be said about a Camunda 7 tenant before deploying into it.
 *
 * <h2>Camunda 7 needs no tenant to exist</h2>
 *
 * A tenant id is an ATTRIBUTE of a deployment and of the process definitions,
 * instances and tasks below it. The engine accepts any name, creates nothing and
 * deploys happily - so unlike Camunda 8 there is nothing that could reject the
 * deployment, and asking the engine "does this tenant exist" has no answer in the
 * engine's own terms.
 *
 * <h2>What the identity service knows</h2>
 *
 * Registered tenants ({@code ACT_ID_TENANT}, written by
 * {@link IdentityService#newTenant(String)}) exist for tenant MEMBERSHIPS and the
 * authorizations built on them. They are optional and most applications have none. An
 * application which does register them and forgets the one VanillaBP deploys into ends
 * up with a working deployment whose workflows nobody is authorized for, and that is
 * worth a warning. For everybody else this check stays silent.
 */
@Slf4j
public final class Camunda7TenantCheck {

  private Camunda7TenantCheck() {
  }

  /**
   * Warns if the given tenant is not registered in the identity service, but only if
   * the identity service knows tenants at all.
   * <p>
   * Every failure of the query is swallowed (a read-only identity provider such as LDAP
   * may not answer tenant queries): a diagnostic must never fail a deployment.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module about to be deployed, which the message names
   *          the key of so the developer can change the name for that module alone
   * @param tenantId The tenant about to be deployed into
   * @param identityService The engine's identity service, or <code>null</code> to skip
   */
  public static void warnAboutUnregisteredTenant(
      final String adapterId,
      final String workflowModuleId,
      final String tenantId,
      final IdentityService identityService) {

    if ((identityService == null) || (tenantId == null)) {
      return;
    }
    try {
      final var registered = identityService
          .createTenantQuery()
          .list()
          .stream()
          .map(Tenant::getId)
          .toList();
      if (registered.isEmpty() || registered.contains(tenantId)) {
        return;
      }
      log.warn(
          """
              Camunda7[{}]: tenant '{}' is not registered in the engine's identity service, \
              although {} other tenant(s) are: {}. The deployment works anyway - Camunda 7 treats a \
              tenant id as an attribute of the deployment and creates nothing - but tenant \
              memberships and the authorizations built on them do not cover this tenant. Register it \
              (IdentityService#newTenant) or point the adapter at a registered one \
              ('vanillabp.adapters.{}.tenant-id', or 'vanillabp.workflow-modules.{}.adapters.{}.tenant-id' \
              for this workflow module alone). Without either the tenant is named after the workflow \
              module.""",
          adapterId,
          tenantId,
          registered.size(),
          registered,
          adapterId,
          workflowModuleId,
          adapterId);
    } catch (final RuntimeException e) {
      log.debug(
          "Camunda7[{}]: the identity service did not answer a tenant query, so tenant '{}' was not checked",
          adapterId,
          tenantId,
          e);
    }

  }

  /**
   * Warns where the engine still runs workflows of this process somewhere the configured
   * scope does not reach.
   *
   * <h4>Which mistake this catches</h4>
   *
   * <code>name-clash-avoidance</code> decides where a workflow module's processes live,
   * and its default is <code>by-adapter</code>, which is what VanillaBP 1 did: a tenant
   * per module, named after the module. An application which ran version 1 with the
   * defaults therefore needs no setting at all. One which ran it with
   * <code>use-tenants: false</code> does: its workflows carry NO tenant while the default
   * deploys into one, so the application deploys happily, starts new workflows happily,
   * and never finds a single one of the workflows it started before.
   * <p>
   * Nothing else notices that. The deployment succeeds, because Camunda 7 treats a tenant
   * id as an attribute and creates nothing; the workflows are still there, still running,
   * and simply out of reach. Hence a query at startup, rather than trust in the guide
   * being read.
   *
   * <h4>Why it warns rather than ending the boot</h4>
   *
   * The finding has a legitimate reading: an application really may be leaving old
   * workflows behind, which is what the last step of a BPMS migration looks like. Ending
   * the boot would also stop the very application which is about to repair its
   * configuration. Every failure of the query is swallowed for the same reason the tenant
   * query above swallows its own - a diagnostic must never fail a deployment.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module being deployed
   * @param bpmnProcessId The BPMN process id as the application knows it
   * @param scopedBpmnProcessId The process id as the ENGINE knows it in this mode
   * @param tenantId The tenant this adapter deploys into, <code>null</code> for none
   * @param runtimeService The engine's runtime service, or <code>null</code> to skip
   */
  public static void warnAboutWorkflowsOutOfScope(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedBpmnProcessId,
      final String tenantId,
      final org.camunda.bpm.engine.RuntimeService runtimeService) {

    if (runtimeService == null) {
      return;
    }
    try {
      final var outOfScope = countOutOfScope(runtimeService, scopedBpmnProcessId, tenantId);
      final var underAnotherId = scopedBpmnProcessId.equals(bpmnProcessId)
          ? 0L
          : runtimeService.createProcessInstanceQuery().processDefinitionKey(bpmnProcessId).count();
      if ((outOfScope == 0) && (underAnotherId == 0)) {
        return;
      }
      log.warn(
          """
              Camunda7[{}]: the engine still runs workflows of BPMN process '{}' (workflow module \
              '{}') which this application will not find. {} This is what an upgrade looks like when \
              'vanillabp.adapters.{}.name-clash-avoidance' does not describe where the running \
              workflows actually live: the deployment succeeds either way, because Camunda 7 treats \
              a tenant id as an attribute of the deployment, so nothing else would ever say so. The \
              default 'by-adapter' is what VanillaBP 1 did with its defaults, a tenant named after \
              the workflow module; an application which ran version 1 with 'use-tenants: false' has \
              to say 'none' here. If you are leaving those workflows behind on purpose, this line is \
              the record of it.""",
          adapterId,
          bpmnProcessId,
          workflowModuleId,
          describe(outOfScope, underAnotherId, tenantId, scopedBpmnProcessId, bpmnProcessId),
          adapterId);
    } catch (final RuntimeException e) {
      log.debug(
          "Camunda7[{}]: the engine did not answer where the workflows of '{}' run, so nothing was checked",
          adapterId,
          bpmnProcessId,
          e);
    }

  }

  /**
   * How many workflows of this process run outside the tenant scope this adapter deploys
   * into.
   *
   * @param runtimeService The engine's runtime service
   * @param scopedBpmnProcessId The process id as the engine knows it
   * @param tenantId The tenant deployed into, <code>null</code> for none
   * @return The number of workflows out of reach
   */
  private static long countOutOfScope(
      final org.camunda.bpm.engine.RuntimeService runtimeService,
      final String scopedBpmnProcessId,
      final String tenantId) {

    if (tenantId == null) {
      // deploying into no tenant: everything under ANY tenant is out of reach
      final var all = runtimeService
          .createProcessInstanceQuery()
          .processDefinitionKey(scopedBpmnProcessId)
          .count();
      final var withoutTenant = runtimeService
          .createProcessInstanceQuery()
          .processDefinitionKey(scopedBpmnProcessId)
          .withoutTenantId()
          .count();
      return all - withoutTenant;
    }
    // deploying into a tenant: everything without one is out of reach. Workflows under a
    // DIFFERENT tenant are not counted - a second tenant on the same engine is a
    // legitimate arrangement rather than the mistake this looks for
    return runtimeService
        .createProcessInstanceQuery()
        .processDefinitionKey(scopedBpmnProcessId)
        .withoutTenantId()
        .count();

  }

  /**
   * The sentence naming what was found and where.
   *
   * @param outOfScope Workflows outside the tenant scope
   * @param underAnotherId Workflows under the unprefixed process id
   * @param tenantId The tenant deployed into, <code>null</code> for none
   * @param scopedBpmnProcessId The process id as the engine knows it
   * @param bpmnProcessId The process id as the application knows it
   * @return The sentence
   */
  private static String describe(
      final long outOfScope,
      final long underAnotherId,
      final String tenantId,
      final String scopedBpmnProcessId,
      final String bpmnProcessId) {

    final var found = new StringBuilder();
    if (outOfScope > 0) {
      found.append(tenantId == null
          ? "%d of them run under a tenant while this application deploys into none".formatted(outOfScope)
          : "%d of them carry no tenant while this application deploys into '%s'".formatted(outOfScope, tenantId));
      found.append('.');
    }
    if (underAnotherId > 0) {
      if (!found.isEmpty()) {
        found.append(' ');
      }
      found.append(
          "%d run under the unprefixed process id '%s', while this application deploys '%s'."
              .formatted(underAnotherId, bpmnProcessId, scopedBpmnProcessId));
    }
    return found.toString();

  }

}
