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
 * What a call activity needs on Camunda 7 to continue the SAME business case. That is the
 * business key, and a note which says that this is what it does.
 * <p>
 * VanillaBP keeps the workflow aggregate's ID in Camunda's business key. Camunda 7 does not
 * pass the business key to a called process unless the model says so. So the called process
 * ran without one, and its first task failed while it resolved the aggregate. The message was
 * the persistence's own. It named neither the call activity nor the business key nor
 * VanillaBP. Camunda 8 has no such gap: {@code propagateAllParentVariables} carries the ID
 * variable into the child. So the two engines behaved differently for a model which looked
 * fine.
 * <p>
 * So the hand-over of the key is added while the BPMN is prepared for deployment. This is the
 * same pass which attaches listeners and rewrites called elements for name-clash avoidance.
 * It is added only where it is right:
 * <ul>
 * <li>the called process is named statically (an expression names a process VanillaBP does
 * not know),</li>
 * <li>the called process works on the SAME workflow aggregate as the calling one. A process
 * with an aggregate of its own must NOT get the caller's identity. It gets its own, for
 * example through a {@code @WorkflowStartedByBpms} method.</li>
 * <li>the model does not pass a business key already. What the application modelled
 * wins.</li>
 * </ul>
 * <p>
 * The same question needs a second answer while a workflow runs. So a call activity whose
 * called process continues the caller's workflow aggregate also gets a
 * <code>camunda:property</code> which says so. The multi-instance walk
 * ({@code Camunda7MultiInstances}) reads it when it leaves a called process. The iteration of a
 * caller belongs to a called process which continues the caller's business case, and to no
 * other. The answer travels with the deployed model. So an older version of a process the
 * engine still runs keeps the answer it was deployed with. That is what the workflows which
 * still stand in it need.
 * <p>
 * A call activity which names the process to call in an expression has nothing to carry such a
 * note. Instead, {@link #continuesTheCallersWorkflowAggregate(DelegateExecution,
 * DelegateExecution, Camunda7TaskRegistry)} asks the core for it while the workflow runs.
 * The multi-instance walk asks only in this case. Where the model spells the called process
 * out, the walk reads the note. The start of a called instance which has no business key of
 * its own asks as well, and it does not check how the call activity names the process. So it
 * also asks for a call activity the deployment already answered, for example where the called
 * process has a workflow aggregate of its own. The answer is the same, and it costs two
 * lookups.
 * <p>
 * Decision 5 in the repository's DECISIONS.md says why the business key is added here and not
 * left to the application, and why it is not added blindly. Decision 22 says what the note on
 * a call activity is for.
 */
@Slf4j
public final class Camunda7CallActivities {

  /**
   * What Camunda 7 evaluates to the calling instance's business key.
   */
  static final String PARENT_BUSINESS_KEY_EXPRESSION = "#{execution.processBusinessKey}";

  /**
   * The <code>camunda:property</code> this class writes onto a call activity whose called
   * process works on the workflow aggregate of the calling one. It is written where the answer
   * is known. So a call activity without it is one of three things:
   * <ul>
   * <li>the called process has an aggregate of its own,</li>
   * <li>an expression names the process to call, so it was unknown at deployment,</li>
   * <li>or this adapter did not prepare the model at all.</li>
   * </ul>
   */
  public static final String SAME_WORKFLOW_AGGREGATE_PROPERTY = "vanillabp:sameWorkflowAggregate";

  private Camunda7CallActivities() {
    // utility class
  }

  /**
   * Prepares the call activities of the given model. That is the hand-over of the business key
   * and the note about the workflow aggregate (see the class comment). It is called BEFORE
   * name-clash avoidance rewrites the called elements. So the process IDs are the ones the
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
   * Under {@code name-clash-avoidance: by-adapter} this adapter keeps workflow modules apart
   * by tenant. So a call activity which names a tenant of its own calls a process of another
   * workflow module. Everything VanillaBP scopes is scoped per module, BPMN error codes too.
   * The called process raises the code under ITS module's prefix. The boundary event of this
   * call activity waits for this module's prefix. So the error finds no catcher, and the
   * called workflow fails with an incident instead. Whoever wrote that attribute hears about
   * it while the application starts.
   * <p>
   * Only {@code camunda:calledElementTenantId} can leave the module. Without it, this engine
   * looks for the called process in the tenant of the CALLING instance, which is this module.
   * And under {@code use-prefix} a static {@code calledElement} carries this module's prefix
   * anyway. An id given as an expression is the application's own string and can name
   * anything. No deployment can resolve it, so nothing is said about one.
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
   * The ID of the process a call activity belongs to. It may sit in a subprocess, so this
   * walks up the parents.
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
   * Whether the model passes a business key to the called process already. An application
   * which modelled it keeps its own expression.
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
   * workflow aggregate. It is read from the note this class wrote at deployment.
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
   * Whether the model names the process a call activity calls. Every answer written at
   * deployment needs that.
   * <p>
   * An expression names the process while the workflow runs. So the deployment reads a string
   * nobody can resolve yet, and says nothing about such a call activity. The same holds for a
   * call activity which names no called process at all. The engine refuses such a model on
   * its own.
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
   * {@link #prepareCallActivities} asks the core the same question while a model is prepared.
   * Here it is asked a second time, about the same pair of processes. It is mainly for the
   * call activity the deployment could say nothing about: the process behind an expression
   * is chosen per instance, so there was nothing to write the answer onto. The start of a
   * called instance without a business key asks here for any call activity, see the class
   * comment. The core answers
   * from the declarations of the workflow services, which it read while the application
   * started. So asking again costs two lookups, and the answer comes from the same source.
   * <p>
   * A caller of another workflow module is no caller whose business case is continued. A
   * workflow aggregate is shared within one module only. A process which this application did
   * not deploy is no such caller either. Nor is a pair which nobody handed an answer over for
   * (tests).
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
      // the core answers about a pair of processes of ONE module. A call across modules is a
      // call between two business cases, whatever either side declares
      return false;
    }
    return taskRegistry
        .workflowsShareTheWorkflowAggregate(
            called.get().workflowModuleId(),
            calling.get().bpmnProcessId(),
            called.get().bpmnProcessId());

  }

  /**
   * The workflow module and the plain BPMN process id behind a running execution. The core
   * knows a process under these two. Empty where this application deployed no such process. An
   * embedded engine may hold the definitions of another application. And a definition of a
   * release this one no longer carries stays in the engine as well.
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
