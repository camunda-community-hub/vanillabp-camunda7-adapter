package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The application of the renamed-process integration test BEFORE the rename. It has one
 * BPMN process, under the id which the workflow of this test is started on.
 * <p>
 * Both halves of the test carry a Spring profile. They are two generations of one
 * application and must never boot together, because they declare the same workflow
 * aggregate with a different process to start. A workflow service is registered because it
 * is a bean. So a profile which is not active registers nothing. This also keeps this
 * scenario out of the other integration tests of this module.
 */
@Service
@Profile("rename-before")
@WorkflowService(
    workflowAggregateClass = RenamedProcessAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "RenamedProcessOld"))
public class RenamedBeforeWorkflowService {

  private final ProcessService<RenamedProcessAggregate> processService;

  public RenamedBeforeWorkflowService(
      final ProcessService<RenamedProcessAggregate> processService) {

    this.processService = processService;

  }

  public RenamedProcessAggregate startWorkflow() {

    return processService.startWorkflow(new RenamedProcessAggregate());

  }

  @WorkflowTask(taskDefinition = "renameStarted")
  public void renameStarted(
      final RenamedProcessAggregate aggregate) {

    aggregate.setStartedBy("before-the-rename");

  }

  @WorkflowTask(taskDefinition = "renameFinished")
  public void renameFinished(
      final RenamedProcessAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setFinishedBy("before-the-rename");
    aggregate.setOpenTaskId(taskId);

  }

}
