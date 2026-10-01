package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.BusinessRuleTask;
import org.camunda.bpm.model.bpmn.instance.CallActivity;
import org.camunda.bpm.model.bpmn.instance.Error;
import org.camunda.bpm.model.bpmn.instance.Escalation;
import org.camunda.bpm.model.bpmn.instance.Message;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.Signal;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind;
import lombok.extern.slf4j.Slf4j;

/**
 * Applies {@link NameClashAvoidance#USE_PREFIX} to a Camunda 7 model:
 * every identifier the ENGINE resolves across process definitions is prefixed, so
 * two workflow modules may use the same names without a Camunda tenant.
 *
 * <table>
 * <caption>What is rewritten - and what deliberately is not</caption>
 * <tr><th>Element</th><th>Rewritten?</th><th>Why</th></tr>
 * <tr><td>{@code bpmn:process id}</td><td>yes</td><td>the process definition key is engine-wide</td></tr>
 * <tr><td>{@code camunda:calledElement} of a call activity</td><td>yes</td><td>it addresses the renamed process</td></tr>
 * <tr><td>{@code bpmn:message name}</td><td>yes</td><td>message correlation resolves by name across definitions</td></tr>
 * <tr><td>{@code bpmn:signal name}, {@code bpmn:escalation escalationCode}</td><td>yes</td><td>broadcast by name</td></tr>
 * <tr><td>{@code bpmn:error errorCode}</td><td>yes</td><td>completeness with the other adapters - the application may raise it via {@code ProcessService#cancelTask}</td></tr>
 * <tr><td>{@code camunda:decisionRef} of a business rule task</td><td>yes</td><td>it addresses a decision the module deploys, whose id was renamed the same way (see {@code DmnDecisionIds}). An expression gets the prefix in front of it, like a called element</td></tr>
 * <tr><td>task definitions ({@code camunda:expression}, {@code camunda:delegateExpression}, {@code camunda:formKey})</td><td><b>no</b></td><td>they are PROCESS-LOCAL in Camunda 7: the expression is evaluated inside the process by VanillaBP's EL resolver, nothing subscribes to them engine-wide. Camunda 8 job types are the opposite case and ARE prefixed.</td></tr>
 * </table>
 * <p>
 * Why the rewrite runs once per FILE and not once per process is decision 5 in the repository's
 * DECISIONS.md; why it runs at all is decision 3 in the repository's
 * DECISIONS.md.
 */
@Slf4j
public final class Camunda7Scoping {

  private Camunda7Scoping() {
  }

  /**
   * The Camunda TENANT a workflow module is deployed to, respectively an operation of
   * it runs in: the workflow module id unless the application configured a name, and
   * <code>null</code> wherever the mode is not {@link NameClashAvoidance#BY_ADAPTER}
   * ({@code none} uses no tenant, and under {@code use-prefix} the prefixed
   * identifiers ARE the isolation).
   * <p>
   * That a tenant is what {@code by-adapter} means here is CAMUNDA 7 knowledge, so it
   * lives in the adapter: the core answers the mode and nothing else.
   *
   * @param scoping The core's name-clash-avoidance support, or <code>null</code>
   *          (tests): version 1's behavior applies then
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @param configuredTenantId The tenant name the application configured for this
   *          workflow module, resolved over the levels it may be set at (see
   *          {@link Camunda7ConfiguredTenant}), or <code>null</code>
   * @return The tenant ID, or <code>null</code> if the mode uses none
   */
  public static String tenantIdFor(
      final NameClashAvoidanceSupport scoping,
      final String workflowModuleId,
      final String adapterId,
      final String configuredTenantId) {

    final var configured = (configuredTenantId != null) && !configuredTenantId.isBlank()
        ? configuredTenantId
        : null;
    if ((scoping != null) && (scoping.modeFor(workflowModuleId, null, adapterId) != NameClashAvoidance.BY_ADAPTER)) {
      return null;
    }
    return configured != null
        ? configured
        : workflowModuleId;

  }

  /**
   * Whether the given workflow module's identifiers are prefixed for this adapter.
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @param scoping The core's name-clash-avoidance support (may be
   *          <code>null</code>)
   * @return Whether prefixing applies
   */
  public static boolean prefixes(
      final String workflowModuleId,
      final String adapterId,
      final NameClashAvoidanceSupport scoping) {

    return (scoping != null) && (scoping.modeFor(workflowModuleId, null, adapterId) == NameClashAvoidance.USE_PREFIX);

  }

