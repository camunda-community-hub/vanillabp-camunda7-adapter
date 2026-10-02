package io.vanillabp.camunda7.it;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.MultiInstanceElement;
import io.vanillabp.spi.service.MultiInstanceIndex;
import io.vanillabp.spi.service.MultiInstanceTotal;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of the test about the levels a user task is told about.
 */
@Service
@WorkflowService(
    workflowAggregateClass = MiUserTaskAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "MiUserTaskLevels"))
public class MiUserTaskWorkflowService {

  private final ProcessService<MiUserTaskAggregate> processService;

  public MiUserTaskWorkflowService(
      final ProcessService<MiUserTaskAggregate> processService) {

    this.processService = processService;

  }

  @Transactional
  public MiUserTaskAggregate startWorkflow() {

    return processService.startWorkflow(new MiUserTaskAggregate());

  }

  /**
   * The method notified about the user task. It reads the levels the two ways an application
   * reads them: the named parameters of one element, and a resolver for the whole chain. The
   * two say the same thing on purpose, because a user task used to answer neither.
   *
   * @param aggregate The workflow aggregate
   * @param group The element of the subprocess this task runs in
   * @param positionIndex The round of the user task itself
   * @param positionTotal How many rounds that user task has
   * @param chain Every level the task was told about, outermost first
   */
  @WorkflowTask(id = "MUT_Review")
  public void reviewAPosition(
      final MiUserTaskAggregate aggregate,
      @MultiInstanceElement("MUT_Group") final String group,
      @MultiInstanceIndex("MUT_Review") final int positionIndex,
      @MultiInstanceTotal("MUT_Review") final int positionTotal,
      @MultiInstanceElement(resolverBean = MiUserTaskChainResolver.class) final String chain) {

    final var entry = "%s#%d/%d %s".formatted(group, positionIndex, positionTotal, chain);
    aggregate
        .setReported(
            aggregate.getReported() == null
                ? entry
                : aggregate.getReported()
                    + ","
                    + entry);

  }

}
