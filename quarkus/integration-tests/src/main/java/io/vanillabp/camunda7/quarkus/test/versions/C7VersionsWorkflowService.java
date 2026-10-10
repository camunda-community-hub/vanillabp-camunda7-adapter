package io.vanillabp.camunda7.quarkus.test.versions;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service of the old-process-versions test. Its three methods are the three
 * states an application can be in towards the versions its BPMS holds:
 * <ul>
 * <li>{@code keptInBothVersions} serves every version - the task survived into the deployed
 * model;</li>
 * <li>{@code droppedFromTheNewModel} serves version 2 only, the old model a node of the older
 * release deployed - the new model dropped the task, and the method stays for the workflows
 * still running on that version. It matches no task of the deployed model, which must not
 * fail the start;</li>
 * <li>{@code servedForAnUnknownVersion} names a version this engine does not hold, so it
 * never runs and the task it would serve is unserved in version 2.</li>
 * </ul>
 * <p>
 * The application deploys only the new model. It could not deploy the old one: a start
 * refuses the version it deploys while a task of it has no method for that version, and
 * 'servedForAnUnknownVersion' has none.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = C7VersionsAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "OldProcessVersionsProcess"))
public class C7VersionsWorkflowService {

  @Inject
  ProcessService<C7VersionsAggregate> processService;

  public C7VersionsAggregate startWorkflow() {

    return processService.startWorkflow(new C7VersionsAggregate());

  }

  @WorkflowTask(taskDefinition = "keptInBothVersions")
  public void keptInBothVersions(
      final C7VersionsAggregate aggregate) {

    aggregate.setServedBy("kept");

  }

  @WorkflowTask(taskDefinition = "droppedFromTheNewModel", version = "2")
  public void droppedFromTheNewModel(
      final C7VersionsAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setOpenTaskId(taskId);

  }

  @WorkflowTask(taskDefinition = "servedForAnUnknownVersion", version = "0")
  public void servedForAnUnknownVersion(
      final C7VersionsAggregate aggregate) {

    aggregate.setServedBy("never");

  }

}
