package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;

/**
 * Aligns the embedded engine's transaction boundaries with remote BPMS (Version-1
 * behavior): service-like tasks get <code>asyncBefore</code> AND
 * <code>asyncAfter</code> - every task runs in its own job transaction, so the
 * transaction ends when the task completes. Running several service tasks within
 * one transaction is an anti-pattern (its scope would be engine-specific and
 * surprising, and a late failure would roll back completed tasks). Wait-state
 * tasks (user/receive tasks) only get <code>asyncAfter</code>. Applied at parse
 * time - the deployed BPMN XML stays untouched, the parsed process definition
 * carries the flags.
 * <p>
 * Why this adapter edits the model it deploys at all, and what bounds each edit, is decision 5 in
 * the repository's DECISIONS.md.
 * <p>
 * The engine runs this for every model it parses, whoever deployed it. Only a process a
 * <code>&#64;WorkflowService</code> class of this application claims gets anything: a process
 * this application deploys without claiming it, and a process somebody else deployed into the
 * same engine, stay as modelled, flags included. See decision 43 of {@code DECISIONS.md}.
 */
public class Camunda7AsyncBpmnParseListener extends AbstractBpmnParseListener {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
      .getLogger(Camunda7AsyncBpmnParseListener.class);

  /**
   * Delivers CANCELED lifecycle events to subscribing handlers - attached as an END
   * execution listener to service-like activities (see
   * {@link Camunda7TaskCancellationListener}).
   */
  private final Camunda7TaskCancellationListener cancellationListener;

  /**
   * Notifies optional <code>&#64;WorkflowTask</code> handlers about user-task
   * lifecycle events - attached to user tasks for the engine's global
   * CREATE and DELETE task-listener events.
   */
  private final Camunda7UserTaskEventListener userTaskEventListener;

  /**
   * Decides what a start of a workflow means and lets the application build the workflow
   * aggregate where nobody started it through VanillaBP - attached to every start event the
   * process itself holds. May be <code>null</code>: an engine built without it simply does
   * not serve such processes.
   */
  private final java.util.function.Function<io.vanillabp.spi.service.BpmsStartTrigger.Kind, org.camunda.bpm.engine.delegate.ExecutionListener> bpmsInitiatedStartListenerFactory;

  /**
   * Tells the application that a workflow ended - attached to the PROCESS
   * scope, but only where a <code>&#64;WorkflowEnded</code> method exists. May be
   * <code>null</code>: an engine built without it never notifies.
   */
  private io.vanillabp.camunda7.wiring.Camunda7WorkflowEndedListener workflowEndedListener;

  /**
   * Decides whether a process definition needs the end listener - the pair of
   * (tenant, process definition key) is what the engine reports at parse time.
   */
  private java.util.function.BiPredicate<String, String> workflowEndedHandlerExists;

  /**
   * Hands over the end-of-workflow notification.
   *
   * @param workflowEndedListener The listener to attach
   * @param workflowEndedHandlerExists Whether a process definition needs it
   */
  public void setWorkflowEnded(
      final io.vanillabp.camunda7.wiring.Camunda7WorkflowEndedListener workflowEndedListener,
      final java.util.function.BiPredicate<String, String> workflowEndedHandlerExists) {

    this.workflowEndedListener = workflowEndedListener;
    this.workflowEndedHandlerExists = workflowEndedHandlerExists;

  }

  /**
   * Whether the application claims the process the element belongs to. Without a registry,
   * which only an engine built for a test lacks, every process counts as claimed.
   *
   * @param scope The scope the engine is parsing
   * @return Whether this listener may change anything of the process
   */
  private boolean theApplicationClaims(
      final ScopeImpl scope) {

    final var registry = cancellationListener != null
        ? cancellationListener.taskRegistry()
        : null;
    if (registry == null) {
      return true;
    }
    final var processDefinition = (org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity) scope
        .getProcessDefinition();
    return registry.claimsTheProcessDefinition(processDefinition.getTenantId(), processDefinition.getKey());

  }

