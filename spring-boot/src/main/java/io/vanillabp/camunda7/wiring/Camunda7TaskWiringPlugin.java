package io.vanillabp.camunda7.wiring;

import java.util.ArrayList;

import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParse;
import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParser;
import org.camunda.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.camunda.bpm.engine.impl.cfg.DefaultBpmnParseFactory;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;

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

        var preParseListeners = configuration.getCustomPreBPMNParseListeners();
        if (preParseListeners == null) {
            preParseListeners = new ArrayList<>();
            configuration.setCustomPreBPMNParseListeners(preParseListeners);
        }
        preParseListeners.add(taskWiringBpmnParseListener);

        // needed to pass workflow module id to bpmn parse listener
        configuration.setBpmnParseFactory(new DefaultBpmnParseFactory() {
            @Override
            public BpmnParse createBpmnParse(BpmnParser bpmnParser) {
                return new Camunda7WorkflowModuleAwareBpmnParse(bpmnParser);
            }
        });

    }

}
