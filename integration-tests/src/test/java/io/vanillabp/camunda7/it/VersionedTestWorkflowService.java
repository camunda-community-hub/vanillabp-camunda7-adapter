package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the process-version integration test: one BPMN
 * task served by two methods, told apart by the version of the deployed process
 * definition - the first version by its number, the second one by the
 * <code>camunda:versionTag</code> its model carries.
 */
@Service
@WorkflowService(
    workflowAggregateClass = VersionedTestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "VersionedProcess"))
public class VersionedTestWorkflowService {

  private final ProcessService<VersionedTestAggregate> processService;

  public VersionedTestWorkflowService(
      final ProcessService<VersionedTestAggregate> processService) {

    this.processService = processService;

  }

  public VersionedTestAggregate startWorkflow() {

    return processService.startWorkflow(new VersionedTestAggregate());

  }

  @WorkflowTask(taskDefinition = "versionedTask", version = "1")
  public void firstVersion(
      final VersionedTestAggregate aggregate) {

    aggregate.setServedBy("firstVersion");

  }

  @WorkflowTask(taskDefinition = "versionedTask", version = "release-2")
  public void taggedVersion(
      final VersionedTestAggregate aggregate) {

    aggregate.setServedBy("taggedVersion");

  }

}
