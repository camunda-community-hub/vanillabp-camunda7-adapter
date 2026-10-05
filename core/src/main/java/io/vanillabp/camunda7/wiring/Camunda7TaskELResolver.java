package io.vanillabp.camunda7.wiring;

import java.beans.FeatureDescriptor;
import java.util.Iterator;

import org.camunda.bpm.engine.ProcessEngineException;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.impl.juel.jakarta.el.ELContext;
import org.camunda.bpm.impl.juel.jakarta.el.ELResolver;

import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;

/**
 * Resolves top-level EL names of BPMN expressions against the WIRED TASKS of VanillaBP: a
 * name matching a task (by the current BPMN element or by task definition) yields the
 * handler - <code>camunda:expression</code> tasks run the handler during evaluation,
 * <code>camunda:delegateExpression</code> tasks receive the
 * {@link Camunda7WorkflowTaskBehavior} (so <code>&#64;TaskId</code> tasks can stay open).
 * Every other name falls through to the engine's remaining resolvers, which is where the
 * process variables live.
 * <p>
 * <b>Attributes of the workflow aggregate are a MIGRATION FALLBACK here</b>, and it will be
 * removed. Reading the aggregate live made a model reading <code>${riskAcceptable}</code>
 * work on Camunda 7 and fail on every remote BPMS - the opposite of what
 * {@code @SyncWithBPMS} is for. The values are pushed as process variables at every sync
 * point now, so the engine resolves them itself (see decision 1 in the repository's
 * DECISIONS.md).
 * <p>
 * The fallback exists because an application upgrading to this version has workflows
 * RUNNING which carry no such variables yet, and because version 1 resolved attributes
 * without a getter as well (the sync model reads getters only). So the order is reversed
 * compared to before: a VARIABLE of that name wins, and only where the engine has none
 * does the resolver still read the aggregate - saying so once per name, with the way out.
 * The startup check of the deployment service reports the same names while the application
 * boots.
 */
public class Camunda7TaskELResolver extends ELResolver {

  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory
      .getLogger(Camunda7TaskELResolver.class);

  /**
   * Which live reads were reported already (workflow module, process and name) - the
   * migration fallback names a configuration gap, and one line per evaluation
   * would bury it.
   */
  private final java.util.Set<String> liveReadsReported = java.util.concurrent.ConcurrentHashMap
      .newKeySet();

  /**
   * How many workflow INSTANCES the fallback served since this application started -
   * which is what has to be known before the fallback can be removed, and what the
   * report of names cannot answer. A name says a model reads something unshared; an
   * instance says a workflow still depends on the live read.
   * <p>
   * The number falls on its own without anything being done to it: an instance stops
   * needing the fallback as soon as it reaches a sync point, because the adapter writes
   * the shared values at every point it talks to the engine. So a shrinking count is the
   * signal, and a count which stays is a workflow parked in a wait state.
   * <p>
   * Bounded like {@link #liveReadsReported}, and for the same reason: losing an entry
   * costs one instance counted twice, and nothing durable belongs in an expression
   * evaluation.
   */
  private final java.util.Set<String> liveReadInstances = java.util.concurrent.ConcurrentHashMap
      .newKeySet();

  /**
   * Up to this many instances are remembered. Beyond it the count is reported as "at
   * least", because an exact number is worth less than a bounded resolver.
   */
  private static final int MAX_REMEMBERED_INSTANCES = 10_000;

  /**
   * How often the count of instances still on the fallback is reported at most. The
   * same interval the platform uses for its eviction-pressure warning, and for the same
   * reason: a number which changes slowly is worth a line now and then, never one per
   * evaluation.
   */
  private static final java.time.Duration USAGE_REPORT_INTERVAL = java.time.Duration.ofHours(1);

  private volatile long usageReportedAtMillis;

  private volatile int usageReportedAtCount;

  private final Camunda7TaskRegistry taskRegistry;

  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * One resolver for the whole engine. It sits in front of everything else the engine
   * would resolve a name with, which is why it has to hand back what is not its own.
   *
   * @param taskRegistry What an EL name evaluated by the engine is looked up in
   * @param workflowTaskInvoker Where the application's method is called, and what is asked
   *          whether a name is an attribute of the workflow aggregate instead
   */
  public Camunda7TaskELResolver(
      final Camunda7TaskRegistry taskRegistry,
      final WorkflowTaskInvoker workflowTaskInvoker) {

    this.taskRegistry = taskRegistry;
    this.workflowTaskInvoker = workflowTaskInvoker;

  }

