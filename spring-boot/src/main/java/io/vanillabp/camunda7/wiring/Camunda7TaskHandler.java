package io.vanillabp.camunda7.wiring;

import io.vanillabp.camunda7.Camunda7AdapterConfiguration;
import io.vanillabp.camunda7.LoggingContext;
import io.vanillabp.camunda7.service.Camunda7ProcessService;
import io.vanillabp.spi.service.MultiInstanceElementResolver;
import io.vanillabp.spi.service.TaskEvent.Event;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.springboot.adapter.MultiInstance;
import io.vanillabp.springboot.adapter.TaskHandlerBase;
import io.vanillabp.springboot.adapter.wiring.WorkflowAggregateCache;
import io.vanillabp.springboot.parameters.MethodParameter;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.camunda.bpm.model.bpmn.instance.Process;
import org.camunda.bpm.model.xml.ModelInstance;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

public class Camunda7TaskHandler extends TaskHandlerBase implements JavaDelegate {

    private static final Logger logger = LoggerFactory.getLogger(Camunda7TaskHandler.class);

    private final String bpmnProcessId;

    private final String tenantId;

    private final String workflowModuleId;

    private Object result;

    private final Camunda7ProcessService<?> processService;

    public Camunda7TaskHandler(
            final String bpmnProcessId,
            final CrudRepository<Object, Object> workflowAggregateRepository,
            final Object bean,
            final Method method,
            final List<MethodParameter> parameters,
            final Camunda7ProcessService<?> processService,
            final String tenantId,
            final String workflowModuleId) {
        
        super(workflowAggregateRepository, bean, method, parameters);
        this.bpmnProcessId = bpmnProcessId;
        this.processService = processService;
        this.tenantId = tenantId;
        this.workflowModuleId = workflowModuleId;
        
    }

    @Override
    protected Logger getLogger() {

        return logger;

    }

    public String getMethodName() {

        return method.getName();

    }

    @SuppressWarnings("unchecked")
    @Override
    @Transactional(noRollbackFor = BpmnError.class)
    public void execute(
            final DelegateExecution execution) throws Exception {
        
        final var multiInstanceCache = new Map[] { null };

        try {

            final var currentElement = (Activity) getCurrentElement(execution.getBpmnModelInstance(), execution);
            LoggingContext.setLoggingContext(
                    Camunda7AdapterConfiguration.ADAPTER_ID,
                    tenantId,
                    workflowModuleId,
                    execution.getBusinessKey(),
                    bpmnProcessId,
                    execution.getId(),
                    getSuperProcessInstanceId(execution),
                    getBpmnProcessId(execution)
                            + "#"
                            + currentElement.getId(),
                    execution.getId());

            logger.trace("Will handle task '{}' of workflow '{}' ('{}') by execution '{}'",
                    currentElement.getId(),
                    execution.getProcessInstanceId(),
                    bpmnProcessId,
                    execution.getId());
            
            final Function<String, Object> multiInstanceSupplier = multiInstanceActivity -> {
                if (multiInstanceCache[0] == null) {
                    multiInstanceCache[0] = Camunda7TaskHandler.getMultiInstanceContext(execution);
                }
                return multiInstanceCache[0].get(multiInstanceActivity);
            };
            
            final var workflowAggregateId = processService
                    .getWorkflowAggregateIdFromBusinessKey(execution.getBusinessKey());
            
            final var workflowAggregateCache = new WorkflowAggregateCache();
            
            super.execute(
                    workflowAggregateCache,
                    workflowAggregateId,
                    true,
                    (args, param) -> processTaskParameter(
                            args,
                            param,
                            execution::getVariableLocal),
                    (args, param) -> processTaskEventParameter(
                            args,
                            param,
                            () -> Event.CREATED),
                    (args, param) -> processMultiInstanceIndexParameter(
                            args,
                            param,
                            multiInstanceSupplier),
                    (args, param) -> processMultiInstanceTotalParameter(
                            args,
                            param,
                            multiInstanceSupplier),
                    (args, param) -> processMultiInstanceElementParameter(
                            args,
                            param,
                            multiInstanceSupplier),
                    (args, param) -> processMultiInstanceResolverParameter(
                            args,
                            param,
                            () -> {
                                if (workflowAggregateCache.workflowAggregate == null) {
                                    workflowAggregateCache.workflowAggregate = workflowAggregateRepository
                                            .findById(workflowAggregateId)
                                            .orElseThrow();
                                }
                                return workflowAggregateCache.workflowAggregate;
                            }, multiInstanceSupplier));

        } catch (TaskException e) {

            if (e.getErrorName() != null) {
                throw new BpmnError(e.getErrorCode(), e.getErrorName(), e);
            }
            throw new BpmnError(e.getErrorCode(), e);

        } finally {
            LoggingContext.clearContext();
        }

    }
    
