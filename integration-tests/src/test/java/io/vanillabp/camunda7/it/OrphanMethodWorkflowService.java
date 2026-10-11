package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * One method serves the task of <code>orphan-process.bpmn</code>. Nobody modelled the task
 * definition of the other one. It stands for a typo, or for a method left behind after a
 * model change.
 * <p>
 * It is a bean only under the profile of its own test. Every other test of this module
 * boots the same application. A workflow service which matches nothing would fail their
 * starts as well. That is exactly the check this class is here to trigger.
 */
@Service
@Profile("orphan-method")
@WorkflowService(
    workflowAggregateClass = OrphanMethodAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "OrphanProcess"))
public class OrphanMethodWorkflowService {

  @WorkflowTask
  public void orphanModelled() {

  }

  @WorkflowTask(taskDefinition = "activityNobodyModelled")
  public void orphanTypo() {

  }

}
