package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The workflow service of {@code ViewerParentProcess}. The test of the viewer and history
 * API inspects this workflow. The service has no {@code @WorkflowTask} methods. The
 * process holds only a call activity, and its called process waits in a timer event.
 */
@Service
@WorkflowService(
    workflowAggregateClass = ViewerTestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "ViewerParentProcess"))
public class ViewerTestWorkflowService {

  private final ProcessService<ViewerTestAggregate> processService;

  public ViewerTestWorkflowService(
      final ProcessService<ViewerTestAggregate> processService) {

    this.processService = processService;

  }

}