    public Object getResult() {

        return result;

    }

    static String getSuperProcessInstanceId(
            final DelegateExecution execution) {

        DelegateExecution cExecution = execution;
        while (true) {

            final var result = cExecution.getProcessInstanceId();
            cExecution = cExecution.getParentId() != null
                    ? ((ExecutionEntity) cExecution).getParent()
                    : cExecution.getSuperExecution();
            if (cExecution == null) {
                return result;
            }

        }

    }

    static String getBpmnProcessId(
            final DelegateExecution execution) {

        DelegateExecution cExecution = execution;
        while (true) {

            final var flowElement = cExecution.getBpmnModelElementInstance();
            if (flowElement instanceof Process) {
                return flowElement.getId();
            }
            cExecution = cExecution.getParentId() != null
                    ? ((ExecutionEntity) cExecution).getParent()
                    : cExecution.getSuperExecution();
            if (cExecution == null) {
                return null;
            }

        }

    }

    static Map<String, MultiInstanceElementResolver.MultiInstance<Object>> getMultiInstanceContext(
            final DelegateExecution execution) {

        final var result = new LinkedHashMap<String, MultiInstanceElementResolver.MultiInstance<Object>>();

        final var model = execution.getBpmnModelElementInstance().getModelInstance();

        DelegateExecution miExecution = execution;
        MultiInstanceLoopCharacteristics loopCharacteristics = null;
        // find multi-instance element from current element up to the root of the
        // process-hierarchy
        while (loopCharacteristics == null) {

            // check current element for multi-instance
            final var bpmnElement = getCurrentElement(model, miExecution);
            if (bpmnElement instanceof Activity) {
                loopCharacteristics = (MultiInstanceLoopCharacteristics) ((Activity) bpmnElement)
                        .getLoopCharacteristics();
            }

            // if still not found then check parent
            if (loopCharacteristics == null) {
                miExecution = miExecution.getParentId() != null
                        ? ((ExecutionEntity) miExecution).getParent()
                        : miExecution.getSuperExecution();
            }
            // multi-instance found
            else {
                final var itemNo = (Integer) miExecution.getVariable("loopCounter");
                final var totalCount = (Integer) miExecution.getVariable("nrOfInstances");
                final var currentItem = loopCharacteristics.getCamundaElementVariable() == null ? null
                        : miExecution.getVariable(loopCharacteristics.getCamundaElementVariable());

                result.put(((BaseElement) bpmnElement).getId(),
                        new MultiInstance<Object>(currentItem, totalCount, itemNo));

            }

            // if there is no parent then multi-instance task was used in a
            // non-multi-instance environment
            if ((miExecution == null) && (loopCharacteristics == null)) {
                throw new RuntimeException(
                        "No multi-instance context found for element '"
                        + execution.getBpmnModelElementInstance().getId()
                        + "' or its parents!");
            }

        }

        return result;

    }

    static ModelElementInstance getCurrentElement(final ModelInstance model, DelegateExecution miExecution) {

        // if current element is known then simply use it
        if (miExecution.getBpmnModelElementInstance() != null) {
            return miExecution.getBpmnModelElementInstance();
        }

        // if execution belongs to an activity (e.g. embedded subprocess) then
        // parse activity-instance-id which looks like "[element-id]:[instance-id]"
        // (e.g. "Activity_14fom0j:29d7e405-9605-11ec-bc62-0242700b16f6")
        final var activityInstanceId = miExecution.getActivityInstanceId();
        final var elementMarker = activityInstanceId.indexOf(':');

        // if there is no marker then the execution does not belong to a specific
        // element
        if (elementMarker == -1) {
            return null;
        }

        return model.getModelElementById(activityInstanceId.substring(0, elementMarker));

    }

}
