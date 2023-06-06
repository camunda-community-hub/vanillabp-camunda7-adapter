package io.vanillabp.camunda7.service;

import io.vanillabp.camunda7.Camunda7AdapterConfiguration;
import io.vanillabp.springboot.adapter.AdapterAwareProcessService;
import io.vanillabp.springboot.adapter.ProcessServiceImplementation;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.exception.NullValueException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.repository.CrudRepository;

import java.util.Collection;
import java.util.function.Function;

public class Camunda7ProcessService<DE>
        implements ProcessServiceImplementation<DE> {

    private static final Logger logger = LoggerFactory.getLogger(Camunda7ProcessService.class);
    
    private final ApplicationEventPublisher applicationEventPublisher;
    
    private final ProcessEngine processEngine;
    
    private final CrudRepository<DE, Object> workflowAggregateRepository;
    
    private final Class<DE> workflowAggregateClass;
    
    private final Function<DE, ?> getWorkflowAggregateId;
    
    private final Function<DE, Boolean> isNewEntity;
    
    private final Function<String, Object> parseWorkflowAggregateIdFromBusinessKey;

    private AdapterAwareProcessService<DE> parent;

    public Camunda7ProcessService(
            final ApplicationEventPublisher applicationEventPublisher,
            final ProcessEngine processEngine,
            final Function<DE, Boolean> isNewEntity,
            final Function<DE, ?> getWorkflowAggregateId,
            final CrudRepository<DE, Object> workflowAggregateRepository,
            final Class<DE> workflowAggregateClass,
            final Function<String, Object> parseWorkflowAggregateIdFromBusinessKey) {

        super();
        this.applicationEventPublisher = applicationEventPublisher;
        this.processEngine = processEngine;
        this.workflowAggregateRepository = workflowAggregateRepository;
        this.workflowAggregateClass = workflowAggregateClass;
        this.isNewEntity = isNewEntity;
        this.getWorkflowAggregateId = getWorkflowAggregateId;
        this.parseWorkflowAggregateIdFromBusinessKey = parseWorkflowAggregateIdFromBusinessKey;
        
    }
    
    @Override
    public void setParent(
            final AdapterAwareProcessService<DE> parent) {
        
        this.parent = parent;
        
    }
    
    public Collection<String> getBpmnProcessIds() {
        
        return parent.getBpmnProcessIds();
                
    }

    public void wire(
            final String workflowModuleId,
            final String bpmnProcessId,
            final boolean isPrimary,
            final Collection<String> messageBasedStartEventsMessageNames,
            final Collection<String> signalBasedStartEventsSignalNames) {

        if (parent == null) {
            throw new RuntimeException("Not yet wired! If this occurs Spring Boot dependency of either "
                    + "VanillaBP Spring Boot support or Camunda7 adapter was changed introducing this "
                    + "lack of wiring. Please report a Github issue!");
            
        }

        parent.wire(
                Camunda7AdapterConfiguration.ADAPTER_ID,
                workflowModuleId,
                bpmnProcessId,
                isPrimary,
                messageBasedStartEventsMessageNames,
                signalBasedStartEventsSignalNames);
        
    }
    
    public boolean testForNotYetWired() {
        
        if (parent.getPrimaryBpmnProcessId() == null) {
            logger.error(
                    "The bean ProcessService<{}> was not wired to a BPMN process! "
                            + "It is likely that the BPMN is not part of the classpath.",
                            workflowAggregateClass.getName());
            return true;
        }
        
        return false;

    }

    @Override
    public Class<DE> getWorkflowAggregateClass() {

        return workflowAggregateClass;

    }

    @Override
    public CrudRepository<DE, Object> getWorkflowAggregateRepository() {

        return workflowAggregateRepository;

    }
    
    public Object getWorkflowAggregateIdFromBusinessKey(
            final String businessKey) {
        
        return parseWorkflowAggregateIdFromBusinessKey.apply(businessKey);
        
    }
    
    @Override
    public DE startWorkflow(
            final DE workflowAggregate) throws Exception {

        final var attachedAggregate = workflowAggregateRepository
                .save(workflowAggregate);

        final var id = getWorkflowAggregateId
                .apply(attachedAggregate)
                .toString();
        
        wakeupJobExecutorOnActivity();
        
        processEngine
                .getRuntimeService()
                .createProcessInstanceByKey(parent.getPrimaryBpmnProcessId())
                .businessKey(id)
                .processDefinitionTenantId(parent.getWorkflowModuleId())
                .execute();
        
        return workflowAggregateRepository
                .save(attachedAggregate);

    }

    @Override
    public DE correlateMessage(
            final DE workflowAggregate,
            final String messageName) {
        
        return correlateMessage(
                workflowAggregate,
                messageName,
                null,
                null);

    }
    
    @Override
    public DE correlateMessage(
            final DE workflowAggregate,
            final Object message) {
        
        return correlateMessage(
                workflowAggregate,
                message.getClass().getSimpleName());
        
    }

    @Override
    public DE correlateMessage(
            final DE workflowAggregate,
            final String messageName,
            final String correlationId) {
        
        final var correlationIdLocalVariableName =
                parent.getPrimaryBpmnProcessId()
                + "-"
                + messageName;

        return correlateMessage(
                workflowAggregate,
                messageName,
                correlationIdLocalVariableName,
                correlationId);

    }
    
    @Override
    public DE correlateMessage(
            final DE workflowAggregate,
            final Object message,
            final String correlationId) {
        
        return correlateMessage(
                workflowAggregate,
                message.getClass().getSimpleName(),
                correlationId);
        
    }

    private DE correlateMessage(
            final DE workflowAggregate,
            final String messageName,
            final String correlationIdLocalVariableName,
            final String correlationId) {

        final var isNewEntity = this.isNewEntity
                .apply(workflowAggregate);
        
        // persist to get ID in case of @Id @GeneratedValue
        // and force optimistic locking exceptions before running
        // the workflow if aggregate was already persisted before
        final var attachedAggregate = workflowAggregateRepository
                .save(workflowAggregate);
        
        final var id = getWorkflowAggregateId
                .apply(attachedAggregate);
        
        final var correlation = processEngine
                .getRuntimeService()
                .createMessageCorrelation(messageName)
                .processInstanceBusinessKey(id.toString());
        if (correlationIdLocalVariableName != null) {
            correlation.localVariableEquals(
                    correlationIdLocalVariableName,
                    correlationId);
        }

        wakeupJobExecutorOnActivity();

        if (isNewEntity) {
            
            final var result = correlation.correlateStartMessage();
            logger.trace("Started process '{}#{}' by message-correlation '{}' (tenant: {})",
                    parent.getPrimaryBpmnProcessId(),
                    result.getProcessInstanceId(),
                    messageName,
                    result.getTenantId());
            
            return attachedAggregate;
            
        }
            
        final var correlationExecutions = processEngine
                .getRuntimeService()
                .createExecutionQuery()
                .messageEventSubscriptionName(messageName)
                .processInstanceBusinessKey(id.toString())
                .active();
        if (correlationIdLocalVariableName != null) {
            correlationExecutions.variableValueEquals(
                    correlationIdLocalVariableName,
                    correlationId);
        }
        final var hasMessageCorrelation = correlationExecutions.count() == 1;
        
        if (!hasMessageCorrelation) {
            
            logger.trace("Message '{}' of process having bpmn-process-id '{}' could "
                    + "not be correlated using correlation-id '{}' for workflow aggregate '{}'!",
                    messageName,
                    parent.getPrimaryBpmnProcessId(),
                    correlationId,
                    id);

            return attachedAggregate;
            
        }
            
        final var result = correlation
                .correlateWithResult()
                .getExecution();
        
        logger.trace("Correlated message '{}' using correlation-id '{}' for process '{}#{}' "
                + "and execution '{}' (tenant: {})",
                messageName,
                correlationId,
                parent.getPrimaryBpmnProcessId(),
                result.getProcessInstanceId(),
                result.getId(),
                result.getTenantId());

        return attachedAggregate;

    }

    @Override
    public DE completeUserTask(
            final DE workflowAggregate,
            final String taskId) {

        final var attachedAggregate = workflowAggregateRepository
                .save(workflowAggregate);
        
        final var id = getWorkflowAggregateId.apply(workflowAggregate);
        final var task = processEngine
                .getTaskService()
                .createTaskQuery()
                .processInstanceBusinessKey(id.toString())
                .taskId(taskId)
                .singleResult();
        
        if (task == null) {
            throw new NullValueException("Task '"
                    + taskId
                    + "' not found!");
        }
        
        wakeupJobExecutorOnActivity();

        processEngine
                .getTaskService()
                .complete(taskId);
        
        return attachedAggregate;
        
    }

    @Override
    public DE completeTask(
            final DE workflowAggregate,
            final String taskId) {
        
        throw new UnsupportedOperationException();
        
    }
    
    @Override
    public DE cancelTask(
            final DE workflowAggregate,
            final String taskId,
            final String bpmnErrorCode) {
        
        throw new UnsupportedOperationException();
        
    }
    
    @Override
    public DE cancelUserTask(
            final DE workflowAggregate,
            final String taskId,
            final String errorCode) {
        
        final var attachedAggregate = workflowAggregateRepository
                .save(workflowAggregate);

        wakeupJobExecutorOnActivity();
        
        processEngine
                .getTaskService()
                .handleBpmnError(taskId, errorCode);

        return attachedAggregate;
        
    }
    
    private void wakeupJobExecutorOnActivity() {
        
        logger.debug("Wanna wake up job-executor");
        applicationEventPublisher.publishEvent(
                new WakupJobExecutorNotification(
                        this.getClass().getName()));
        
    }
    
}
