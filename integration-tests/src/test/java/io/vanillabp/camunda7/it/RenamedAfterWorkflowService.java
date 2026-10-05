package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The same application AFTER the rename: the BPMN process carries the new id, and the old
 * one is declared as a secondary process so the workflow which is still on it keeps being
 * served by these very methods.
 * <p>
 * Nothing else about the class says that a rename happened, which is the point of the
 * feature: the aggregate is the same, the tasks are the same, and the workflow started
 * under the old id ends under it.
 */
@Service
@Profile("rename-after")
@WorkflowService(
    workflowAggregateClass = RenamedProcessAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "RenamedProcessNew"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "RenamedProcessOld"))
public class RenamedAfterWorkflowService {

  private final ProcessService<RenamedProcessAggregate> processService;

  private final RenamedProcessRepository repository;

  public RenamedAfterWorkflowService(
      final ProcessService<RenamedProcessAggregate> processService,
      final RenamedProcessRepository repository) {

    this.processService = processService;
    this.repository = repository;

  }

  public void continueWorkflow(
      final Long id) {

    processService.correlateMessage(repository.findById(id).orElseThrow(), "RenameContinue");

  }

  public void completeOpenTask(
      final Long id) {

    final var aggregate = repository.findById(id).orElseThrow();
    processService.completeTask(aggregate, aggregate.getOpenTaskId());

  }

  @WorkflowTask(taskDefinition = "renameStarted")
  public void renameStarted(
      final RenamedProcessAggregate aggregate) {

    aggregate.setStartedBy("after-the-rename");

  }

  @WorkflowTask(taskDefinition = "renameFinished")
  public void renameFinished(
      final RenamedProcessAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setFinishedBy("after-the-rename");
    aggregate.setOpenTaskId(taskId);

  }

}
