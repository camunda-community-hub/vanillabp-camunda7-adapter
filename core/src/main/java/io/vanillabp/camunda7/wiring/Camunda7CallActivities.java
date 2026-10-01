package io.vanillabp.camunda7.wiring;

import java.util.stream.Stream;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.CallActivity;
import org.camunda.bpm.model.bpmn.instance.ExtensionElements;
import org.camunda.bpm.model.bpmn.instance.FlowElement;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaIn;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperties;
import org.camunda.bpm.model.bpmn.instance.camunda.CamundaProperty;

import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring;
import lombok.extern.slf4j.Slf4j;

/**
 * What a call activity needs on Camunda 7 to continue the SAME business case: the
 * business key, and a note saying that this is what it does.
 * <p>
 * VanillaBP keeps the workflow aggregate's ID in Camunda's business key. Camunda 7
 * does not pass the business key to a called process unless the model says so, so the
 * called process ran without one and the first task of it failed while resolving the
 * aggregate - with the persistence's own message, mentioning neither the call
 * activity nor the business key nor VanillaBP. Camunda 8 has no such gap
 * ({@code propagateAllParentVariables} carries the ID variable into the child), so
 * one engine behaved differently from the other for a model which looked fine.
 * <p>
 * The propagation is therefore injected while the BPMN is prepared for deployment -
 * the same pass which attaches listeners and rewrites called elements for
 * name-clash avoidance. It is injected only where it is right:
 * <ul>
 * <li>the called process is addressed statically (an expression addresses a process
 * VanillaBP does not know),</li>
 * <li>the called process works on the SAME workflow aggregate as the calling one
 * (a process with an aggregate of its own must NOT be handed the caller's identity -
 * it gets its own, e.g. through a {@code @WorkflowStartedByBpms} method),</li>
 * <li>the model does not pass a business key already - what the application
 * modelled wins.</li>
 * </ul>
 * <p>
 * The same question needs a second answer while a workflow runs, so a call activity whose
 * called process continues the caller's workflow aggregate also gets a
 * <code>camunda:property</code> saying so. The multi-instance walk
 * ({@code Camunda7MultiInstances}) reads it when it leaves a called process: the iteration
 * of a caller belongs to a called process which continues the caller's business case, and
 * to no other. The answer travels with the model that was deployed, so an older version of
 * a process the engine still runs keeps the answer it was deployed with, which is what the
 * workflows still standing in it need.
 * <p>
 * A call activity naming the process to call in an expression has nothing to carry such a
 * note, and {@link #continuesTheCallersWorkflowAggregate(DelegateExecution,
 * DelegateExecution, Camunda7TaskRegistry)} asks the core for it while the workflow runs
 * instead. That is the only case where it is asked twice: where the model spells the called
 * process out, the answer of the deployment stands and nobody asks again.
 * <p>
 * Why the business key is injected here rather than left to the application, and why it is not
 * injected blindly, is decision 5 in the repository's DECISIONS.md. What the note on a call
 * activity is for is decision 22.
 */
@Slf4j
public final class Camunda7CallActivities {

  /**
   * What Camunda 7 evaluates to the calling instance's business key.
   */
  static final String PARENT_BUSINESS_KEY_EXPRESSION = "#{execution.processBusinessKey}";

  /**
   * The <code>camunda:property</code> this class writes onto a call activity whose called
   * process works on the workflow aggregate of the calling one. It is written where the
   * answer is known, so a call activity without it is one of three things: the called
   * process has an aggregate of its own, the process to call is named by an expression and
   * was unknown while the model was deployed, or the model was not prepared by this adapter
   * at all.
   */
  public static final String SAME_WORKFLOW_AGGREGATE_PROPERTY = "vanillabp:sameWorkflowAggregate";

  private Camunda7CallActivities() {
    // utility class
  }

  /**
   * Prepares the call activities of the given model: the business-key propagation and the
   * note about the workflow aggregate (see the class comment). Called BEFORE name-clash
   * avoidance rewrites the called elements, so the process IDs are the ones the
   * application knows.
   *
   * @param model The BPMN model of one file
   * @param workflowModuleId The workflow module the file belongs to
   * @param workflowTaskWiring The core, asked which processes share an aggregate
   */
  public static void prepareCallActivities(
      final BpmnModelInstance model,
      final String workflowModuleId,
      final WorkflowTaskWiring workflowTaskWiring) {

    model
        .getModelElementsByType(CallActivity.class)
        .forEach(callActivity -> {
          if (!theModelSaysWhichProcessIsCalled(callActivity)) {
            return;
          }
          final var calledElement = callActivity.getCalledElement();
          final var callingProcessId = processIdOf(callActivity);
          if (callingProcessId == null) {
            return;
          }
          if (!workflowTaskWiring
              .workflowsShareTheWorkflowAggregate(workflowModuleId, callingProcessId, calledElement)) {
            return;
          }
          noteTheSharedWorkflowAggregate(model, callActivity);
          if (passesBusinessKeyAlready(callActivity)) {
            return;
          }
          final var camundaIn = model.newInstance(CamundaIn.class);
          camundaIn.setCamundaBusinessKey(PARENT_BUSINESS_KEY_EXPRESSION);
          extensionElementsOf(model, callActivity).addChildElement(camundaIn);
          log.debug(
              "Camunda7: call activity '{}' of BPMN process '{}' (workflow module '{}') passes the "
                  + "business key to '{}' - both work on the same workflow aggregate",
              callActivity.getId(),
              callingProcessId,
              workflowModuleId,
              calledElement);
        });

  }