  // both calls into the deprecated half of WorkflowTaskInvoker are the migration
  // fallback, and this resolver is the only production caller of it: the
  // 'removal' lint is mandatory and @Deprecated on a caller does not silence it, so
  // without this the adapter reported the same two warnings in every build. Goes away
  // together with the fallback.
  @SuppressWarnings("removal")
  @Override
  public Object getValue(
      final ELContext context,
      final Object base,
      final Object property) {

    // only top-level names; attribute chains are served by subsequent resolvers
    if (base != null) {
      return null;
    }

    final var execution = (ExecutionEntity) context
        .getELResolver()
        .getValue(context, null, "execution");
    // the built-in variable-scope resolver marks 'execution' as resolved - reset
    // so OUR verdict decides whether subsequent resolvers get their chance
    context.setPropertyResolved(false);
    if (execution == null) {
      return null;
    }

    // Without a tenant (prefixed identifiers) the registry answers which
    // workflow module a process definition key belongs to, and its plain id
    final var scopedBpmnProcessId = execution
        .getProcessDefinition()
        .getKey();
    final var workflowModuleId = taskRegistry
        .resolveWorkflowModuleId(execution.getTenantId(), scopedBpmnProcessId);
    final var bpmnProcessId = taskRegistry.plainBpmnProcessId(workflowModuleId, scopedBpmnProcessId);
    final var currentElement = execution.getBpmnModelElementInstance();
    final var currentElementId = currentElement != null
        ? currentElement.getId()
        : null;
    final var propertyName = property.toString();

    final var connectable = taskRegistry
        .resolve(
            workflowModuleId,
            scopedBpmnProcessId,
            currentElementId,
            propertyName)
        // user-task connectables are served by task listeners, never
        // by EL names - a formKey colliding with an aggregate attribute must not
        // shadow the attribute
        .filter(candidate -> candidate.type() != Camunda7TaskConnectable.Type.USER_TASK)
        // a modelled listener is resolved here like a task is: the engine evaluates the
        // listener's own expression, so the NAME route is the normal one for it

        // a connectable matched by ELEMENT catches EVERY name evaluated while an
        // execution sits at its activity, including the condition of a conditional event
        // or a gateway. Only a name which IS a task definition of this process may run a
        // handler; anything else evaluated at that element is a variable and belongs to
        // the engine's resolvers
        .filter(
            candidate -> taskRegistry
                .isTaskDefinitionName(workflowModuleId, scopedBpmnProcessId, propertyName) || !workflowTaskInvoker
                    .workflowAggregateHasProperty(workflowModuleId, bpmnProcessId, propertyName));
    if (connectable.isPresent()) {
      context.setPropertyResolved(true);
      final var behavior = new Camunda7WorkflowTaskBehavior(
          connectable.get(), workflowTaskInvoker, taskRegistry.getScoping(), taskRegistry.getAdapterId(), taskRegistry);
      if (connectable.get().type() == Camunda7TaskConnectable.Type.DELEGATE_EXPRESSION) {
        // the engine treats the resolved object as the task's activity behavior
        return behavior;
      }
      if (connectable.get().type() == Camunda7TaskConnectable.Type.EXECUTION_LISTENER_DELEGATE_EXPRESSION) {
        // a delegate expression of a LISTENER has to yield a listener object: the engine is
        // inside a transition, and an activity behavior would try to leave the element
        return new Camunda7ListenerNotification(behavior, connectable.get());
      }
      if (connectable.get().type() == Camunda7TaskConnectable.Type.EXECUTION_LISTENER_EXPRESSION) {
        // camunda:expression of a listener - the handler runs while the expression
        // evaluates, and the listener is done when it returns
        Camunda7ListenerNotification.invoke(behavior, connectable.get(), execution);
        return null;
      }
      // camunda:expression - the handler runs while the expression evaluates;
      // the task completes when the evaluation returns
      final var outcome = behavior.invokeHandler(execution);
      if (outcome.kind() == WorkflowTaskOutcome.Kind.COMPLETION_PENDING) {
        // the backstop: the deployment check reports this while the
        // application starts, so reaching this line means the model got to the
        // engine another way
        throw new ProcessEngineException(
            Camunda7TaskConnectable.asynchronousTaskWiredByExpression(
                propertyName, bpmnProcessId, workflowModuleId));
      }
      return null;
    }

    // no wired task: the values shared by the aggregate are process variables, so
    // the engine's own resolvers answer the name - unless this workflow still
    // runs without them, which is what the migration fallback below is for
    if (execution.hasVariable(propertyName)) {
      return null;
    }
    final var businessKey = execution.getBusinessKey();
    if (businessKey == null) {
      return null;
    }
    final var value = workflowTaskInvoker.resolveWorkflowAggregateProperty(
        workflowModuleId,
        bpmnProcessId,
        businessKey,
        propertyName);
    if (value == null) {
      return null;
    }
    rememberLiveReadInstance(execution.getProcessInstanceId());
    reportLiveReadUsage(workflowModuleId);
    reportLiveRead(workflowModuleId, bpmnProcessId, propertyName);
    context.setPropertyResolved(true);
    return value;

  }

  /**
   * Remembers that one workflow instance was served by the fallback.
   *
   * @param processInstanceId The instance, may be <code>null</code> for an evaluation
   *          outside an instance
   */
  private void rememberLiveReadInstance(
      final String processInstanceId) {

    if (processInstanceId == null) {
      return;
    }
    if (liveReadInstances.size() >= MAX_REMEMBERED_INSTANCES) {
      return;
    }
    liveReadInstances.add(processInstanceId);

  }

