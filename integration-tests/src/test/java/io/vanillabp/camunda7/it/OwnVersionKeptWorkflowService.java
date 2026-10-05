package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The application AFTER the upgrade, which kept the methods of version 1 next to the ones
 * version 2 names. A workflow started on version 1 has to reach the methods it names, and
 * one started on version 2 the methods version 2 names.
 * <p>
 * The methods of version 1 say which version they serve. Without that the start refuses
 * them, because they match no task of the model this boot deploys.
 * <p>
 * A bean only under the profile of its own generation: the generations declare the same
 * process and must never boot together.
 */
@Service
@Profile("own-version-kept")
@WorkflowService(
    workflowAggregateClass = OwnVersionAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "OwnVersionProcess"))
public class OwnVersionKeptWorkflowService {

  private final ProcessService<OwnVersionAggregate> processService;

  private final OwnVersionRepository repository;

  public OwnVersionKeptWorkflowService(
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

  @WorkflowTask(taskDefinition = "ownVersionOldExpression", version = "1")
  public void ownVersionOldExpression(
      final OwnVersionAggregate aggregate) {

    aggregate.setExpressionServedBy("old");

  }

  @WorkflowTask(taskDefinition = "ownVersionOldListener", version = "1")
  public void ownVersionOldListener(
      final OwnVersionAggregate aggregate) {

    aggregate.setListenerServedBy("old");

  }

  @WorkflowTask(taskDefinition = "ownVersionOldDelegate", version = "1")
  public void ownVersionOldDelegate(
      final OwnVersionAggregate aggregate) {

    aggregate.setDelegateServedBy("old");

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
