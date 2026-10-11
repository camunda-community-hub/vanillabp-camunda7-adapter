package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The application which deploys version 1 of the old-process-versions test. It serves every
 * task of that version, because a start refuses a version it deploys itself when a task of it
 * has no method for that version.
 * <p>
 * The application after it is {@link OldProcessVersionsWorkflowService}. That one no longer
 * serves the task 'servedForAnUnknownVersion' in version 1, which is what the old-versions check
 * has to report while a workflow still runs on version 1.
 * <p>
 * A bean only under the profile of its own generation: the generations declare the same
 * process and must never boot together.
 */
@Service
@Profile(OldProcessVersionsBeforeWorkflowService.PROFILE)
@WorkflowService(
    workflowAggregateClass = OldProcessVersionsAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "OldProcessVersionsProcess"))
public class OldProcessVersionsBeforeWorkflowService {

  /**
   * The profile of the boot which deploys version 1.
   */
  public static final String PROFILE = "old-process-versions-before";

  private final ProcessService<OldProcessVersionsAggregate> processService;

  public OldProcessVersionsBeforeWorkflowService(
      final ProcessService<OldProcessVersionsAggregate> processService) {

    this.processService = processService;

  }

  public OldProcessVersionsAggregate startWorkflow() {

    return processService.startWorkflow(new OldProcessVersionsAggregate());

  }

  @WorkflowTask(taskDefinition = "keptInBothVersions")
  public void keptInBothVersions(
      final OldProcessVersionsAggregate aggregate) {

    aggregate.setServedBy("kept");

  }

  @WorkflowTask(taskDefinition = "droppedInVersionTwo", version = "1")
  public void droppedInVersionTwo(
      final OldProcessVersionsAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setOpenTaskId(taskId);

  }

  /**
   * Never runs in this test: the workflow stays open in the task before it.
   *
   * @param aggregate The workflow aggregate
   */
  @WorkflowTask(taskDefinition = "servedForAnUnknownVersion", version = "1")
  public void servedInVersionOne(
      final OldProcessVersionsAggregate aggregate) {

    aggregate.setServedBy("version one");

  }

}
