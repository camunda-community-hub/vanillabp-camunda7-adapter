package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A model whose elements name one thing and whose methods name another, run against a
 * real engine.
 * <p>
 * The service task is wired by <code>camunda:delegateExpression</code> and its method
 * carries <code>&#64;WorkflowTask(id = ...)</code>. The user task carries a form key and
 * its method is wired by the element id as well. Both wirings pass the validation while
 * the application boots, so both have to work while a workflow runs. They did not: the
 * service task failed every delivery until the job executor made an incident of it, and
 * the user-task notification was skipped without a word, which is worse because nothing
 * happens at all.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7WiredByElementIdIT {

  @Test
  @DisplayName("A service task and a user task wired by the element id are both served")
  public void bothElementsAreServed() throws Exception {

    final var application = new SpringApplicationBuilder(TestApplication.class)
        .run(
            "--spring.profiles.active=wired-by-element-id",
            "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/wired-by-element-id",
            "--spring.datasource.url=jdbc:h2:mem:c7-wired-by-element-id;DB_CLOSE_DELAY=-1");
    try {
      final var repository = application.getBean(WiredByElementIdRepository.class);
      application
          .getBean(org.springframework.transaction.support.TransactionTemplate.class)
          .executeWithoutResult(
              status -> application
                  .getBean(WiredByElementIdWorkflowService.class)
                  .startWorkflow("4711"));

      awaitUntil(
          application,
          () -> repository
              .findById("4711")
              .map(aggregate -> aggregate.getServiceTaskRanAs() != null)
              .orElse(Boolean.FALSE),
          "the service task wired by an expression nobody names to reach the method naming its element");
      awaitUntil(
          application,
          () -> repository
              .findById("4711")
              .map(aggregate -> aggregate.getUserTaskId() != null)
              .orElse(Boolean.FALSE),
          "the user task carrying a form key to notify the method naming its element");

      final var aggregate = repository.findById("4711").orElseThrow();
      assertEquals("by-element-id", aggregate.getServiceTaskRanAs());
      assertNotNull(
          aggregate.getUserTaskId(),
          "the notification carries the engine's task id, which is what completes the task later");
    } finally {
      application.close();
    }

  }

  /**
   * Waits for something the engine brings about. Generous on purpose: in a full build
   * this class shares its machine with the other engines of this module, and a deadline
   * close to what a quiet machine needs fails while nothing is wrong. Reading the
   * repository needs a transaction of the test's own.
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

}
