package io.vanillabp.camunda7.quarkus.sample;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The workflow service bound to the {@code TestProcess} BPMN process. Its presence
 * makes the VanillaBP Quarkus integration build a {@link ProcessService} bean for
 * {@link TestAggregate}.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = TestAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TestProcess"))
public class TestWorkflowService {

  @Inject
  ProcessService<TestAggregate> processService;

  public TestAggregate startWorkflow(
      final String content) {

    final var aggregate = new TestAggregate();
    aggregate.setContent(content);
    return processService.startWorkflow(aggregate);

  }

  @io.vanillabp.spi.service.WorkflowTask
  public void testTask(
      final TestAggregate aggregate) {

    aggregate.setContent("task-done");

  }

}
