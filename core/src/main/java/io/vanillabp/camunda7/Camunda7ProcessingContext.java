package io.vanillabp.camunda7;

import java.util.LinkedHashMap;
import java.util.Map;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;

import lombok.Getter;

/**
 * The adapter-specific processing context (the {@code PC} type parameter of
 * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService}). The deployment
 * pipeline fills one instance with all BPMN files of a workflow module. At the end it
 * hands the instance to
 * {@link io.vanillabp.camunda7.deployment.Camunda7DeploymentService#deployResources(String, Camunda7ProcessingContext)}.
 * <p>
 * It collects the parsed BPMN models and the module's decision tables. They are deployed
 * as one single Camunda 7 deployment. The module's name-clash-avoidance mode decides
 * whether that deployment goes into a tenant named after the workflow module. Models are
 * keyed by their BPMN file name. So a file with several executable processes adds its
 * model only once. The pipeline calls {@code prepareBpmn} once per executable process,
 * and all these calls share the same model of the file.
 */
@Getter
public class Camunda7ProcessingContext {

  private final String workflowModuleId;

  /**
   * The BPMN models to be deployed, keyed by their file name. A {@link LinkedHashMap}
   * keeps the order of the deployment the same on every run. This helps to reproduce a
   * deployment and to read the log.
   */
  private final Map<String, BpmnModelInstance> resourcesByFilename = new LinkedHashMap<>();

  /**
   * Starts an empty context for one workflow module, which is what the adapter deploys in
   * one call.
   *
   * @param workflowModuleId The workflow module this context collects
   */
  public Camunda7ProcessingContext(
      final String workflowModuleId) {

    this.workflowModuleId = workflowModuleId;

  }

  /**
   * The PLAIN BPMN process ids of the module's executable processes, collected in
   * {@code prepareBpmn}. They are the input of the collision check: two processes must
   * not end up under the same prefixed identifier (see decision 3 in the repository's
   * DECISIONS.md).
   */
  private final java.util.List<String> deployedProcessIds = new java.util.LinkedList<>();

  /**
   * Records an executable BPMN process of this workflow module.
   *
   * @param bpmnProcessId The plain BPMN process ID
   */
  public void recordDeployedProcess(
      final String bpmnProcessId) {

    if ((bpmnProcessId != null) && !deployedProcessIds.contains(bpmnProcessId)) {
      deployedProcessIds.add(bpmnProcessId);
    }

  }

  /**
   * Remembers the given BPMN model for deployment. Adding the same file name again (once
   * per executable process of a multi-process file) keeps a single model instance.
   *
   * @param filename The BPMN file name (used as the deployment resource name)
   * @param model The parsed BPMN model
   */
  public void addResource(
      final String filename,
      final BpmnModelInstance model) {

    resourcesByFilename.putIfAbsent(filename, model);

  }

  /**
   * The decision tables to be deployed with the module's processes, keyed by their file
   * name. They are kept as bytes, not as a model, because nothing here has to understand
   * a decision. Only one thing is rewritten: the decision id, where the module is scoped
   * by prefixes. This already happened while the file was read.
   */
  private final Map<String, byte[]> decisionsByFilename = new LinkedHashMap<>();

  /**
   * Remembers the given decision table for deployment.
   *
   * @param filename The DMN file name (used as the deployment resource name, so it keeps
   *          its extension, because Camunda 7 reads the resource type from it)
   * @param dmn The file
   */
  public void addDecision(
      final String filename,
      final byte[] dmn) {

    decisionsByFilename.putIfAbsent(filename, dmn);

  }

  /**
   * The PLAIN identifiers which the module's models declare and the workflow module
   * scopes: message names, signal names, error codes, escalation codes and the ids of the
   * decisions it brings. The adapter collects them while it reads the files, because it
   * holds them there anyway. Once the module is deployed, they go to the core. So the core
   * can name two modules which end up under one name.
   */
  private final java.util.Set<io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier> declaredIdentifiers = new java.util.LinkedHashSet<>();

  /**
   * Records what one model of this workflow module declares.
   *
   * @param identifiers What was read out of it, plain
   */
  public void recordDeclaredIdentifiers(
      final java.util.Collection<io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier> identifiers) {

    declaredIdentifiers.addAll(identifiers);

  }

  /**
   * The PLAIN decision ids of the module's decision tables, in the order the files were
   * read. The engine is asked about these ids, and they are part of what the models
   * declare.
   */
  private final java.util.Set<String> decisionIds = new java.util.LinkedHashSet<>();

  /**
   * Records the decisions one DMN file of this workflow module declares. A decision id is one
   * of the identifiers the workflow module scopes. So it is also added to what the models
   * declare.
   *
   * @param plainDecisionIds The decision ids as the application knows them
   */
  public void recordDecisionIds(
      final java.util.Collection<String> plainDecisionIds) {

    decisionIds.addAll(plainDecisionIds);
    plainDecisionIds
        .forEach(
            decisionId -> declaredIdentifiers
                .add(
                    new io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier(
                        io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ScopedIdentifierKind.DMN_DECISION_ID, decisionId, null)));

  }

  /**
   * The listeners which somebody modelled in this module's models, collected while the
   * models are read. The startup report names them one by one, see
   * {@link io.vanillabp.camunda7.wiring.Camunda7Listeners}.
   */
  @Getter
  private final java.util.List<io.vanillabp.camunda7.wiring.Camunda7Listeners.ModelledListener> modelledListeners = new java.util.LinkedList<>();

  /**
   * For each BPMN process of this module which serves its modelled listeners, the
   * property key which said so. It is kept per process, because the key resolves per
   * workflow as well as per module. The report has to name the line which a reader can
   * find in their own configuration.
   */
  @Getter
  private final Map<String, String> listenersAllowedBy = new LinkedHashMap<>();

  /**
   * Remembers that the listeners somebody modelled are served for one BPMN process.
   *
   * @param bpmnProcessId The PLAIN BPMN process id
   * @param propertyKey The key which decided it, or <code>null</code>
   */
  public void recordListenersAllowed(
      final String bpmnProcessId,
      final String propertyKey) {

    listenersAllowedBy.put(bpmnProcessId, propertyKey);

  }

  /**
   * Remembers one listener of this module which an application method serves.
   *
   * @param listener The listener
   */
  public void recordModelledListener(
      final io.vanillabp.camunda7.wiring.Camunda7Listeners.ModelledListener listener) {

    modelledListeners.add(listener);

  }

  /**
   * Whether there is anything to deploy. A module which brings no executable process is
   * legitimate, and the deployment is then skipped, not refused. The core does not even
   * create a context for such a module, and its decision tables are not deployed. The core
   * warns about that while the application starts.
   *
   * @return Whether no BPMN models were accumulated (e.g. a workflow module without any
   *         executable BPMN process).
   */
  public boolean isEmpty() {

    return resourcesByFilename.isEmpty();

  }

}
