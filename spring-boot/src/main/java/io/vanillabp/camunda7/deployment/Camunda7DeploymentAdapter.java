package io.vanillabp.camunda7.deployment;


import io.vanillabp.camunda7.Camunda7AdapterConfiguration;
import io.vanillabp.camunda7.wiring.Camunda7TaskWiring;
import io.vanillabp.camunda7.wiring.TaskWiringBpmnParseListener;
import io.vanillabp.springboot.adapter.ModuleAwareBpmnDeployment;
import io.vanillabp.springboot.adapter.VanillaBpProperties;
import jakarta.annotation.PostConstruct;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.repository.ResumePreviousBy;
import org.camunda.bpm.engine.spring.application.SpringProcessApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.transaction.annotation.Transactional;


@Transactional
public class Camunda7DeploymentAdapter extends ModuleAwareBpmnDeployment {

	private static final Logger logger = LoggerFactory.getLogger(Camunda7DeploymentAdapter.class);

    private final ProcessEngine processEngine;
    
    private final SpringProcessApplication processApplication;

    private final Camunda7TaskWiring taskWiring;

    public Camunda7DeploymentAdapter(
            final VanillaBpProperties properties,
            final SpringProcessApplication processApplication,
            final Camunda7TaskWiring taskWiring,
            final ProcessEngine processEngine) {
        
        super(properties);
        this.processEngine = processEngine;
        this.processApplication = processApplication;
        this.taskWiring = taskWiring;
        
    }

    @Override
    protected Logger getLogger() {
    	
    	return logger;
    	
    }
    
    @Override
    protected String getAdapterId() {
        
        return Camunda7AdapterConfiguration.ADAPTER_ID;
        
    }
    
    @Override
    @PostConstruct
    public void deployAllWorkflowModules() {

        super.deployAllWorkflowModules();

        taskWiring.validateWiring();

    }

    @Override
    protected void doDeployment(
    		final String workflowModuleId,
            final String workflowModuleName,
            final Resource[] bpmns,
            final Resource[] dmns,
            final Resource[] cmms)
            throws Exception {

        final var deploymentBuilder = processEngine
                .getRepositoryService()
                .createDeployment(processApplication.getReference())
                .resumePreviousVersions()
                .resumePreviousVersionsBy(ResumePreviousBy.RESUME_BY_DEPLOYMENT_NAME)
                .enableDuplicateFiltering(true)
                .source(applicationName)
                .tenantId(workflowModuleName)
                .name(workflowModuleName);

        boolean hasDeployables = false;
        
        for (final var resource : bpmns) {
            try (final var inputStream = resource.getInputStream()) {
                deploymentBuilder.addInputStream(resource.getFilename(), inputStream);
                hasDeployables = true;
            }
        }

        for (final var resource : cmms) {
            try (final var inputStream = resource.getInputStream()) {
                deploymentBuilder.addInputStream(resource.getFilename(), inputStream);
                hasDeployables = true;
            }
        }

        for (final var resource : dmns) {
            try (final var inputStream = resource.getInputStream()) {
                deploymentBuilder.addInputStream(resource.getFilename(), inputStream);
                hasDeployables = true;
            }
        }

        // BPMNs which are new will be parsed and wired as part of the deployment
        final String deploymentId;
        if (hasDeployables) {
            deploymentId = deploymentBuilder
                    .deployWithResult()
                    .getId();
        } else {
            deploymentId = "";
        }

        // BPMNs which were deployed in the past need to be forced to be parsed for wiring 
        processEngine
                .getRepositoryService()
                .createProcessDefinitionQuery()
                .tenantIdIn(workflowModuleName)
                .list()
                .stream()
                .forEach(definition -> {
                    // process models parsed during deployment are cached and therefore
                    // not wired twice.
                    try {
                        TaskWiringBpmnParseListener.setOldVersionBpmn(
                                !definition.getDeploymentId().equals(deploymentId));
                        processEngine.getRepositoryService().getProcessModel(definition.getId());
                    } finally {
                        TaskWiringBpmnParseListener.setOldVersionBpmn(false);
                    }
                });

    }
    
}
