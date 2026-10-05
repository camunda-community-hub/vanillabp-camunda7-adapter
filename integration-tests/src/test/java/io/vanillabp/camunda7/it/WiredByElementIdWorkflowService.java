package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Two methods, both wired by the BPMN ELEMENT id while the model names something else on
 * the element: an expression on the service task, a form key on the user task. The
 * wiring validation accepts both, so a delivery has to find both.
 * <p>
 * A bean only under the profile of its own test, like every other workflow service which
 * must not reach the contexts of the other tests of this module.
 */
@Service
@Profile("wired-by-element-id")
@WorkflowService(
    workflowAggregateClass = WiredByElementIdAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "WiredByElementIdProcess"))
public class WiredByElementIdWorkflowService {

  private final ProcessService<WiredByElementIdAggregate> processService;

  public WiredByElementIdWorkflowService(
      final ProcessService<WiredByElementIdAggregate> processService) {

    this.processService = processService;

  }

  public void startWorkflow(
      final String id) {

    final var aggregate = new WiredByElementIdAggregate();
    aggregate.setId(id);
    processService.startWorkflow(aggregate);

  }

  @WorkflowTask(id = "IW_Task")
  public void theServiceTaskOfTheModel(
      final WiredByElementIdAggregate aggregate) {

    aggregate.setServiceTaskRanAs("by-element-id");

  }

  @WorkflowTask(id = "IW_UserTask")
  public void theUserTaskOfTheModel(
      final WiredByElementIdAggregate aggregate,
      @TaskId final String taskId) {

    aggregate.setUserTaskId(taskId);

  }

}
