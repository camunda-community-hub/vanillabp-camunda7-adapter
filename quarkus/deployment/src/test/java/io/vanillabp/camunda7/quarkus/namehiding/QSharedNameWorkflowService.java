package io.vanillabp.camunda7.quarkus.namehiding;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Serves the task definition 'chargeCard' of a process whose aggregate shares an attribute
 * of the same name. The start refuses it, so the method is never called.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = QSharedNameAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "QSharedNameProcess"))
public class QSharedNameWorkflowService {

  @WorkflowTask
  public void chargeCard(
      final QSharedNameAggregate aggregate) {

    // never called: the start ends over the name of this method's task definition

  }

}