  /**
   * The call activities of one BPMN process which send the engine looking for the called
   * process in ANOTHER tenant, with the tenant each of them names.
   * <p>
   * A tenant is how this adapter keeps workflow modules apart under
   * {@code name-clash-avoidance: by-adapter}, so a call activity naming a tenant of its own
   * calls a process of another workflow module. Everything VanillaBP scopes is scoped per
   * module, a BPMN error code among it: the called process raises the code under ITS
   * module's prefix and the boundary event of this call activity waits for this module's, so
   * the error finds no catcher and the called workflow fails with an incident instead.
   * Whoever wrote that attribute hears it while the application starts.
   * <p>
   * Only {@code camunda:calledElementTenantId} can leave the module. Without it this engine
   * resolves the called process in the tenant of the CALLING instance, which is this module,
   * and a static {@code calledElement} carries this module's prefix under
   * {@code use-prefix} anyway. An id given as an expression is the application's own string
   * and can name anything, and no deployment can resolve it, so nothing is said about one.
   *
   * @param model The BPMN model
   * @param bpmnProcessId The process' ID as the model knows it at this point
   * @param ownTenantId The tenant this workflow module is deployed to, or
   *          <code>null</code> where it uses none
   * @return The tenant per call activity ID, in model order, empty where every call
   *         activity stays in this module
   */
  public static java.util.Map<String, String> callActivitiesLeavingTheWorkflowModule(
      final BpmnModelInstance model,
      final String bpmnProcessId,
      final String ownTenantId) {

    final var leaving = new java.util.LinkedHashMap<String, String>();
    model
        .getModelElementsByType(CallActivity.class)
        .stream()
        .filter(callActivity -> bpmnProcessId.equals(processIdOf(callActivity)))
        .forEach(callActivity -> {
          final var tenantId = callActivity.getCamundaCalledElementTenantId();
          if ((tenantId == null) || tenantId.isBlank() || tenantId.equals(ownTenantId)) {
            return;
          }
          leaving.put(callActivity.getId(), tenantId);
        });
    return leaving;

  }

  /**
   * The ID of the process a call activity belongs to (it may sit in a subprocess, so
   * the parents are walked).
   */
  private static String processIdOf(
      final CallActivity callActivity) {

    var current = callActivity.getParentElement();
    while (current != null) {
      if (current instanceof Process process) {
        return process.getId();
      }
      current = current.getParentElement();
    }
    return null;

  }

  /**
   * Whether the model passes a business key to the called process already - an
   * application which modelled it keeps its own expression.
   */
  private static boolean passesBusinessKeyAlready(
      final CallActivity callActivity) {

    final var extensionElements = callActivity.getExtensionElements();
    if (extensionElements == null) {
      return false;
    }
    return extensionElements
        .getElementsQuery()
        .filterByType(CamundaIn.class)
        .list()
        .stream()
        .anyMatch(camundaIn -> (camundaIn.getCamundaBusinessKey() != null) && !camundaIn
            .getCamundaBusinessKey()
            .isBlank());

  }

  /**
   * Whether the called process of the given call activity continues the calling process'
   * workflow aggregate, read from the note this class wrote while the model was deployed.
   *
   * @param element The element an execution stands on, of any kind and possibly
   *          <code>null</code>
   * @return Whether this is a call activity carrying the note
   */
  public static boolean continuesTheCallersWorkflowAggregate(
      final FlowElement element) {

    if (!(element instanceof final CallActivity callActivity)) {
      return false;
    }
    return propertiesOf(callActivity)
        .anyMatch(property -> SAME_WORKFLOW_AGGREGATE_PROPERTY.equals(property.getCamundaName()));

  }

  /**
   * Whether the model names the process a call activity calls, which is the condition for
   * every answer written while the model is deployed.
   * <p>
   * An expression names the process while the workflow runs, so the deployment reads a
   * string nobody can resolve yet and says nothing about such a call activity. The same
   * holds for a call activity which names no called process at all, which is a model the
   * engine refuses on its own.
   *
   * @param element The element an execution stands on, of any kind and possibly
   *          <code>null</code>
   * @return Whether this is a call activity whose called process the model spells out
   */
  public static boolean theModelSaysWhichProcessIsCalled(
      final FlowElement element) {

    if (!(element instanceof final CallActivity callActivity)) {
      return false;
    }
    final var calledElement = callActivity.getCalledElement();
    return (calledElement != null) && !calledElement.isBlank() && !calledElement.contains("${") && !calledElement
        .contains("#{");

  }

