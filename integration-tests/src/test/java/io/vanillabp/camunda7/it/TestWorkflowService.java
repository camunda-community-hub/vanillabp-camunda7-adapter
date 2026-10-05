package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The workflow service bound to the {@code TestProcess} BPMN process. Its presence makes
 * the VanillaBP Spring Boot integration build a {@link ProcessService} bean for
 * {@link TestAggregate} and associate it with the {@code c7-smoke-test} workflow module.
 */
@Service
@WorkflowService(
    workflowAggregateClass = TestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TestProcess"))
public class TestWorkflowService {

  private final ProcessService<TestAggregate> processService;

  public TestWorkflowService(
      final ProcessService<TestAggregate> processService) {

    this.processService = processService;

  }

  @io.vanillabp.spi.service.WorkflowTask
  public void testTask(
      final TestAggregate aggregate) {

    aggregate.setContent("task-done");

  }

}
