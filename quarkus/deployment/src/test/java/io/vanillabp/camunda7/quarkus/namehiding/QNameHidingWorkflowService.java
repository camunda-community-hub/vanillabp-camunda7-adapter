package io.vanillabp.camunda7.quarkus.namehiding;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * One method wired by the task definition 'chargeCard', and one wired by the element id
 * 'reviewOrder'. Neither name is hidden, so the start goes on and both methods run.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = QNameHidingAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "QNameHidingProcess"))
public class QNameHidingWorkflowService {

  @Inject
  ProcessService<QNameHidingAggregate> processService;

  public void startWorkflow(
      final String id) {

    final var aggregate = new QNameHidingAggregate();
    aggregate.setId(id);
    aggregate.setChargeCard("kept in the application");
    aggregate.setReviewOrder("shared with the engine");
    processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void chargeCard(
      final QNameHidingAggregate aggregate) {

    aggregate.setCharged(true);

  }

  @WorkflowTask(id = "reviewOrder")
  public void review(
      final QNameHidingAggregate aggregate) {

    aggregate.setReviewed(true);

  }

}
