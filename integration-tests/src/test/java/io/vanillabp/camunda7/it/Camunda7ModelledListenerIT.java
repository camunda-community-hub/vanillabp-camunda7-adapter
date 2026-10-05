package io.vanillabp.camunda7.it;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Whether a listener somebody modelled really reaches a <code>@WorkflowTask</code> method of a
 * real engine, and what the boot says about a model carrying one.
 * <p>
 * This needs an engine and nothing else would do. For a
 * <code>camunda:delegateExpression</code> listener the engine expects the expression to yield a
 * listener object, and the object the adapter serves a TASK with is an activity behavior, which
 * the engine would refuse here. Nothing but a running engine proves that the right object
 * arrives, and version 1 got that right with a type version 2 no longer has.
 * <p>
 * The cancellation is the same kind of question. The model has a user task which nobody
 * completes and an interrupting boundary timer which takes it away, so the listener of that task
 * fires once and is then called a second time with CANCELED. Only an engine shows that the
 * listener VanillaBP attaches to the element really runs on a cancellation, because the engine
 * decides what a canceled execution reports about itself.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7ModelledListenerIT {

  private static final String LOCATION = "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/listeners";

  private static final String PROFILE = "--spring.profiles.active=modelled-listeners";

  private static final String DATABASE = "--spring.datasource.url=jdbc:h2:mem:c7-modelled-listeners-it;DB_CLOSE_DELAY=-1";

  @Test
  @DisplayName("Both listener forms reach a method, and a canceled element reaches the method serving its listener")
  public void bothListenerFormsReachAMethod(
      final CapturedOutput output) throws Exception {

    final var alreadyLogged = output.getAll().length();

    try (var application = new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        .run(
            LOCATION,
            PROFILE,
            DATABASE,
            "--vanillabp.adapters.c7.allow-listeners=true")) {

      final var workflowService = application.getBean(ModelledListenerWorkflowService.class);
      final var repository = application.getBean(ModelledListenerRepository.class);
      final var transactionTemplate = application.getBean(TransactionTemplate.class);

      final var aggregateId = transactionTemplate
          .execute(status -> workflowService.start(new ModelledListenerAggregate()).getId());
      Assertions.assertNotNull(aggregateId);

      final var deadline = System.currentTimeMillis() + 30_000;
      while (System.currentTimeMillis() < deadline) {
        final var aggregate = transactionTemplate.execute(status -> repository.findById(aggregateId).orElseThrow());
        if (aggregate.isTheEndWasReached() && aggregate.isTheEndWasDone()) {
          Assertions
              .assertTrue(
                  aggregate.isTheWorkWasDone(),
                  "the task before the end event ran as it always did");
          Assertions
              .assertTrue(
                  aggregate.isTheWaitBegan(),
                  "the listener of the user task fired when the task was created");
          Assertions
              .assertTrue(
                  aggregate.isTheWaitWasCanceled(),
                  "and the same method was told when the boundary timer took the task away, which "
                      + "is the moment the listener of the model never reaches by itself");
          return;
        }
        Thread.sleep(100);
      }

      final var aggregate = transactionTemplate.execute(status -> repository.findById(aggregateId).orElseThrow());
      Assertions
          .fail(
              ("the listeners of the end event did not both reach a method within 30 seconds: the "
                  + "camunda:expression one %s, the camunda:delegateExpression one %s, the task before them %s, "
                  + "the listener of the user task %s, its cancellation %s")
                  .formatted(
                      aggregate.isTheEndWasReached(),
                      aggregate.isTheEndWasDone(),
                      aggregate.isTheWorkWasDone(),
                      aggregate.isTheWaitBegan(),
                      aggregate.isTheWaitWasCanceled()));

    } finally {
      final var log = output.getAll().substring(alreadyLogged);
      Assertions
          .assertTrue(
              log.contains("MODELLED LISTENERS ARE SERVED"),
              () -> "and every boot of such a module says what it costs: "
                  + log);
    }

  }

  @Test
  @DisplayName("Without the property the same model does not boot")
  public void withoutThePropertyTheSameModelDoesNotBoot() {

    final var refused = Assertions
        .assertThrows(
            Exception.class,
            () -> new SpringApplicationBuilder(TestApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                    LOCATION,
                    PROFILE,
                    "--spring.datasource.url=jdbc:h2:mem:c7-modelled-listeners-refused-it;DB_CLOSE_DELAY=-1")
                .close());

    Assertions
        .assertTrue(
            messagesOf(refused).contains("allow-listeners"),
            () -> "the boot ends where the engine would evaluate the listener's expression itself: "
                + messagesOf(refused));

  }

  /**
   * Every message of a failure and of its causes, because a boot failure reaches a test wrapped
   * in whatever Spring wrapped it in.
   *
   * @param failure What the boot threw
   * @return The messages, joined
   */
  private static String messagesOf(
      final Throwable failure) {

    final var messages = new StringBuilder();
    for (var current = failure; current != null; current = current.getCause()) {
      messages.append(current.getMessage()).append('\n');
    }
    return messages.toString();

  }

}
