package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the ENGINE does on its own with a BPMN process id the application only DECLARES -
 * the old id of a renamed process - against a real embedded engine and across two
 * generations of one application. The engine keeps the old model's repeating timer and
 * its signal subscription, so it keeps STARTING workflows under the old id while the
 * renamed application runs. Every one of them has to be a full VanillaBP workflow: the
 * aggregate built through <code>&#64;WorkflowStartedByBpms</code> (a timer start telling
 * its kind, a signal start telling the PLAIN signal name of a model only the engine
 * holds), the task after the start event served, and the end reported to
 * <code>&#64;WorkflowEnded</code>.
 * <p>
 * The module scopes its identifiers by prefix (<code>use-prefix</code>), which is the
 * mode without a tenant: every listener finds the workflow module by what the wiring
 * registered for the engine's definition key, and for the old id that registration has
 * to exist BEFORE the engine parses the old definitions - parsing is when the
 * end-of-workflow listener is attached or lost for good.
 * <p>
 * The second generation's model has NO timer start on purpose: a workflow a timer
 * starts while that generation runs therefore belongs to the old id, which is how the
 * test tells the two ids apart. Both boots share ONE in-memory database, like a real
 * upgrade does.
 */
@ExtendWith(SuppressOutputExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Camunda7DeclaredIdRuntimeIT {

  private static final String DATABASE = "--spring.datasource.url=jdbc:h2:mem:c7-declared-runtime;DB_CLOSE_DELAY=-1";

  @Test
  @Order(1)
  @DisplayName("The first generation deploys the process the engine starts on its own")
  public void theFirstGenerationLeavesItsDeploymentBehind() throws Exception {

    final var application = boot("decl-before", "v1");
    try {
      final var repository = application.getBean(DeclaredRuntimeRepository.class);
      // the repeating timer fires and the whole path works while the id is still
      // deployed - the baseline the second generation has to keep
      awaitUntil(
          application,
          () -> repository
              .findAll()
              .stream()
              .anyMatch(aggregate -> "TIMER".equals(aggregate.getStartedAs()) && "before-the-rename"
                  .equals(aggregate.getProcessedBy())),
          "the timer of the deployed process to start a workflow");
    } finally {
      application.close();
    }

  }

  @Test
  @Order(2)
  @DisplayName("A timer start of the declared-only id reaches @WorkflowStartedByBpms, and the end is reported")
  public void aTimerStartOfTheDeclaredIdIsAFullWorkflow() throws Exception {

    final var application = boot("decl-after", "v2");
    try {
      final var repository = application.getBean(DeclaredRuntimeRepository.class);

      // the engine's timer keeps firing for the OLD id (the new model has no timer
      // start), and each generation writes its own name into the aggregate's id. So an
      // aggregate carrying this generation's name proves the start ran through
      // @WorkflowStartedByBpms of the renamed application
      awaitUntil(
          application,
          () -> aTimerWorkflowOfThisGeneration(repository).isPresent(),
          "the engine's timer to start a workflow under the declared-only id");
      final var started = aTimerWorkflowOfThisGeneration(repository).orElseThrow();

      awaitUntil(
          application,
          () -> "after-the-rename"
              .equals(repository.findById(started.getId()).orElseThrow().getProcessedBy()),
          "the task after the timer start to be served by the renamed application");
      awaitUntil(
          application,
          () -> repository.findById(started.getId()).orElseThrow().getEndedAs() != null,
          "the end of the timer-started workflow to be reported");
      assertEquals(
          "COMPLETED/after",
          repository.findById(started.getId()).orElseThrow().getEndedAs(),
          "the end has to reach the renamed application's @WorkflowEnded method");

      // the OLD generation's signal: only the model the engine still holds under the
      // declared-only id subscribes to it, and the start has to be told the PLAIN
      // signal name of that model - which nobody registered while deploying, because
      // nothing was deployed under the id
      application
          .getBean(org.springframework.transaction.support.TransactionTemplate.class)
          .executeWithoutResult(
              status -> application.getBean(DeclaredRuntimeAfterWorkflowService.class).fireTheOldSignal());
      awaitUntil(
          application,
          () -> repository
              .findAll()
              .stream()
              .anyMatch(aggregate -> "SIGNAL".equals(aggregate.getStartedAs()) && "DeclRuntimeSignal"
                  .equals(aggregate.getSignalName()) && (aggregate.getEndedAs() != null)),
          "the signal start of the declared-only id to run a full workflow, told its plain "
              + "signal name");
    } finally {
      application.close();
    }

  }

  /**
   * A workflow the timer started while THIS generation of the application runs: the
   * @WorkflowStartedByBpms method of each generation writes its own name into the id.
   */
  private static Optional<DeclaredRuntimeAggregate> aTimerWorkflowOfThisGeneration(
      final DeclaredRuntimeRepository repository) {

    return repository
        .findAll()
        .stream()
        .filter(aggregate -> "TIMER".equals(aggregate.getStartedAs()))
        .filter(aggregate -> aggregate.getId().startsWith("declared-runtime-after-"))
        .findFirst();

  }

  /**
   * Waits for something the engine's job executor has to bring about. Generous on
   * purpose: in a full build this class shares its machine with the other engines of
   * this module, and a deadline close to what a quiet machine needs fails while nothing
   * is wrong. Reading the repository needs a transaction of the test's own.
   */
  private static void awaitUntil(
      final ConfigurableApplicationContext application,
      final Supplier<Boolean> condition,
      final String whatDidNotHappen) throws Exception {

    final var transaction = application
        .getBean(org.springframework.transaction.support.TransactionTemplate.class);
    final var deadline = System.currentTimeMillis() + 60_000;
    while (!Boolean.TRUE.equals(transaction.execute(status -> condition.get()))) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(whatDidNotHappen);
      }
      Thread.sleep(100);
    }

  }

  /**
   * One generation of the application, with the module's identifiers scoped by
   * prefix - the mode without a tenant, where every way back from the engine's
   * definition key is the registration of the wiring.
   */
  private static ConfigurableApplicationContext boot(
      final String profile,
      final String bpmnVersion) {

    final var boot = new ArrayList<String>();
    boot.add(DATABASE);
    boot.add("--spring.profiles.active="
        + profile);
    // the aggregates of the first boot are what the second one continues on, so the
    // schema is kept rather than recreated
    boot.add("--spring.jpa.hibernate.ddl-auto=update");
    boot.add("--vanillabp.adapters.c7.name-clash-avoidance=use-prefix");
    boot
        .add("--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/declared-runtime/%s"
            .formatted(bpmnVersion));
    return new SpringApplicationBuilder(TestApplication.class).run(boot.toArray(String[]::new));

  }

}
