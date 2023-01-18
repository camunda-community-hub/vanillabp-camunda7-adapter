package org.camunda.community.vanillabp.c7.wiring;

import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParser;
import org.camunda.bpm.engine.impl.persistence.entity.DeploymentEntity;

public class Camunda7WorkflowModuleAwareBpmnParse extends BpmnParse {

    private static final ThreadLocal<String> workflowModuleId = new ThreadLocal<>();

    public Camunda7WorkflowModuleAwareBpmnParse(BpmnParser parser) {

        super(parser);

    }

    @Override
    public BpmnParse deployment(DeploymentEntity deployment) {

        workflowModuleId.set(deployment.getName());
        return super.deployment(deployment);

    }

    public static String getWorkflowModuleId() {

        return workflowModuleId.get();

    }

}
