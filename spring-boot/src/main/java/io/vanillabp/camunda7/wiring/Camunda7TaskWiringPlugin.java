package io.vanillabp.camunda7.wiring;

import java.util.LinkedList;

import org.camunda.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.camunda.bpm.engine.impl.persistence.deploy.Deployer;
import org.camunda.bpm.engine.impl.persistence.entity.DeploymentEntity;

public class Camunda7TaskWiringPlugin extends AbstractProcessEnginePlugin {

    private final ProcessEntityAwareExpressionManager processEntityAwareExpressionManager;

    private final TaskWiringBpmnParseListener taskWiringBpmnParseListener;
    
    public Camunda7TaskWiringPlugin(
            final ProcessEntityAwareExpressionManager processEntityAwareExpressionManager,
            final TaskWiringBpmnParseListener taskWiringBpmnParseListener) {
        
        this.processEntityAwareExpressionManager = processEntityAwareExpressionManager;
        this.taskWiringBpmnParseListener = taskWiringBpmnParseListener;
        
    }

    @Override
    public void preInit(final ProcessEngineConfigurationImpl configuration) {

        configuration.setExpressionManager(processEntityAwareExpressionManager);

        if (configuration.getCustomPreBPMNParseListeners() == null) {
            configuration.setCustomPreBPMNParseListeners(
                    new LinkedList<>());
        }
        configuration
                .getCustomPreBPMNParseListeners()
                .add(taskWiringBpmnParseListener);
        
        // needed to pass workflow module id to bpmn parse listener
        if (configuration.getCustomPreDeployers() == null) {
            configuration.setCustomPreDeployers(new LinkedList<>());
        }
        configuration.getCustomPreDeployers().add(new Deployer() {
                @Override
                public void deploy(
                        final DeploymentEntity deployment) {
                    TaskWiringBpmnParseListener.workflowModuleId.set(deployment.getName());
                }
            });

    }

}
