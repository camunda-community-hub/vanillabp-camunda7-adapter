package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Serves the task definition 'chargeCard' of a process whose aggregate shares an attribute
 * of the same name. The start refuses it, so the method is never called.
 */
@Service
@Profile("name-hiding-attribute")
@WorkflowService(
    workflowAggregateClass = SharedNameAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "SharedNameProcess"))
public class SharedNameWorkflowService {

  @WorkflowTask
  public void chargeCard(
      final SharedNameAggregate aggregate) {

    // never called: the start ends over the name of this method's task definition

  }

}