  /**
   * Says at most once an hour how many workflow instances are still being answered by
   * the fallback, and only while that number keeps growing.
   * <p>
   * This is the number the removal needs, and it is not the one the startup check
   * reports. That check names the EXPRESSIONS which read something unshared, which is a
   * modelling gap and stays the same however many workflows there are. This says how
   * many workflows still depend on the live read, and it falls on its own: an instance
   * stops needing the fallback the moment it reaches a sync point, because the adapter
   * writes the shared values at every point it talks to the engine. A count which stops
   * growing is the signal that the upgrade window is closing; one which keeps growing
   * means new workflows are being started into the gap, which is a defect in the sharing
   * rather than a leftover of the upgrade.
   *
   * @param workflowModuleId The workflow module whose expression was answered
   */
  private void reportLiveReadUsage(
      final String workflowModuleId) {

    final var count = liveReadInstances.size();
    if (count == usageReportedAtCount) {
      return;
    }
    final var now = System.currentTimeMillis();
    if ((usageReportedAtMillis != 0) && ((now - usageReportedAtMillis) < USAGE_REPORT_INTERVAL.toMillis())) {
      return;
    }
    usageReportedAtMillis = now;
    usageReportedAtCount = count;
    log.info(
        """
            Camunda7[{}]: {}{} workflow(s) of workflow module '{}' were answered by the MIGRATION \
            FALLBACK since this application started - they carry no process variable for an \
            attribute their model reads, which is how workflows started under VanillaBP 1 arrive \
            here. The fallback will be removed, so this number is the one to watch: it falls on \
            its own, because a workflow stops needing the fallback as soon as it reaches a point \
            where this adapter writes the shared values. A number which keeps growing means new \
            workflows run into the same gap, and then the model reads something which is not \
            shared - the startup names those expressions. Ask the VanillaBP team if you need a \
            date for the removal.""",
        taskRegistry.getAdapterId(),
        usage().atLeast()
            ? "at least "
            : "",
        count,
        workflowModuleId);

  }

  /**
   * The current usage, extracted so the message and {@link #liveReadUsage()} cannot
   * disagree about what "at least" means.
   *
   * @return The usage
   */
  private LiveReadUsage usage() {

    return new LiveReadUsage(liveReadInstances.size(), liveReadInstances.size() >= MAX_REMEMBERED_INSTANCES);

  }

  /**
   * How many workflow instances the migration fallback served since this application
   * started, and whether that is an exact number or a lower bound.
   * <p>
   * Read by whoever wants to report it - the resolver itself says nothing periodically,
   * because an expression evaluation is the wrong place to own a schedule.
   *
   * @return The instances, and whether the memory ran full
   */
  public LiveReadUsage liveReadUsage() {

    return usage();

  }

  /**
   * How much of the Camunda 7 migration fallback is still in use.
   *
   * @param instances The workflow instances served since the application started
   * @param atLeast Whether more were served than could be remembered
   */
  public record LiveReadUsage(
                              int instances,
                              boolean atLeast) {

  }

  /**
   * Says ONCE per workflow module, process and name that an expression was answered by
   * reading the aggregate instead of a process variable - the migration fallback which
   * will be removed.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param propertyName The attribute read
   */
  private void reportLiveRead(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String propertyName) {

    if (!liveReadsReported.add("%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, propertyName))) {
      return;
    }
    log.warn(
        """
            Camunda7[{}]: the expression '{}' of BPMN process '{}' (workflow module '{}') was \
            answered by reading the workflow aggregate directly, because the workflow carries no \
            process variable of that name. That is the MIGRATION FALLBACK of VanillaBP 2.0, which \
            exists for the upgrade and WILL BE REMOVED - ask the VanillaBP team if you need a date \
            for that. A workflow started with this version writes the variable at every sync \
            point. To become independent of it: make the attribute a readable getter \
            (the values shared with a BPMS are read from getters, never from fields) and make sure \
            it is shared (@SyncWithBPMS on the getter, or an aggregate class which shares \
            everything - the default). Workflows which were already running when you upgraded keep \
            working through this fallback until they end.""",
        taskRegistry.getAdapterId(),
        propertyName,
        bpmnProcessId,
        workflowModuleId);

  }

  @Override
  public Class<?> getType(
      final ELContext context,
      final Object base,
      final Object property) {

    return Object.class;

  }

  @Override
  public void setValue(
      final ELContext context,
      final Object base,
      final Object property,
      final Object value) {

    if ((base == null) && (getValue(context, null, property) != null)) {
      throw new ProcessEngineException(
          "Cannot set value of '%s' - it resolves to VanillaBP-managed state.".formatted(property));
    }
    context.setPropertyResolved(false);

  }

  @Override
  public boolean isReadOnly(
      final ELContext context,
      final Object base,
      final Object property) {

    return true;

  }

  @Override
  public Class<?> getCommonPropertyType(
      final ELContext context,
      final Object base) {

    return Object.class;

  }

  @Override
  public Iterator<FeatureDescriptor> getFeatureDescriptors(
      final ELContext context,
      final Object base) {

    return null;

  }

}
