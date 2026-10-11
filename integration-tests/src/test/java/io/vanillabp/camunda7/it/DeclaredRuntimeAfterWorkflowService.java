package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The same application AFTER the rename. The BPMN process carries the new id, and the old
 * id is declared as a secondary process. The ENGINE keeps starting workflows under the old
 * id, by the repeating timer of the model it still holds and by its signal subscription.
 * Every one of them has to reach these methods: the aggregate built by
 * {@code @WorkflowStartedByBpms}, the task, and the end notification.
 */
@Service
@Profile("decl-after")
@WorkflowService(
    workflowAggregateClass = DeclaredRuntimeAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "DeclaredRuntimeNew"),
    secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = "DeclaredRuntimeOld"))
public class DeclaredRuntimeAfterWorkflowService {

  private final ProcessService<DeclaredRuntimeAggregate> processService;

  public DeclaredRuntimeAfterWorkflowService(
      final ProcessService<DeclaredRuntimeAggregate> processService) {

    this.processService = processService;

  }

  /**
   * Broadcasts the signal of the OLD generation. Only the model which the engine holds
   * under the old id subscribes to it. So the workflow it starts runs under the id which is
   * only declared.
   */
  public void fireTheOldSignal() {

    processService.sendSignal("DeclRuntimeSignal");

  }

  /**
   * Builds the workflow aggregate of a workflow the engine started under this id.
   *
   * @param trigger What the engine fired
   * @return The workflow aggregate of the started workflow
   */
  @WorkflowStartedByBpms
  public DeclaredRuntimeAggregate startedByBpms(
      final BpmsStartTrigger trigger) {

    final var aggregate = new DeclaredRuntimeAggregate();
    // a name of the application's own, which the engine holds as the business key. The
    // generation is part of it. That is how the test tells the workflows this application
    // started from the ones its predecessor started.
    aggregate.setId("declared-runtime-after-"
        + java.util.UUID.randomUUID());
    aggregate.setStartedAs(trigger.kind().name());
    aggregate.setSignalName(trigger.signalName());
    return aggregate;

  }

  @WorkflowTask(taskDefinition = "declaredRuntimeTask")
  public void declaredRuntimeTask(
      final DeclaredRuntimeAggregate aggregate) {

    aggregate.setProcessedBy("after-the-rename");

  }

  @io.vanillabp.spi.service.WorkflowEnded
  public void workflowEnded(
      final DeclaredRuntimeAggregate aggregate,
      final io.vanillabp.spi.service.WorkflowEnd end) {

    aggregate.setEndedAs("%s/after".formatted(end.kind()));

  }

}