  @Override
  public void parseProcess(
      final Element processElement,
      final org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity processDefinition) {

    if (!theApplicationClaims(processDefinition)) {
      return;
    }
    attachCancellationToElementsCarryingAServedListener(processDefinition);
    if ((workflowEndedListener == null) || (workflowEndedHandlerExists == null)) {
      return;
    }
    // a model must not pay for a notification the application did not ask for
    if (!workflowEndedHandlerExists.test(processDefinition.getTenantId(), processDefinition.getKey())) {
      log.debug(
          "Camunda7: no end listener for process definition '{}' (tenant '{}') - nothing asked "
              + "for the end of its workflows at the moment the engine parsed it",
          processDefinition.getKey(),
          processDefinition.getTenantId());
      return;
    }
    log.debug(
        "Camunda7: end listener attached to process definition '{}' (tenant '{}')",
        processDefinition.getKey(),
        processDefinition.getTenantId());
    // 'addListener' and not the deprecated 'addExecutionListener', which does nothing but
    // delegate here, and not 'addBuiltInListener' either: a built-in listener is the
    // ENGINE's own and is the only kind which still runs when a caller skips custom
    // listeners (AbstractEventAtomicOperation asks getBuiltInListeners then). Moving the
    // notification there would make it fire on a forced deletion as well, which is a
    // behaviour change, and the cancellation and start listeners below register the same
    // way
    processDefinition
        .addListener(
            org.camunda.bpm.engine.delegate.ExecutionListener.EVENTNAME_END,
            workflowEndedListener);

  }

  /**
   * Attaches the cancellation listener to every element of the process which carries a listener
   * somebody modelled and this application serves.
   * <p>
   * Read here rather than in one of the {@code parseXxx} methods because a listener may sit on
   * any element a modeller can select, and asking the parsed process once is a shorter answer
   * than a method per element type. The engine has parsed the whole scope by the time this runs,
   * so the activities are there to be found.
   * <p>
   * An element which already carries the listener is left alone. Every service-like activity
   * does, because the transaction boundaries put it there, and a second copy would report one
   * cancellation twice.
   *
   * @param processDefinition The parsed process
   */
  private void attachCancellationToElementsCarryingAServedListener(
      final org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity processDefinition) {

    if (cancellationListener == null) {
      return;
    }
    final var workflowModuleId = cancellationListener
        .taskRegistry()
        .resolveWorkflowModuleId(processDefinition.getTenantId(), processDefinition.getKey());
    if (workflowModuleId == null) {
      // a process of another application on the same engine, or one this adapter has not wired
      // yet: nothing of it is VanillaBP's business
      return;
    }
    cancellationListener
        .taskRegistry()
        .listenersNeedingACancellation(workflowModuleId, processDefinition.getKey())
        .forEach(listener -> {
          final var activity = processDefinition.findActivity(listener.elementId());
          if (activity == null) {
            // a listener on something which is no activity of the engine, a sequence flow being
            // the case a modeller reaches: such an element is never canceled
            log.debug(
                "Camunda7: the element '{}' of process definition '{}' carries a served listener "
                    + "but is no activity, so it is never canceled and nothing is attached",
                listener.elementId(),
                processDefinition.getKey());
            return;
          }
          if (activity
              .getListeners(org.camunda.bpm.engine.delegate.ExecutionListener.EVENTNAME_END)
              .contains(cancellationListener)) {
            return;
          }
          activity
              .addListener(
                  org.camunda.bpm.engine.delegate.ExecutionListener.EVENTNAME_END,
                  cancellationListener);
        });

  }

  /**
   * Convenience constructor for an engine which reports no start the BPMS decided on. The
   * models are parsed and the listeners are attached all the same.
   *
   * @param cancellationListener What is attached to an activity whose cancelation a method
   *          wants to hear about
   * @param userTaskEventListener What is attached to a user task
   */
  public Camunda7AsyncBpmnParseListener(
      final Camunda7TaskCancellationListener cancellationListener,
      final Camunda7UserTaskEventListener userTaskEventListener) {

    this(cancellationListener, userTaskEventListener, null);

  }

