package io.vanillabp.camunda7.service;

import io.vanillabp.camunda7.Camunda7AdapterConfiguration;
import io.vanillabp.springboot.adapter.AdapterAwareProcessService;
import io.vanillabp.springboot.adapter.ProcessServiceImplementation;
import org.camunda.bpm.engine.ProcessEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.function.Function;

public class Camunda7ProcessService<DE>
        implements ProcessServiceImplementation<DE> {

    private static final Logger logger = LoggerFactory.getLogger(Camunda7ProcessService.class);
    
    private final ApplicationEventPublisher applicationEventPublisher;
    
    private final ProcessEngine processEngine;
    
    private final CrudRepository<DE, String> workflowAggregateRepository;
    
    private final Class<DE> workflowAggregateClass;
    
    private final Function<DE, String> getWorkflowAggregateId;

    private AdapterAwareProcessService<DE> parent;

    private String workflowModuleId;

    private String bpmnProcessId;

    public Camunda7ProcessService(
            final ApplicationEventPublisher applicationEventPublisher,
            final ProcessEngine processEngine,
            final Function<DE, String> getWorkflowAggregateId,
            final CrudRepository<DE, String> workflowAggregateRepository,
            final Class<DE> workflowAggregateClass) {

        super();
        this.applicationEventPublisher = applicationEventPublisher;
        this.processEngine = processEngine;
        this.workflowAggregateRepository = workflowAggregateRepository;
        this.workflowAggregateClass = workflowAggregateClass;
        this.getWorkflowAggregateId = getWorkflowAggregateId;

    }
    
    @Override
    public void setParent(
            final AdapterAwareProcessService<DE> parent) {
        
        this.parent = parent;
        
    }

    public void wire(
            final String workflowModuleId,
            final String bpmnProcessId) {
        
        this.workflowModuleId = workflowModuleId;
        this.bpmnProcessId = bpmnProcessId;
        
        if (parent != null) {
            parent.wire(
                    Camunda7AdapterConfiguration.ADAPTER_ID,
                    workflowModuleId,
                    bpmnProcessId);
        }
        
    }
    
    public boolean testForNotYetWired() {
        
        if (bpmnProcessId == null) {
            logger.error(
                    "The bean ProcessService<{}> was not wired to a BPMN process! "
                            + "It is likely that the BPMN is not part of the classpath.",
                            workflowAggregateClass.getName());
            return true;
        }
        
        return false;

    }

    @Override
    public String getBpmnProcessId() {

        return bpmnProcessId;

    }

    @Override
    public Class<DE> getWorkflowAggregateClass() {

        return workflowAggregateClass;

    }

    @Override
    public CrudRepository<DE, String> getWorkflowAggregateRepository() {

        return workflowAggregateRepository;

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
                .createProcessInstanceByKey(bpmnProcessId)
                .businessKey(id)
                .processDefinitionTenantId(workflowModuleId)
                .execute();
        
        return workflowAggregateRepository
                .save(attachedAggregate);

    }

    @Override
    @Transactional
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
                bpmnProcessId
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

        final var originalId = getWorkflowAggregateId.apply(workflowAggregate);
        final var isNewEntity = Objects.isNull(originalId);

        final var attachedAggregate = workflowAggregateRepository
                .save(workflowAggregate);
        
        final var id = (isNewEntity ? getWorkflowAggregateId
                .apply(attachedAggregate) : originalId)
                .toString();
        
        final var correlation = processEngine
                .getRuntimeService()
                .createMessageCorrelation(messageName)
                .processInstanceBusinessKey(id);

        if (correlationIdLocalVariableName != null) {
            correlation.localVariableEquals(
                    correlationIdLocalVariableName,
                    correlationId);
        }

        wakeupJobExecutorOnActivity();

        if (isNewEntity) {
            
            final var result = correlation.correlateStartMessage();
            logger.trace("Started process '{}#{}' by message-correlation '{}' (tenant: {})",
                    bpmnProcessId,
                    result.getProcessInstanceId(),
                    messageName,
                    result.getTenantId());
            
        } else {
        
            final var result = correlation
                    .correlateWithResult()
                    .getExecution();
            
            logger.trace("Correlated message '{}' using correlation-id '{}' for process '{}#{}' and execution '{}' (tenant: {})",
                    messageName,
                    correlationId,
                    bpmnProcessId,
                    result.getProcessInstanceId(),
                    result.getId(),
                    result.getTenantId());

        }
        
        return attachedAggregate;

    }

    @Override
    public DE completeUserTask(
            final DE workflowAggregate,
            final String taskId) {

        final var attachedAggregate = workflowAggregateRepository
                .save(workflowAggregate);
        
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
