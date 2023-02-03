package io.vanillabp.camunda7.wiring;

import io.vanillabp.camunda7.wiring.Camunda7AdapterProperties.BpmnAsyncDefinition;
import org.camunda.bpm.engine.impl.bpmn.behavior.DmnBusinessRuleTaskActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.listener.DelegateExpressionExecutionListener;
import org.camunda.bpm.engine.impl.bpmn.listener.ExpressionExecutionListener;
import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.pvm.process.TransitionImpl;
import org.camunda.bpm.engine.impl.task.TaskDefinition;
import org.camunda.bpm.engine.impl.util.StringUtil;
import org.camunda.bpm.engine.impl.util.xml.Element;
import org.camunda.bpm.engine.impl.variable.VariableDeclaration;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@SuppressWarnings("deprecation")
public class TaskWiringBpmnParseListener implements BpmnParseListener {

    private static final Pattern CAMUNDA_EL_PATTERN = Pattern.compile("^[\\$\\#]\\{(.*)\\}$");

    private final Camunda7TaskWiring taskWiring;

    private final Camunda7UserTaskEventHandler userTaskEventHandler;
    
    private final boolean useBpmnAsyncDefinitions;
    
    private final List<BpmnAsyncDefinition> bpmnAsyncDefinitions;
    
    private List<Camunda7Connectable> connectables = new LinkedList<>();

    private Map<String, Camunda7Connectable> serviceTaskLikeElements = new HashMap<>();
    
    static enum Async {
        DONT_SET,
        SET_ASYNC_BEFORE_ONLY,
        SET_ASYNC_AFTER_ONLY,
        SET_ASYNC_BEFORE_AND_AFTER
    };
    
    public TaskWiringBpmnParseListener(
            final Camunda7TaskWiring taskWiring,
            final Camunda7UserTaskEventHandler userTaskEventHandler,
            final boolean useBpmnAsyncDefinitions,
            final List<BpmnAsyncDefinition> bpmnAsyncDefinitions) {

        super();
        this.taskWiring = taskWiring;
        this.userTaskEventHandler = userTaskEventHandler;
        this.useBpmnAsyncDefinitions = useBpmnAsyncDefinitions;
        this.bpmnAsyncDefinitions = bpmnAsyncDefinitions;
        
    }
    
    @Override
    public void parseProcess(
            final Element processElement,
            final ProcessDefinitionEntity processDefinition) {

        final var workflowModuleId = Camunda7WorkflowModuleAwareBpmnParse.getWorkflowModuleId();
        final var bpmnProcessId = processDefinition.getKey();

        final var processService = taskWiring.wireService(workflowModuleId, bpmnProcessId);

        connectables.forEach(connectable -> taskWiring.wireTask(processService, connectable));

        connectables.clear();
        serviceTaskLikeElements.clear();

    }
    
    @Override
    public void parseEndEvent(
            final Element endEventElement,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        connectEvent(endEventElement, scope, activity);
        
    }
    
    @Override
    public void parseIntermediateThrowEvent(
            final Element intermediateEventElement,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        connectEvent(
                intermediateEventElement,
                scope,
                activity);
        
    }
    
    @Override
    public void parseUserTask(
            final Element userTaskElement,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        final var taskDefinition = getTaskDefinition(activity);

        taskDefinition.addBuiltInTaskListener(
                org.camunda.bpm.engine.delegate.TaskListener.EVENTNAME_CREATE,
                userTaskEventHandler);
//        taskDefinition.addBuiltInTaskListener(
//                org.camunda.bpm.engine.delegate.TaskListener.EVENTNAME_DELETE,
//                null);

        final var bpmnProcessId = ((ProcessDefinitionEntity) activity.getProcessDefinition()).getKey();

        final var connectable = new Camunda7Connectable(
                bpmnProcessId,
                activity.getId(),
                taskDefinition.getFormKey() != null ? taskDefinition.getFormKey().getExpressionText() : null,
                Camunda7Connectable.Type.USERTASK);
        
        connectables.add(connectable);
        
        resetAsyncForWaitstateTasks(userTaskElement, activity);

    }

    private void connectListener(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity,
            final Camunda7Connectable.Type type,
            final String expression) {
        
        final var bpmnProcessId = ((ProcessDefinitionEntity) activity.getProcessDefinition()).getKey();
        
        final var connectable = new Camunda7Connectable(
                bpmnProcessId,
                activity.getId(),
                expression,
                type);
        
        connectables.add(connectable);
        
    }
    
    @Override
    public void parseBusinessRuleTask(
            final Element businessRuleTaskElement,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        if (activity.getActivityBehavior() instanceof DmnBusinessRuleTaskActivityBehavior) {
            return;
        }
                
        connectTask(businessRuleTaskElement, scope, activity);        
    }
    
    @Override
    public void parseSendTask(
            final Element sendTaskElement,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        connectTask(sendTaskElement, scope, activity);
        
    }

    @Override
    public void parseServiceTask(
            final Element serviceTaskElement,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        connectTask(serviceTaskElement, scope, activity);
        
    }

