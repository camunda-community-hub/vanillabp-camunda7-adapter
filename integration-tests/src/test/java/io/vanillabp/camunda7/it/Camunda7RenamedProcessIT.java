package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Renaming a BPMN process, against a real engine and across two generations of one
 * application. The first generation deploys the process under its old id and starts a
 * workflow, which then waits for a message. The second deploys the same model under the NEW
 * id and declares the old one as a secondary process. The workflow started before the
 * rename has to run to its end through the methods of that second application.
 * <p>
 * Camunda 7 evaluates the expressions of the model which a workflow was STARTED with. So
 * only an engine can answer this test. The second application deploys nothing under the old
 * id. Its EL resolver needs the connectables of a model which exists only in the repository
 * of the engine. That includes the way each task is wired there. The last task of this
 * process is a <code>camunda:delegateExpression</code> and has to STAY OPEN until the
 * application completes it. The first one completes when its handler returns. Nothing
 * outside a model can be asked which of the two a task is. That is why the adapter reads
 * the models which the engine holds.
 * <p>
 * The workflow module keeps the name-clash-avoidance mode of every other test of this
 * module (<code>by-adapter</code>, a tenant per module). An application which upgrades from
 * version 1 arrives with that mode.
 * <p>
 * Both boots share ONE in-memory database. <code>DB_CLOSE_DELAY=-1</code> keeps it alive
 * across them. The second application continues on the deployment in the engine and on the
 * workflow aggregate which the first application wrote.
 */
@ExtendWith(SuppressOutputExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Camunda7RenamedProcessIT {

  private static final String DATABASE = "--spring.datasource.url=jdbc:h2:mem:c7-renamed-process;DB_CLOSE_DELAY=-1";

  /**
   * The workflow started by the first application. The second one reads it from the same
   * database.
   */
  static Long aggregateId;

  @Test
  @Order(1)
  @DisplayName("A workflow is started under the old process id and waits for its message")
  public void aWorkflowIsStartedUnderTheOldId() throws Exception {

    final var application = boot("rename-before", "v1");
    try {
      final var workflowService = application.getBean(RenamedBeforeWorkflowService.class);
      final var repository = application.getBean(RenamedProcessRepository.class);
      final var aggregate = application
          .getBean(TransactionTemplate.class)
          .execute(status -> workflowService.startWorkflow());
      aggregateId = aggregate.getId();

      awaitUntil(
          () -> repository.findById(aggregateId).orElseThrow().getStartedBy() != null,
          "the workflow of the old process id did not reach its first task");
      assertEquals(
          "before-the-rename",
          repository.findById(aggregateId).orElseThrow().getStartedBy(),
          "the application before the rename served the first task");
    } finally {
      application.close();
    }

  }

  @Test
  @Order(2)
  @DisplayName("The renamed application finishes the workflow which runs under the old id")
  public void theWorkflowOfTheOldIdIsFinishedAfterTheRename(
      final CapturedOutput output) throws Exception {

    assertNotNull(aggregateId, "the workflow of the first case has to exist");

    final var beforeTheBoot = output.getAll().length();
    final var application = boot("rename-after", "v2");
    try {
      final var workflowService = application.getBean(RenamedAfterWorkflowService.class);
      final var repository = application.getBean(RenamedProcessRepository.class);
      final var transaction = application.getBean(TransactionTemplate.class);

      // the message reaches a subscription which the FIRST application created, under the
      // id which this application does not deploy any more
      transaction.executeWithoutResult(status -> workflowService.continueWorkflow(aggregateId));

      awaitUntil(
          () -> repository.findById(aggregateId).orElseThrow().getOpenTaskId() != null,
          "the workflow started under the old process id did not reach its last task");
      assertEquals(
          "after-the-rename",
          repository.findById(aggregateId).orElseThrow().getFinishedBy(),
          "the methods of the renamed application served the workflow of the old id");

      // the task is still open. The engine does that only for a task which the OLD model
      // wired by 'camunda:delegateExpression'. An expression task would have completed when
      // the handler returned.
      transaction.executeWithoutResult(status -> workflowService.completeOpenTask(aggregateId));
      awaitUntil(
          () -> noWorkflowIsRunning(application),
          "the workflow of the old process id did not end after its open task was completed");

      // and the start said so. A developer reads that before any of this happens, not
      // afterwards.
      final var reported = output.getAll().substring(beforeTheBoot);
      assertTrue(
          reported.contains("declared BPMN process 'RenamedProcessOld'"),
          () -> "the start has to say that the old id was wired: "
              + reported);
      assertTrue(
          reported.contains("keep being served"),
          () -> "and what that means for the workflows running on it: "
              + reported);
    } finally {
      application.close();
    }

  }

  /**
   * Whether the engine still holds a workflow of the old process id. This says that the
   * workflow of this test ran to its end.
   */
  private static boolean noWorkflowIsRunning(
      final ConfigurableApplicationContext application) {

    return application
        .getBean(org.camunda.bpm.engine.RuntimeService.class)
        .createProcessInstanceQuery()
        .processDefinitionKey("RenamedProcessOld")
        .active()
        .count() == 0;

  }

  /**
   * Waits for something which the job executor of the engine has to bring about. The
   * deadline is generous on purpose. In a full build this class shares its machine with the
   * other engines of this module. A deadline close to what a quiet machine needs fails
   * while nothing is wrong.
   */
  private static void awaitUntil(
      final java.util.function.BooleanSupplier condition,
      final String whatDidNotHappen) throws Exception {

    final var deadline = System.currentTimeMillis() + 60_000;
    while (!condition.getAsBoolean()) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(whatDidNotHappen);
      }
      Thread.sleep(100);
    }

  }

  /**
   * One generation of the application: the workflow service of that generation, which a
   * Spring profile picks, and the BPMN it deploys.
   */
  private static ConfigurableApplicationContext boot(
      final String profile,
      final String bpmnVersion) {

    final var boot = new ArrayList<String>();
    boot.add(DATABASE);
    boot.add("--spring.profiles.active="
        + profile);
    // the second boot continues on the aggregate of the first boot. So the schema is kept,
    // not recreated.
    boot.add("--spring.jpa.hibernate.ddl-auto=update");
    boot
        .add("--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/renamed-process/%s"
            .formatted(bpmnVersion));
    return new SpringApplicationBuilder(TestApplication.class).run(boot.toArray(String[]::new));

  }

}
