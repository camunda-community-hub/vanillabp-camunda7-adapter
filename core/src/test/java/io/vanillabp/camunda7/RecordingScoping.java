package io.vanillabp.camunda7;

import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import io.vanillabp.integration.adapter.spi.NameClashAvoidance;
import io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport;

/**
 * The core's name-clash-avoidance support as a test sees it: it scopes the way the core
 * does, and it keeps what the adapter reports to it. A test which needs the core's verdict
 * rather than the adapter's report uses the real implementation instead, which
 * {@link io.vanillabp.camunda7.deployment.Camunda7CollidingProcessIdsTest} does.
 * <p>
 * The adapter hands its findings about names over the SPI and writes no message of its own,
 * so this is where a test about them asserts: what the adapter found, which identifier it
 * named and how sure it was. Prefixing is implemented rather than mocked, because a test
 * under {@code use-prefix} has to see the very strings the engine does.
 */
public class RecordingScoping implements NameClashAvoidanceSupport {

  private final NameClashAvoidance mode;

  /**
   * The mode of a workflow module which does not run in the one every other module of the
   * test runs in - a mixed configuration, which the modes being resolvable per workflow
   * module makes possible.
   */
  private final java.util.Map<String, NameClashAvoidance> modePerWorkflowModule = new java.util.HashMap<>();

  private final List<IdentifierHeldElsewhere> heldElsewhere = new LinkedList<>();

  private final List<Collection<IdentifierHeldElsewhere>> answersAboutWhatIsHeld = new LinkedList<>();

  private final List<ModelIdentifier> declaredByTheModels = new LinkedList<>();

  /**
   * @param mode The mode every workflow module of this test runs in
   */
  public RecordingScoping(
      final NameClashAvoidance mode) {

    this.mode = mode;

  }

  /**
   * Lets one workflow module run in a mode of its own.
   *
   * @param workflowModuleId The workflow module
   * @param workflowModuleMode The mode it runs in
   * @return This support, for chaining
   */
  public RecordingScoping withWorkflowModuleIn(
      final String workflowModuleId,
      final NameClashAvoidance workflowModuleMode) {

    modePerWorkflowModule.put(workflowModuleId, workflowModuleMode);
    return this;

  }

  /**
   * @return What the adapter reported as held by somebody else, in the order it found it
   */
  public List<IdentifierHeldElsewhere> getHeldElsewhere() {

    return heldElsewhere;

  }

  /**
   * @return One entry per answer the adapter gave about what the BPMS already holds - an
   *         empty one meaning it asked and found nothing, none at all meaning it did not ask
   */
  public List<Collection<IdentifierHeldElsewhere>> getAnswersAboutWhatIsHeld() {

    return answersAboutWhatIsHeld;

  }

  /**
   * @return What the adapter read out of the models it deployed
   */
  public List<ModelIdentifier> getDeclaredByTheModels() {

    return declaredByTheModels;

  }

  /**
   * @param kind The kind of identifier
   * @return The plain identifiers reported as held elsewhere, of that kind
   */
  public List<String> heldElsewhereOfKind(
      final ScopedIdentifierKind kind) {

    return heldElsewhere
        .stream()
        .filter(held -> held.kind() == kind)
        .map(IdentifierHeldElsewhere::plainIdentifier)
        .toList();

  }

  /**
   * @param kind The kind of identifier
   * @return The plain identifiers the models declare, of that kind
   */
  public List<String> declaredOfKind(
      final ScopedIdentifierKind kind) {

    return declaredByTheModels
        .stream()
        .filter(declared -> declared.kind() == kind)
        .map(ModelIdentifier::plainIdentifier)
        .toList();

  }

  @Override
  public NameClashAvoidance modeFor(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    return modePerWorkflowModule.getOrDefault(workflowModuleId, mode);

  }

  @Override
  public String scopedProcessId(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String adapterId) {

    return scopedIdentifier(workflowModuleId, bpmnProcessId, adapterId);

  }

  @Override
  public String scopedIdentifier(
      final String workflowModuleId,
      final String identifier,
      final String adapterId) {

    if ((identifier == null) || (modeFor(workflowModuleId, null, adapterId) != NameClashAvoidance.USE_PREFIX)) {
      return identifier;
    }
    return workflowModuleId + SEPARATOR + identifier;

  }

  @Override
  public String scopedTaskDefinition(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final String adapterId) {

    if ((taskDefinition == null) || (modeFor(workflowModuleId, null, adapterId) != NameClashAvoidance.USE_PREFIX)) {
      return taskDefinition;
    }
    return scopedIdentifier(
        workflowModuleId,
        bpmnProcessId + SEPARATOR + taskDefinition,
        adapterId);

  }

  @Override
  public String plainProcessId(
      final String workflowModuleId,
      final String scopedBpmnProcessId,
      final String adapterId) {

    return plainIdentifier(workflowModuleId, scopedBpmnProcessId, adapterId);

  }

  @Override
  public String plainIdentifier(
      final String workflowModuleId,
      final String scopedIdentifier,
      final String adapterId) {

    if ((scopedIdentifier == null) || (modeFor(workflowModuleId, null, adapterId) != NameClashAvoidance.USE_PREFIX)) {
      return scopedIdentifier;
    }
    final var prefix = workflowModuleId + SEPARATOR;
    return scopedIdentifier.startsWith(prefix)
        ? scopedIdentifier.substring(prefix.length())
        : scopedIdentifier;

  }

  @Override
  public String plainTaskDefinition(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String scopedTaskDefinition,
      final String adapterId) {

    final var withoutTheModule = plainIdentifier(workflowModuleId, scopedTaskDefinition, adapterId);
    if ((withoutTheModule == null) || (modeFor(workflowModuleId, null, adapterId) != NameClashAvoidance.USE_PREFIX)) {
      return withoutTheModule;
    }
    final var prefix = bpmnProcessId + SEPARATOR;
    return withoutTheModule.startsWith(prefix)
        ? withoutTheModule.substring(prefix.length())
        : withoutTheModule;

  }

  @Override
  public void validateNoneNameClashStrategy(
      final String adapterId,
      final String byAdapterOnlyPropertyKey) {

    // a test which configures a tenant does not test the core's verdict about it

  }

  @Override
  public void validateNativeIsolationSupported(
      final String adapterId,
      final String workflowModuleId,
      final String bpmsDescription) {

    // Camunda 7 isolates natively, so nothing here ever fails

  }

  @Override
  public void validateNoCollidingProcessIds(
      final String adapterId,
      final Collection<DeployedProcess> deployedProcesses) {

    // the collision inside one deployment is the core's own test

  }

  @Override
  public void reportIdentifiersTheBpmsAlreadyHolds(
      final String adapterId,
      final String workflowModuleId,
      final Collection<IdentifierHeldElsewhere> found) {

    answersAboutWhatIsHeld.add(found);
    heldElsewhere.addAll(found);

  }

  @Override
  public void reportIdentifiersTheModelsDeclare(
      final String adapterId,
      final String workflowModuleId,
      final Collection<ModelIdentifier> declared) {

    declaredByTheModels.addAll(declared);

  }

  @Override
  public void reportIdentifiersOfHeldVersion(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final Long activeWorkflows,
      final Collection<ModelIdentifier> declared) {

    // the core calls this one itself, never an adapter

  }

}
