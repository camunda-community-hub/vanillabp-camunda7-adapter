package io.vanillabp.camunda7.service;

import io.vanillabp.camunda7.Camunda7AdapterConfiguration;
import io.vanillabp.camunda7.Camunda7VanillaBpProperties;
import io.vanillabp.camunda7.LoggingContext;
import io.vanillabp.camunda7.service.jobs.startprocess.StartProcessCommand;
import io.vanillabp.springboot.adapter.AdapterAwareProcessService;
import io.vanillabp.springboot.adapter.ProcessServiceImplementation;
import java.util.Collection;
import java.util.function.Function;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.exception.NullValueException;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.repository.CrudRepository;

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

    private final Camunda7VanillaBpProperties camunda7Properties;

    private AdapterAwareProcessService<DE> parent;

    public Camunda7ProcessService(
            final ApplicationEventPublisher applicationEventPublisher,
            final Camunda7VanillaBpProperties camunda7Properties,
            final ProcessEngine processEngine,
            final Function<DE, Boolean> isNewEntity,
            final Function<DE, ?> getWorkflowAggregateId,
            final CrudRepository<DE, Object> workflowAggregateRepository,
            final Class<DE> workflowAggregateClass,
            final Function<String, Object> parseWorkflowAggregateIdFromBusinessKey) {

        super();
        this.applicationEventPublisher = applicationEventPublisher;
        this.camunda7Properties = camunda7Properties;
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

    @Override
    public String getPrimaryBpmnProcessId() {

        return parent.getPrimaryBpmnProcessId();

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

        try {

            final var attachedAggregate = workflowAggregateRepository
                    .save(workflowAggregate);

            final var aggregateId = getWorkflowAggregateId
                    .apply(attachedAggregate)
                    .toString();

            final var tenantId = camunda7Properties.getTenantId(parent.getWorkflowModuleId());
            final var bpmnProcessId = parent.getPrimaryBpmnProcessId();
            LoggingContext.setLoggingContext(
                    Camunda7AdapterConfiguration.ADAPTER_ID,
                    camunda7Properties.getTenantId(parent.getWorkflowModuleId()),
                    parent.getWorkflowModuleId(),
                    aggregateId,
                    bpmnProcessId,
                    null,
                    null,
                    null,
                    null);

            wakeupJobExecutorOnActivity();

            // Start workflow asynchronously by Camunda's job-executor
            // Hint: this is not done by setting "async-before" on the start-event
            // since we don't know which process is used as a call-activity which
            // has to be started synchronously.
            ((ProcessEngineConfigurationImpl) processEngine
                    .getProcessEngineConfiguration())
                    .getCommandExecutorTxRequired()
                    .execute(new StartProcessCommand(
                            tenantId,
                            bpmnProcessId,
                            aggregateId));

            return attachedAggregate;

        } finally {
            LoggingContext.clearContext();
        }

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

        try {

            final var isNewEntity = this.isNewEntity
                    .apply(workflowAggregate);

            // persist to get ID in case of @Id @GeneratedValue
            // and force optimistic locking exceptions before running
            // the workflow if aggregate was already persisted before
            final var attachedAggregate = workflowAggregateRepository
                    .save(workflowAggregate);

            final var aggregateId = getWorkflowAggregateId
                    .apply(attachedAggregate)
                    .toString();

            final var bpmnProcessId = parent.getPrimaryBpmnProcessId();
            final var tenantId = camunda7Properties.getTenantId(parent.getWorkflowModuleId());
            LoggingContext.setLoggingContext(
                    Camunda7AdapterConfiguration.ADAPTER_ID,
                    tenantId,
                    parent.getWorkflowModuleId(),
                    aggregateId,
                    bpmnProcessId,
                    null,
                    null,
                    null,
                    null);

            final var correlation =
                    (tenantId == null
                            ? processEngine
                                    .getRuntimeService()
                                    .createMessageCorrelation(messageName)
                            : processEngine
                                    .getRuntimeService()
                                    .createMessageCorrelation(messageName)
                                    .tenantId(tenantId))
                    .processInstanceBusinessKey(aggregateId);
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

                return attachedAggregate;

            }
            
            final var correlationExecutions =
                    (tenantId == null
                            ? processEngine
                                    .getRuntimeService()
                                    .createExecutionQuery()
                            : processEngine
                                    .getRuntimeService()
                                    .createExecutionQuery()
                                    .tenantIdIn(tenantId))
                    .messageEventSubscriptionName(messageName)
                    .processInstanceBusinessKey(aggregateId)
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
                        bpmnProcessId,
                        correlationId,
                        aggregateId);

                return attachedAggregate;

            }

            final var result = correlation
                    .correlateWithResult()
                    .getExecution();

            logger.trace("Correlated message '{}' using correlation-id '{}' for process '{}#{}' "
                    + "and execution '{}' (tenant: {})",
                    messageName,
                    correlationId,
                    bpmnProcessId,
                    result.getProcessInstanceId(),
                    result.getId(),
                    result.getTenantId());

            return attachedAggregate;

        } finally {
            LoggingContext.clearContext();
        }

    }

    @Override
    public DE completeUserTask(
            final DE workflowAggregate,
            final String taskId) {

        try {

            final var attachedAggregate = workflowAggregateRepository
                    .save(workflowAggregate);

            final var aggregateId = getWorkflowAggregateId.apply(workflowAggregate).toString();

            final var tenantId = camunda7Properties.getTenantId(parent.getWorkflowModuleId());
            final var task = (tenantId == null
                    ? processEngine
                    .getTaskService()
                    .createTaskQuery()
                    : processEngine
                    .getTaskService()
                    .createTaskQuery()
                    .tenantIdIn(tenantId))
                    .processInstanceBusinessKey(aggregateId)
                    .taskId(taskId)
                    .singleResult();

            if (task == null) {
                throw new NullValueException("Task '"
                        + taskId
                        + "' not found!");
            }

            final var bpmnProcessId = parent.getPrimaryBpmnProcessId();
            LoggingContext.setLoggingContext(
                    Camunda7AdapterConfiguration.ADAPTER_ID,
                    tenantId,
                    parent.getWorkflowModuleId(),
                    aggregateId,
                    bpmnProcessId,
                    taskId,
                    task.getProcessInstanceId(),
                    task.getProcessDefinitionId() + "#" + task.getTaskDefinitionKey(),
                    task.getExecutionId());

            wakeupJobExecutorOnActivity();

            processEngine
                    .getTaskService()
                    .complete(taskId);

            return attachedAggregate;

        } finally {
            LoggingContext.clearContext();
        }
        
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

        try {

            final var attachedAggregate = workflowAggregateRepository
                    .save(workflowAggregate);

            final var aggregateId = getWorkflowAggregateId.apply(workflowAggregate).toString();

            final var tenantId = camunda7Properties.getTenantId(parent.getWorkflowModuleId());
            final var task = (tenantId == null
                    ? processEngine
                    .getTaskService()
                    .createTaskQuery()
                    : processEngine
                    .getTaskService()
                    .createTaskQuery()
                    .tenantIdIn(tenantId))
                    .processInstanceBusinessKey(aggregateId)
                    .taskId(taskId)
                    .singleResult();

            if (task == null) {
                throw new NullValueException("Task '"
                        + taskId
                        + "' not found!");
            }

            final var bpmnProcessId = parent.getPrimaryBpmnProcessId();
            LoggingContext.setLoggingContext(
                    Camunda7AdapterConfiguration.ADAPTER_ID,
                    tenantId,
                    parent.getWorkflowModuleId(),
                    aggregateId,
                    bpmnProcessId,
                    taskId,
                    task.getProcessInstanceId(),
                    task.getTaskDefinitionKey(),
                    task.getExecutionId());

            wakeupJobExecutorOnActivity();

            processEngine
                    .getTaskService()
                    .handleBpmnError(taskId, errorCode);

            return attachedAggregate;

        } finally {
            LoggingContext.clearContext();
        }
        
    }
    
    private void wakeupJobExecutorOnActivity() {
        
        logger.debug("Wanna wake up job-executor");
        applicationEventPublisher.publishEvent(
                new WakupJobExecutorNotification(
                        this.getClass().getName()));
        
    }
    
}