    private void connectTask(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        if (connectTaskLike(element, scope, activity)) {
            
            resetAsyncForNonWaitstateTasks(element, activity);

        } else {
            
            throw new RuntimeException(
                    "Missing implemenation 'delegate-expression' or 'external-task topic' on element '"
                    + activity.getId()
                    + "'");
                        
        }
        
    }
    
    private boolean connectTaskLike(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        final var bpmnProcessId = ((ProcessDefinitionEntity) activity.getProcessDefinition()).getKey();

        final var delegateExpression = element.attributeNS(
                BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS,
                BpmnParse.PROPERTYNAME_DELEGATE_EXPRESSION);

        final var expression = element.attributeNS(
                BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS,
                BpmnParse.PROPERTYNAME_EXPRESSION);
        
        final var topic = element.attributeNS(
                BpmnParse.CAMUNDA_BPMN_EXTENSIONS_NS,
                BpmnParse.PROPERTYNAME_EXTERNAL_TASK_TOPIC);
        
        final Camunda7Connectable connectable;
        if (StringUtil.hasText(delegateExpression)) {
            
            final var unwrappedDelegateExpression = unwrapExpression(activity, delegateExpression);
            
            connectable = new Camunda7Connectable(
                    bpmnProcessId,
                    activity.getId(),
                    unwrappedDelegateExpression,
                    Camunda7Connectable.Type.DELEGATE_EXPRESSION);
            
        } else if (StringUtil.hasText(expression)) {
            
            final var unwrappedExpression = unwrapExpression(activity, expression);
            
            connectable = new Camunda7Connectable(
                    bpmnProcessId,
                    activity.getId(),
                    unwrappedExpression,
                    Camunda7Connectable.Type.EXPRESSION);
            
        } else if (StringUtil.hasText(topic)) {
            
            connectable = new Camunda7Connectable(
                    bpmnProcessId,
                    activity.getId(),
                    topic,
                    Camunda7Connectable.Type.EXTERNAL_TASK);
            
        } else {
            
            return false;
                    
        }
        
        connectables.add(connectable);
        
        return true;
        
    }

    private void connectEvent(
            final Element eventElement,
            final ScopeImpl scope,
            final ActivityImpl activity) {

        final var messageEventDefinition = eventElement.element(BpmnParse.MESSAGE_EVENT_DEFINITION);
        if (messageEventDefinition != null) {

            final var connectedByImplementation = connectTaskLike(
                    messageEventDefinition,
                    scope,
                    activity);
            if (connectedByImplementation) {
                
                resetAsyncForNonWaitstateTasks(eventElement, activity);
                return;
                
            }
            
        }

        final var unsupportedListeners = activity
                .getListeners()
                .values()
                .stream()
                .flatMap(List::stream)
                .filter(l -> {
                    if (l instanceof ExpressionExecutionListener) {
                        final var expression = unwrapExpression(
                                activity,
                                ((ExpressionExecutionListener) l).getExpressionText());
                        connectListener(
                                eventElement,
                                scope,
                                activity,
                                Camunda7Connectable.Type.EXPRESSION,
                                expression);
                        return false;
                    }
                    return true;
                })
                .filter(l -> {
                    if (l instanceof DelegateExpressionExecutionListener) {
                        final var expression = unwrapExpression(
                                activity,
                                ((DelegateExpressionExecutionListener) l).getExpressionText());
                        connectListener(
                                eventElement,
                                scope,
                                activity,
                                Camunda7Connectable.Type.DELEGATE_EXPRESSION,
                                expression);
                        return false;
                    }
                    return true;
                })
                .map(l -> l.toString())
                .collect(Collectors.joining(", "));
        
        if (StringUtils.hasText(unsupportedListeners)) {
            throw new RuntimeException(
                    "Unsupported listeners at '"
                    + activity.getId()
                    + "': "
                    + unsupportedListeners);
        }

    }

    private String unwrapExpression(final ActivityImpl activity, final String delegateExpression) {
        
        final var expressionWrapperMatcher = CAMUNDA_EL_PATTERN.matcher(delegateExpression);
        
        if (!expressionWrapperMatcher.find()) {
            throw new RuntimeException(
                    "'delegate-expression' of element '"
                    + activity.getId()
                    + "' not uses pattern ${...} or #{...}: '"
                    + delegateExpression
                    + "'");
        }
        
        return expressionWrapperMatcher.group(1);
        
    }

    /**
     * Retrieves task definition.
     *
     * @param activity the taskActivity
     * @return taskDefinition for activity
     */
    private TaskDefinition getTaskDefinition(
            final ActivityImpl activity) {
        
        final UserTaskActivityBehavior activityBehavior = (UserTaskActivityBehavior) activity.getActivityBehavior();
        return activityBehavior.getTaskDefinition();
        
    }

    private void removeAsyncBeforeAndAsyncAfter(
            final Element element,
            final ActivityImpl activity) {
        
        resetAsyncBeforeAndAsyncAfter(
                element,
                activity,
                Async.DONT_SET);
        
    }

