package io.vanillabp.camunda7.service.jobs.startprocess;

import org.camunda.bpm.engine.impl.interceptor.Command;
import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.persistence.entity.MessageEntity;

public class StartProcessCommand implements Command<String> {

    static final String TYPE = "VBP_StartProcess";

    private final StartProcessJobHandlerConfiguration configuration;

    public StartProcessCommand(
            final String workflowModuleId,
            final String bpmnProcessId,
            final String businessKey) {

        this.configuration = new StartProcessJobHandlerConfiguration(
                workflowModuleId,
                bpmnProcessId,
                businessKey);

    }

    @Override
    public String execute(
            final CommandContext commandContext) {

        final var entity = new MessageEntity();

        entity.init(commandContext);
        entity.setJobHandlerType(TYPE);
        entity.setJobHandlerConfiguration(configuration);

        commandContext.getJobManager().send(entity);

        return entity.getId();

    }

}