  /**
   * Whether the called process of a running instance continues the workflow aggregate of
   * the process which called it, asked while the workflow runs.
   * <p>
   * This is the question {@link #prepareCallActivities} asks the core while a model is
   * prepared, asked a second time and about the same pair of processes. It is for the one
   * call activity the deployment could say nothing about: the process behind an expression
   * is chosen per instance, so there was nothing to write the answer onto. The core answers
   * from the declarations of the workflow services, which it read while the application
   * started, so asking again costs two lookups and no new source of the answer.
   * <p>
   * A caller of another workflow module is no caller to continue a business case of: a
   * workflow aggregate is shared within one module. A process which this application did not
   * deploy is none either, and so is a pair nobody handed an answer over for (tests).
   *
   * @param calledExecution An execution of the called instance
   * @param callingExecution The execution of the call activity which started it
   * @param taskRegistry Where the core's answer is reached, possibly <code>null</code>
   * @return Whether both processes serve one workflow aggregate
   */
  public static boolean continuesTheCallersWorkflowAggregate(
      final DelegateExecution calledExecution,
      final DelegateExecution callingExecution,
      final Camunda7TaskRegistry taskRegistry) {

    if ((calledExecution == null) || (callingExecution == null) || (taskRegistry == null)) {
      return false;
    }
    final var called = workflowProcessOf(calledExecution, taskRegistry);
    final var calling = workflowProcessOf(callingExecution, taskRegistry);
    if (called.isEmpty() || calling.isEmpty()) {
      return false;
    }
    if (!called.get().workflowModuleId().equals(calling.get().workflowModuleId())) {
      // the core answers about a pair of processes of ONE module, and a call across modules
      // is a call between two business cases whatever either side declares
      return false;
    }
    return taskRegistry
        .workflowsShareTheWorkflowAggregate(
            called.get().workflowModuleId(),
            calling.get().bpmnProcessId(),
            called.get().bpmnProcessId());

  }

  /**
   * The workflow module and the plain BPMN process id behind a running execution, which is
   * what the core knows a process under. Empty where this application deployed no such
   * process: an embedded engine may hold the definitions of another application, and a
   * definition of a release this one no longer carries stays in the engine as well.
   */
  private static java.util.Optional<Camunda7TaskRegistry.WorkflowProcess> workflowProcessOf(
      final DelegateExecution execution,
      final Camunda7TaskRegistry taskRegistry) {

    final var definition = execution
        .getProcessEngineServices()
        .getRepositoryService()
        .getProcessDefinition(execution.getProcessDefinitionId());
    return definition == null
        ? java.util.Optional.empty()
        : taskRegistry.resolve(execution.getTenantId(), definition.getKey());

  }

  /**
   * Writes the note the multi-instance walk reads. A model prepared twice carries it once.
   */
  private static void noteTheSharedWorkflowAggregate(
      final BpmnModelInstance model,
      final CallActivity callActivity) {

    if (continuesTheCallersWorkflowAggregate(callActivity)) {
      return;
    }
    final var property = model.newInstance(CamundaProperty.class);
    property.setCamundaName(SAME_WORKFLOW_AGGREGATE_PROPERTY);
    property.setCamundaValue(Boolean.TRUE.toString());
    propertiesContainerOf(model, callActivity).getCamundaProperties().add(property);

  }

  private static Stream<CamundaProperty> propertiesOf(
      final CallActivity callActivity) {

    final var extensionElements = callActivity.getExtensionElements();
    if (extensionElements == null) {
      return Stream.empty();
    }
    return extensionElements
        .getElementsQuery()
        .filterByType(CamundaProperties.class)
        .list()
        .stream()
        .flatMap(properties -> properties.getCamundaProperties().stream());

  }

  private static CamundaProperties propertiesContainerOf(
      final BpmnModelInstance model,
      final CallActivity callActivity) {

    final var extensionElements = extensionElementsOf(model, callActivity);
    final var existing = extensionElements
        .getElementsQuery()
        .filterByType(CamundaProperties.class)
        .list();
    if (!existing.isEmpty()) {
      return existing.getFirst();
    }
    final var properties = model.newInstance(CamundaProperties.class);
    extensionElements.addChildElement(properties);
    return properties;

  }

  private static ExtensionElements extensionElementsOf(
      final BpmnModelInstance model,
      final CallActivity callActivity) {

    final var existing = callActivity.getExtensionElements();
    if (existing != null) {
      return existing;
    }
    final var extensionElements = model.newInstance(ExtensionElements.class);
    callActivity.setExtensionElements(extensionElements);
    return extensionElements;

  }

}
