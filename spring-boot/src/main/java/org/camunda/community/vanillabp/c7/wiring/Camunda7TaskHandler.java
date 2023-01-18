package org.camunda.community.vanillabp.c7.wiring;

import io.vanillabp.spi.service.MultiInstanceElementResolver;
import io.vanillabp.spi.service.TaskEvent.Event;
import io.vanillabp.spi.service.TaskException;
import io.vanillabp.springboot.adapter.MultiInstance;
import io.vanillabp.springboot.adapter.TaskHandlerBase;
import io.vanillabp.springboot.parameters.MethodParameter;
import org.camunda.bpm.engine.delegate.BpmnError;
import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.model.bpmn.instance.Activity;
import org.camunda.bpm.model.bpmn.instance.BaseElement;
import org.camunda.bpm.model.bpmn.instance.MultiInstanceLoopCharacteristics;
import org.camunda.bpm.model.xml.ModelInstance;
import org.camunda.bpm.model.xml.instance.ModelElementInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Camunda7TaskHandler extends TaskHandlerBase implements JavaDelegate {

    private static final Logger logger = LoggerFactory.getLogger(Camunda7TaskHandler.class);

    private final String bpmnProcessId;

    private Object result;

    public Camunda7TaskHandler(
            final String bpmnProcessId,
            final CrudRepository<Object, String> workflowAggregateRepository,
            final Object bean,
            final Method method,
            final List<MethodParameter> parameters) {
        
        super(workflowAggregateRepository, bean, method, parameters);
        this.bpmnProcessId = bpmnProcessId;
        
    }

    @Override
    protected Logger getLogger() {

        return logger;

    }

    public String getMethodName() {

        return method.getName();

    }

    @Override
    @Transactional(noRollbackFor = BpmnError.class)
    public void execute(
            final DelegateExecution execution) throws Exception {
        
        final var multiInstanceCache = new Map[] { null };

        try {

            logger.trace("Will handle task '{}' of workflow '{}' ('{}') by execution '{}'",
                    execution.getBpmnModelElementInstance().getId(),
                    execution.getProcessInstanceId(),
                    bpmnProcessId,
                    execution.getId());
            
            super.execute(
                    execution.getBusinessKey(),
                    multiInstanceActivity -> {
                        if (multiInstanceCache[0] == null) {
                            multiInstanceCache[0] = Camunda7TaskHandler.getMultiInstanceContext(execution);
                        }
                        return multiInstanceCache[0].get(multiInstanceActivity);
                    },
                    taskParameter -> execution.getVariableLocal(taskParameter),
                    null,
                    () -> Event.CREATED);

        } catch (TaskException e) {

            if (e.getErrorName() != null) {
                throw new BpmnError(e.getErrorCode(), e.getErrorName(), e);
            }
            throw new BpmnError(e.getErrorCode(), e);

        }

    }
    
    public Object getResult() {

        return result;

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

    private static ModelElementInstance getCurrentElement(final ModelInstance model, DelegateExecution miExecution) {

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