  /**
   * The constructor the platform integrations use. The factory is asked per start event
   * the engine fires on its own, because the listener has to know which kind of trigger it
   * sits on and that is decided while the model is parsed.
   *
   * @param cancellationListener What is attached to an activity whose cancelation a method
   *          wants to hear about
   * @param userTaskEventListener What is attached to a user task
   * @param bpmsInitiatedStartListenerFactory Builds the listener of a start event the
   *          engine fires itself, or <code>null</code> where no method asks for one
   */
  public Camunda7AsyncBpmnParseListener(
      final Camunda7TaskCancellationListener cancellationListener,
      final Camunda7UserTaskEventListener userTaskEventListener,
      final java.util.function.Function<io.vanillabp.spi.service.BpmsStartTrigger.Kind, org.camunda.bpm.engine.delegate.ExecutionListener> bpmsInitiatedStartListenerFactory) {

    this.cancellationListener = cancellationListener;
    this.userTaskEventListener = userTaskEventListener;
    this.bpmsInitiatedStartListenerFactory = bpmsInitiatedStartListenerFactory;

  }

  @Override
  public void parseStartEvent(
      final Element startEventElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    if ((bpmsInitiatedStartListenerFactory == null) || !theApplicationClaims(scope)) {
      return;
    }
    // a start event of an event subprocess fires inside a workflow which is already
    // running and already has its aggregate, so nothing has to be built for it
    if (!Camunda7StartEvents.startsTheWorkflow(scope)) {
      return;
    }
    // EVERY start event of the process carries the listener, the plain one included: what
    // a start means is read from the state of the workflow and not from the kind of its
    // start event, see decision 28 in the repository's DECISIONS.md
    final var kind = Camunda7StartEvents.kindOf(startEventElement);
    activity
        .addListener(
            org.camunda.bpm.engine.delegate.ExecutionListener.EVENTNAME_START,
            bpmsInitiatedStartListenerFactory.apply(kind));

  }

  private void asyncBeforeAndAfter(
      final ActivityImpl activity) {

    activity.setAsyncBefore(true, true);
    activity.setAsyncAfter(true, true);
    activity.addListener(
        org.camunda.bpm.engine.delegate.ExecutionListener.EVENTNAME_END,
        cancellationListener);

  }

  private void asyncAfterOnly(
      final ActivityImpl activity) {

    activity.setAsyncBefore(false);
    activity.setAsyncAfter(true, true);

  }

  @Override
  public void parseServiceTask(
      final Element serviceTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    if (theApplicationClaims(scope)) {
      asyncBeforeAndAfter(activity);
    }

  }

  @Override
  public void parseSendTask(
      final Element sendTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    if (theApplicationClaims(scope)) {
      asyncBeforeAndAfter(activity);
    }

  }

  @Override
  public void parseBusinessRuleTask(
      final Element businessRuleTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    if (theApplicationClaims(scope)) {
      asyncBeforeAndAfter(activity);
    }

  }

  @Override
  public void parseScriptTask(
      final Element scriptTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    if (theApplicationClaims(scope)) {
      asyncBeforeAndAfter(activity);
    }

  }

  @Override
  public void parseUserTask(
      final Element userTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    if (!theApplicationClaims(scope)) {
      return;
    }
    asyncAfterOnly(activity);
    // user-task lifecycle notifications: the engine's global CREATE
    // and DELETE task-listener events reach optional @WorkflowTask handlers -
    // BUILT-IN listeners run before any modeller-defined ones (V1 semantics)
    final var taskDefinition = ((org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior) activity
        .getActivityBehavior()).getTaskDefinition();
    taskDefinition.addBuiltInTaskListener(
        org.camunda.bpm.engine.delegate.TaskListener.EVENTNAME_CREATE,
        userTaskEventListener);
    taskDefinition.addBuiltInTaskListener(
        org.camunda.bpm.engine.delegate.TaskListener.EVENTNAME_DELETE,
        userTaskEventListener);

  }

  @Override
  public void parseReceiveTask(
      final Element receiveTaskElement,
      final ScopeImpl scope,
      final ActivityImpl activity) {

    if (theApplicationClaims(scope)) {
      asyncAfterOnly(activity);
    }

  }

}
