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
 * <li>{@code droppedInVersionTwo} serves version 1 only - the task was dropped from the
 * model, and the method stays for the workflows still running on that version. It matches no
 * task of the deployed model, which must not fail the start;</li>
 * <li>{@code servedForAnUnknownVersion} names a version this engine does not hold, so it
 * never runs and the task it would serve is unserved in version 1.</li>
 * </ul>
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

  @WorkflowTask(taskDefinition = "droppedInVersionTwo", version = "1")
  public void droppedInVersionTwo(
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
