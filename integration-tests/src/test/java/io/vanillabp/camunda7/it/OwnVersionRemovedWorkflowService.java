package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The application AFTER the upgrade, which removed the methods of version 1. A workflow
 * still running on version 1 must not reach a method version 2 names at the same element:
 * it ends in an incident which names what is missing.
 * <p>
 * A bean only under the profile of its own generation: the generations declare the same
 * process and must never boot together.
 */
@Service
@Profile("own-version-removed")
@WorkflowService(
    workflowAggregateClass = OwnVersionAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "OwnVersionProcess"))
public class OwnVersionRemovedWorkflowService {

  private final ProcessService<OwnVersionAggregate> processService;

  private final OwnVersionRepository repository;

  public OwnVersionRemovedWorkflowService(
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

  @WorkflowTask(taskDefinition = "ownVersionNewExpression")
  public void ownVersionNewExpression(
      final OwnVersionAggregate aggregate) {

    aggregate.setExpressionServedBy("new");

  }

  @WorkflowTask(taskDefinition = "ownVersionNewListener")
  public void ownVersionNewListener(
      final OwnVersionAggregate aggregate) {

    aggregate.setListenerServedBy("new");

  }

  @WorkflowTask(taskDefinition = "ownVersionNewDelegate")
  public void ownVersionNewDelegate(
      final OwnVersionAggregate aggregate) {

    aggregate.setDelegateServedBy("new");

  }

}
