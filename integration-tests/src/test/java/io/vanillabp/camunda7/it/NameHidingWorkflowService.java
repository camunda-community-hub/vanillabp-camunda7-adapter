package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * One method wired by the task definition 'chargeCard', and one wired by the element id
 * 'reviewOrder'. The tests add a bean of one of these names and look at what the start
 * makes of it.
 */
@Service
@Profile("name-hiding")
@WorkflowService(
    workflowAggregateClass = NameHidingAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "NameHidingProcess"))
public class NameHidingWorkflowService {

  private final ProcessService<NameHidingAggregate> processService;

  public NameHidingWorkflowService(
      final ProcessService<NameHidingAggregate> processService) {

    this.processService = processService;

  }

  public void startWorkflow(
      final String id) {

    final var aggregate = new NameHidingAggregate();
    aggregate.setId(id);
    aggregate.setChargeCard("kept in the application");
    aggregate.setReviewOrder("shared with the engine");
    processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void chargeCard(
      final NameHidingAggregate aggregate) {

    aggregate.setCharged(true);

  }

  @WorkflowTask(id = "reviewOrder")
  public void review(
      final NameHidingAggregate aggregate) {

    aggregate.setReviewed(true);

  }

}
