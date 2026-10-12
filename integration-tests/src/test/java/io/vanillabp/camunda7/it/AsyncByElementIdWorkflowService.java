package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * A method which keeps its task open, wired by the ELEMENT id. The model names
 * <code>signTheContract</code> as the expression of the task. The method names the element
 * which that expression sits on. Both wirings serve the task, so both have to pass the
 * check about a task which cannot stay open.
 * <p>
 * It is a bean only under the profile of its own test, like every other workflow service
 * which must not reach the contexts of the other tests of this module.
 */
@Service
@Profile("async-by-element-id")
@WorkflowService(
    workflowAggregateClass = AsyncByElementIdAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "AsyncByElementIdProcess"))
public class AsyncByElementIdWorkflowService {

  @WorkflowTask(id = "ABI_Task")
  public void keepTheTaskOpen(
      final AsyncByElementIdAggregate aggregate,
      @TaskId final String taskId) {

  }

}
