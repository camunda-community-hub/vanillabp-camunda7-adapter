package io.vanillabp.camunda7.wiring;

import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;

/**
 * Makes the transaction boundaries of the embedded engine work like those of a remote BPMS, as
 * in Version 1. Service-like tasks get <code>asyncBefore</code> AND <code>asyncAfter</code>.
 * So every task runs in its own job transaction, and the transaction ends when the task
 * completes. Running several service tasks in one transaction is an anti-pattern. Its scope
 * would depend on the engine and surprise people. And a late failure would roll back tasks
 * which had already completed. Wait-state tasks (user and receive tasks) only get
 * <code>asyncAfter</code>. This happens at parse time. The deployed BPMN XML stays as it is,
 * only the parsed process definition carries the flags.
 * <p>
 * Decision 5 in the repository's DECISIONS.md says why this adapter changes the model it deploys
 * at all, and what limits each change.
 * <p>
 * The engine runs this for every model it parses, whoever deployed it. Only a process which a
 * <code>&#64;WorkflowService</code> class of this application claims gets anything. Two kinds of
 * process stay as modelled, flags included: a process this application deploys without claiming
 * it, and a process somebody else deployed into the same engine. See decision 43 of
 * {@code DECISIONS.md}.
 */
public class Camunda7AsyncBpmnParseListener extends AbstractBpmnParseListener {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
      .getLogger(Camunda7AsyncBpmnParseListener.class);

  /**
   * Delivers CANCELED lifecycle events to the handlers which subscribe to them. It is attached
   * as an END execution listener to service-like activities (see
   * {@link Camunda7TaskCancellationListener}).
   */
  private final Camunda7TaskCancellationListener cancellationListener;

  /**
   * Tells optional <code>&#64;WorkflowTask</code> handlers about the lifecycle events of user
   * tasks. It is attached to user tasks for the engine's CREATE and DELETE task-listener
   * events.
   */
  private final Camunda7UserTaskEventListener userTaskEventListener;

  /**
   * Decides what a start of a workflow means. Where nobody started the workflow through
   * VanillaBP, it lets the application build the workflow aggregate. It is attached to every
   * start event of the process itself. May be <code>null</code>. An engine built without it
   * does not serve such processes.
   */
  private final java.util.function.Function<io.vanillabp.spi.service.BpmsStartTrigger.Kind, org.camunda.bpm.engine.delegate.ExecutionListener> bpmsInitiatedStartListenerFactory;

  /**
   * Tells the application that a workflow ended. It is attached to the PROCESS scope, but only
   * where a <code>&#64;WorkflowEnded</code> method exists. May be <code>null</code>. An engine
   * built without it never tells.
   */
  private io.vanillabp.camunda7.wiring.Camunda7WorkflowEndedListener workflowEndedListener;

  /**
   * Decides whether a process definition needs the end listener. It takes the tenant and the
   * process definition key, because that pair is what the engine reports at parse time.
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
   * Whether the application claims the process the element belongs to. Only an engine built for
   * a test has no registry. Then every process counts as claimed.
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
    // 'addListener', not the deprecated 'addExecutionListener', which only delegates here.
    // And not 'addBuiltInListener' either. A built-in listener belongs to the ENGINE. It is
    // the only kind which still runs when a caller skips custom listeners
    // (AbstractEventAtomicOperation then asks getBuiltInListeners). If the notification moved
    // there, it would fire on a forced deletion as well. That would change the behaviour. The
    // cancellation and start listeners below register the same way
    processDefinition
        .addListener(
            org.camunda.bpm.engine.delegate.ExecutionListener.EVENTNAME_END,
            workflowEndedListener);

  }

  /**
   * Attaches the cancellation listener to every element of the process which carries a listener
   * somebody modelled and this application serves.
   * <p>
   * This is read here and not in one of the {@code parseXxx} methods. A listener may sit on any
   * element a modeller can select. Asking the parsed process once is shorter than a method per
   * element type. When this runs, the engine has parsed the whole scope, so the activities are
   * there to be found.
   * <p>
   * An element which already carries the listener is left alone. Every service-like activity
   * carries it, because the transaction boundaries put it there. A second copy would report one
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
      // yet. None of it is VanillaBP's business
      return;
    }
    cancellationListener
        .taskRegistry()
        .listenersNeedingACancellation(workflowModuleId, processDefinition.getKey())
        .forEach(listener -> {
          final var activity = processDefinition.findActivity(listener.elementId());
          if (activity == null) {
            // a listener on something which is no activity of the engine. A modeller gets there
            // with a sequence flow. Such an element is never canceled
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
   * The constructor the platform integrations use. The factory is asked once per start event
   * the engine fires on its own. The listener has to know which kind of trigger it sits on, and
   * that is decided while the model is parsed.
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
    // running and already has its aggregate. So nothing has to be built for it
    if (!Camunda7StartEvents.startsTheWorkflow(scope)) {
      return;
    }
    // EVERY start event of the process carries the listener, the plain one included. What a
    // start means is read from the state of the workflow, not from the kind of its start
    // event. See decision 28 in the repository's DECISIONS.md
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
    // lifecycle notifications of user tasks. The engine's CREATE and DELETE task-listener
    // events reach optional @WorkflowTask handlers. BUILT-IN listeners run before the ones a
    // modeller defined, as in V1
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
