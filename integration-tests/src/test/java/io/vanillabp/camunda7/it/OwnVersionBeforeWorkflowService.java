package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The application BEFORE the upgrade. It deploys version 1 of the process, and its methods
 * are the ones version 1 names.
 * <p>
 * A bean only under the profile of its own generation: the generations declare the same
 * process and must never boot together.
 */
@Service
@Profile("own-version-before")
@WorkflowService(
    workflowAggregateClass = OwnVersionAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "OwnVersionProcess"))
public class OwnVersionBeforeWorkflowService {

  private final ProcessService<OwnVersionAggregate> processService;

  private final OwnVersionRepository repository;

  public OwnVersionBeforeWorkflowService(
      final ProcessService<OwnVersionAggregate> processService,
      final OwnVersionRepository repository) {

    this.processService = processService;
    this.repository = repository;

  }

  public OwnVersionAggregate startWorkflow() {

    return processService.startWorkflow(new OwnVersionAggregate());

  }

  public void continueWorkflow(
      final Long id) {

    processService.correlateMessage(repository.findById(id).orElseThrow(), "OwnVersionContinue");

  }

  @WorkflowTask(taskDefinition = "ownVersionOldExpression")
  public void ownVersionOldExpression(
      final OwnVersionAggregate aggregate) {

    aggregate.setExpressionServedBy("old");

  }

  @WorkflowTask(taskDefinition = "ownVersionOldListener")
  public void ownVersionOldListener(
      final OwnVersionAggregate aggregate) {

    aggregate.setListenerServedBy("old");

  }

  @WorkflowTask(taskDefinition = "ownVersionOldDelegate")
  public void ownVersionOldDelegate(
      final OwnVersionAggregate aggregate) {

    aggregate.setDelegateServedBy("old");

  }

}
