package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the timer-start integration test. The engine starts this
 * workflow, so the aggregate is built here and nowhere else, and the task following the
 * start event has to find it.
 */
@Service
@WorkflowService(
    workflowAggregateClass = TimerStartTestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TimerStartProcess"))
public class TimerStartTestWorkflowService {

  /**
   * Builds the workflow aggregate of the workflow the timer started.
   *
   * @param trigger What the engine fired
   * @return The workflow aggregate of the started workflow
   */
  @WorkflowStartedByBpms
  public TimerStartTestAggregate aggregateOfTimerStart(
      final BpmsStartTrigger trigger) {

    final var aggregate = new TimerStartTestAggregate();
    // a name of the application's own, which the engine holds as the business key from
    // here on - a second delivery of the same start finds the workflow under it
    aggregate.setId("timer-start-"
        + java.util.UUID.randomUUID());
    aggregate.setStartedBy(trigger.kind().name());
    return aggregate;

  }

  /**
   * The workflow started by the timer also reports its end.
   */
  @WorkflowEnded
  public void workflowEnded(
      final TimerStartTestAggregate aggregate,
      final WorkflowEnd end) {

    aggregate.setEndedAs("%s/%s".formatted(end.kind(), end.endEventId()));

  }

  @WorkflowTask(taskDefinition = "recordStart")
  public void recordStart(
      final TimerStartTestAggregate aggregate) {

    aggregate.setProcessedBy("recordStart");

  }

}