    private void resetAsyncForWaitstateTasks(
            final Element element,
            final ActivityImpl activity) {
        
        resetAsyncBeforeAndAsyncAfter(
                element,
                activity,
                Async.SET_ASYNC_AFTER_ONLY);
        
    }

    private void resetAsyncForNonWaitstateTasks(
            final Element element,
            final ActivityImpl activity) {
        
        resetAsyncBeforeAndAsyncAfter(
                element,
                activity,
                Async.SET_ASYNC_BEFORE_AND_AFTER);
        
    }

    private void resetAsyncBeforeAndAsyncAfter(
            final Element element,
            final ActivityImpl activity,
            final Async mode) {
        
        if (useBpmnAsyncDefinitions) {
            return;
        }
        
        final var workflowModuleId = Camunda7WorkflowModuleAwareBpmnParse.getWorkflowModuleId();
        final var bpmnProcessId = ((ProcessDefinitionEntity) activity.getProcessDefinition()).getKey();
        if (bpmnAsyncDefinitions
                .stream()
                .filter(d -> d.getWorkflowModuleId().equals(workflowModuleId))
                .filter(d -> d.getBpmnProcessId().equals(bpmnProcessId))
                .findFirst()
                .isPresent()) {
            return;
        }
        
        activity.setAsyncAfter(false);
        activity.setAsyncBefore(false);
        
        if ((mode == Async.SET_ASYNC_BEFORE_AND_AFTER)
                || (mode == Async.SET_ASYNC_BEFORE_ONLY)) {
            activity.setAsyncBefore(true, true);
        }
        if ((mode == Async.SET_ASYNC_BEFORE_AND_AFTER)
                || (mode == Async.SET_ASYNC_AFTER_ONLY)) {
            activity.setAsyncAfter(true, true);
        }
        
    }

    @Override
    public void parseStartEvent(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        resetAsyncBeforeAndAsyncAfter(element, activity, Async.SET_ASYNC_BEFORE_ONLY);

    }

    @Override
    public void parseExclusiveGateway(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseInclusiveGateway(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseParallelGateway(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseScriptTask(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        resetAsyncForNonWaitstateTasks(element, activity);

    }

    @Override
    public void parseTask(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseManualTask(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseBoundaryTimerEventDefinition(
            final Element element,
            final boolean interrupting,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseBoundaryErrorEventDefinition(
            final Element element,
            final boolean interrupting,
            final ActivityImpl activity,
            final ActivityImpl nestedErrorEventActivity) {
        
        resetAsyncForWaitstateTasks(element, activity);
        
    }

    @Override
    public void parseSubProcess(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseCallActivity(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseProperty(
            final Element element,
            final VariableDeclaration variableDeclaration,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseSequenceFlow(
            final Element sequenceFlowElement,
            final ScopeImpl scopeElement,
            final TransitionImpl transition) {

    }

    @Override
    public void parseMultiInstanceLoopCharacteristics(
            final Element element,
            final Element multiInstanceLoopCharacteristicsElement,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseIntermediateTimerEventDefinition(
            final Element element, ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseRootElement(
            final Element rootElement,
            final List<ProcessDefinitionEntity> processDefinitions) {
        
    }

    @Override
    public void parseReceiveTask(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        resetAsyncForWaitstateTasks(element, activity);

    }

    @Override
    public void parseIntermediateSignalCatchEventDefinition(
            final Element element,
            final ActivityImpl activity) {
        
        resetAsyncForWaitstateTasks(element, activity);

    }

    @Override
    public void parseBoundarySignalEventDefinition(
            final Element element,
            final boolean interrupting,
            final ActivityImpl activity) {
        
        resetAsyncForWaitstateTasks(element, activity);
        
    }

    @Override
    public void parseEventBasedGateway(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseTransaction(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseCompensateEventDefinition(
            final Element element,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseIntermediateCatchEvent(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseBoundaryEvent(
            final Element element,
            final ScopeImpl scope,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseIntermediateMessageCatchEventDefinition(
            final Element element,
            final ActivityImpl activity) {
        
        resetAsyncForWaitstateTasks(element, activity);

    }

    @Override
   public void parseBoundaryMessageEventDefinition(
            final Element element,
            final boolean interrupting,
            final ActivityImpl activity) {
        
        resetAsyncForWaitstateTasks(element, activity);

    }

    @Override
    public void parseBoundaryEscalationEventDefinition(
            final Element element, boolean interrupting, ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    public void parseBoundaryConditionalEventDefinition(
            final Element element,
            final boolean interrupting,
            final ActivityImpl activity) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }

    @Override
    public void parseIntermediateConditionalEventDefinition(
            final Element element,
            final ActivityImpl activity) {
        
        resetAsyncForWaitstateTasks(element, activity);

    }

    @Override
    public void parseConditionalStartEventForEventSubprocess(
            final Element element,
            final ActivityImpl activity,
            final boolean interrupting) {
        
        removeAsyncBeforeAndAsyncAfter(element, activity);

    }
    
}
