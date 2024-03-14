package io.vanillabp.camunda7.service.jobs.startprocess;

import org.camunda.bpm.engine.impl.interceptor.CommandContext;
import org.camunda.bpm.engine.impl.jobexecutor.JobHandler;
import org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity;
import org.camunda.bpm.engine.impl.persistence.entity.JobEntity;

import java.util.regex.Pattern;

public class StartProcessJobHandler implements JobHandler<StartProcessJobHandlerConfiguration> {

    private static final Pattern SPLITTER = Pattern.compile("([^\n]+)\n([^\n]+)\n(.*)");

    @Override
    public String getType() {
        return StartProcessCommand.TYPE;
    }

    @Override
    public void execute(
            final StartProcessJobHandlerConfiguration configuration,
            final ExecutionEntity execution,
            final CommandContext commandContext,
            final String tenantId) {

        final var command = commandContext
                .getProcessEngineConfiguration()
                .getProcessEngine()
                .getRuntimeService()
                .createProcessInstanceByKey(configuration.getBpmnProcessId())
                .businessKey(configuration.getBusinessKey());
        (configuration.getTenantId() == null
                ? command.processDefinitionWithoutTenantId()
                : command.processDefinitionTenantId(configuration.getTenantId()))
                .execute();

    }

    @Override
    public StartProcessJobHandlerConfiguration newConfiguration(
            final String canonicalString) {

        if (canonicalString == null) {
            return null;
        }

        final var splitter = SPLITTER.matcher(canonicalString);
        if (!splitter.matches()) {
            return null;
        }

        return new StartProcessJobHandlerConfiguration(
                splitter.group(1),
                splitter.group(2),
                splitter.group(3));

    }

    @Override
    public void onDelete(
            final StartProcessJobHandlerConfiguration configuration,
            final JobEntity jobEntity) {
        // nothing to do
    }
}