  /**
   * Rewrites the identifiers of the given model in place. A no-op unless the mode of
   * the workflow module is {@link NameClashAvoidance#USE_PREFIX}.
   *
   * @param model The model of one BPMN file
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter ID
   * @param scoping The core's name-clash-avoidance support
   */
  public static void apply(
      final BpmnModelInstance model,
      final String workflowModuleId,
      final String adapterId,
      final NameClashAvoidanceSupport scoping) {

    if (!prefixes(workflowModuleId, adapterId, scoping)) {
      return;
    }

    model
        .getModelElementsByType(Message.class)
        .forEach(message -> message.setName(
            scoping.scopedIdentifier(workflowModuleId, message.getName(), adapterId)));
    model
        .getModelElementsByType(Signal.class)
        .forEach(signal -> signal.setName(
            scoping.scopedIdentifier(workflowModuleId, signal.getName(), adapterId)));
    model
        .getModelElementsByType(Escalation.class)
        .forEach(escalation -> escalation.setEscalationCode(
            scoping.scopedIdentifier(workflowModuleId, escalation.getEscalationCode(), adapterId)));
    // an error code carries the prefix of the module whose model declares it, and so does
    // the code a TaskException raises (Camunda7WorkflowTaskBehavior). Both sides of a throw
    // and its catcher are therefore the same module, which they are: this engine resolves
    // the called process of a call activity in the tenant of the calling instance, and the
    // called element below gets this module's prefix. A call activity naming another tenant
    // is the one way out of the module, and the deployment warns about it
    model
        .getModelElementsByType(Error.class)
        .forEach(error -> error.setErrorCode(
            scoping.scopedIdentifier(workflowModuleId, error.getErrorCode(), adapterId)));

    // call activities address another process BY ID - rewrite before the ids change
    model
        .getModelElementsByType(CallActivity.class)
        .forEach(callActivity -> {
          final var calledElement = callActivity.getCalledElement();
          if ((calledElement == null) || calledElement.isBlank()) {
            return; // nothing is addressed, so there is nothing to rewrite
          }
          // an expression gets the prefix written in FRONT of it, on purpose. Camunda 7
          // evaluates this attribute as one expression, so '${processToCall}' becomes
          // 'loan-approval__${processToCall}' and the engine resolves the prefixed id of
          // whatever the expression yields. That is the id this module's processes are
          // deployed under, so a process of this module is still found.
          // Camunda7CallActivities skips an expression instead, and that difference is
          // meant: it has to ask the core whether the called process shares the workflow
          // aggregate, and the core can only answer about a process it knows by name
          callActivity.setCalledElement(
              scoping.scopedProcessId(workflowModuleId, calledElement, adapterId));
        });

    // a business rule task addresses a decision BY ID, and the decisions this module
    // deploys were renamed the same way while their files were read. An expression gets the
    // prefix written in front of it, exactly like the called element above: Camunda 7 reads
    // this attribute as one expression too, so '${whichDecision}' becomes
    // 'loan-approval__${whichDecision}' and the engine looks up the prefixed id of whatever
    // the expression yields. That is the id the decision of this module is deployed under
    model
        .getModelElementsByType(BusinessRuleTask.class)
        .forEach(businessRuleTask -> {
          final var decisionRef = businessRuleTask.getCamundaDecisionRef();
          if ((decisionRef == null) || decisionRef.isBlank()) {
            return; // nothing is addressed, so there is nothing to rewrite
          }
          businessRuleTask.setCamundaDecisionRef(
              scoping.scopedIdentifier(workflowModuleId, decisionRef, adapterId));
        });

    model
        .getModelElementsByType(Process.class)
        .forEach(process -> {
          final var scoped = scoping.scopedProcessId(workflowModuleId, process.getId(), adapterId);
          if (scoped.equals(process.getId())) {
            return;
          }
          log.debug(
              "Camunda7: BPMN process '{}' of workflow module '{}' is deployed as '{}' (name-clash "
                  + "avoidance 'use-prefix')",
              process.getId(),
              workflowModuleId,
              scoped);
          process.setId(scoped);
        });

  }

  /**
   * The identifiers of one model which the WORKFLOW MODULE scopes: message names, signal
   * names, error codes and escalation codes, as the application knows them.
   *
   * <h4>Why it costs nothing</h4>
   *
   * {@link #apply} rewrites exactly these four while it scopes a model, so the adapter
   * holds all of them at that moment, and a model the engine still holds is being read
   * anyway when somebody asks what an old version declares. Nothing is queried for it and
   * nothing is kept.
   *
   * <h4>Why no task definition is among them</h4>
   *
   * A task definition is process-local on this engine, which is the row this class' own
   * table explains: the expression is evaluated inside the process by VanillaBP's EL
   * resolver and nothing subscribes to it engine-wide. So this adapter does not scope one
   * and there is no clash to report.
   *
   * @param model The model of one BPMN file
   * @param plainForm How an identifier the model carries becomes the plain one - the
   *          identity where the model was not scoped yet, and the way back out of a prefix
   *          where the engine handed the model back
   * @return The identifiers it declares, without duplicates
   */
  public static java.util.Collection<NameClashAvoidanceSupport.ModelIdentifier> identifiersDeclaredBy(
      final BpmnModelInstance model,
      final java.util.function.UnaryOperator<String> plainForm) {

    final var declared = new java.util.LinkedHashSet<NameClashAvoidanceSupport.ModelIdentifier>();
    addWhatTheseDeclare(declared, model, Message.class, Message::getName, plainForm, ScopedIdentifierKind.MESSAGE_NAME);
    addWhatTheseDeclare(declared, model, Signal.class, Signal::getName, plainForm, ScopedIdentifierKind.SIGNAL_NAME);
    addWhatTheseDeclare(declared, model, Error.class, Error::getErrorCode, plainForm, ScopedIdentifierKind.ERROR_CODE);
    addWhatTheseDeclare(
        declared,
        model,
        Escalation.class,
        Escalation::getEscalationCode,
        plainForm,
        ScopedIdentifierKind.ESCALATION_CODE);
    return declared;

  }

  /**
   * Adds what the elements of one type declare, skipping an element which declares no name
   * at all - a message without one is legal BPMN and names nothing the engine could resolve.
   */
  private static <T extends org.camunda.bpm.model.xml.instance.ModelElementInstance> void addWhatTheseDeclare(
      final java.util.Collection<NameClashAvoidanceSupport.ModelIdentifier> declared,
      final BpmnModelInstance model,
      final Class<T> type,
      final java.util.function.Function<T, String> identifierOf,
      final java.util.function.UnaryOperator<String> plainForm,
      final ScopedIdentifierKind kind) {

    model
        .getModelElementsByType(type)
        .stream()
        .map(identifierOf)
        .filter(identifier -> (identifier != null) && !identifier.isBlank())
        .map(plainForm)
        .forEach(identifier -> declared.add(new NameClashAvoidanceSupport.ModelIdentifier(kind, identifier, null)));

  }

}
