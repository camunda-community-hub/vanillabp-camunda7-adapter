package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A Spring bean or a shared attribute of the workflow aggregate named like a task
 * definition. The engine asks both before it asks VanillaBP, so either one would take the
 * expression away from the <code>&#64;WorkflowTask</code> method. The start ends instead,
 * and the message says what collides with what.
 * <p>
 * The third test is the model which must keep working: an attribute of that name which is
 * not shared, and a bean and a shared attribute named like the element id of a task wired
 * by that id. Its workflow runs both methods.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7NamesHidingATaskIT {

  @Test
  @DisplayName("A Spring bean named like a task definition ends the start")
  public void aBeanNamedLikeATaskDefinitionEndsTheStart() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> new SpringApplicationBuilder(TestApplication.class)
            .run(
                "--spring.profiles.active=name-hiding,name-hiding-bean",
                "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/name-hiding",
                "--spring.datasource.url=jdbc:h2:mem:c7-name-hiding-bean;DB_CLOSE_DELAY=-1")
            .close());

    final var message = rootMessage(failure);
    assertTrue(message.contains("Task definition 'chargeCard'"), () -> "unexpected message: "
        + message);
    assertTrue(message.contains("'NameHidingProcess'"), () -> "unexpected message: "
        + message);
    assertTrue(message.contains("element 'NH_Charge'"), () -> "unexpected message: "
        + message);
    assertTrue(message.contains("workflow module 'c7-it'"), () -> "unexpected message: "
        + message);
    assertTrue(
        message.contains("Spring bean 'chargeCard' (class "
            + NameHidingBeans.ChargeCardBean.class.getName()
            + ")"),
        () -> "unexpected message: "
            + message);
    assertTrue(message.contains("or rename the bean"), () -> "unexpected message: "
        + message);
    // the element id is no EL name, so the bean of the test about it is no finding here
    assertTrue(!message.contains("'reviewOrder'"), () -> "unexpected message: "
        + message);

  }

  @Test
  @DisplayName("A shared attribute named like a task definition ends the start")
  public void aSharedAttributeNamedLikeATaskDefinitionEndsTheStart() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> new SpringApplicationBuilder(TestApplication.class)
            .run(
                "--spring.profiles.active=name-hiding-attribute",
                "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/shared-name",
                "--spring.datasource.url=jdbc:h2:mem:c7-name-hiding-attribute;DB_CLOSE_DELAY=-1")
            .close());

    final var message = rootMessage(failure);
    assertTrue(message.contains("Task definition 'chargeCard'"), () -> "unexpected message: "
        + message);
    assertTrue(message.contains("'SharedNameProcess'"), () -> "unexpected message: "
        + message);
    assertTrue(message.contains("element 'SN_Charge'"), () -> "unexpected message: "
        + message);
    assertTrue(
        message.contains("the attribute 'chargeCard' of the workflow aggregate"),
        () -> "unexpected message: "
            + message);
    assertTrue(message.contains("@NoSyncWithBPMS"), () -> "unexpected message: "
        + message);

  }

  @Test
  @DisplayName("An unshared attribute, and names of an element wired by its id, leave the start alone")
  public void namesWhichHideNoTaskLeaveTheStartAlone() throws Exception {

    final var application = new SpringApplicationBuilder(TestApplication.class)
        .run(
            "--spring.profiles.active=name-hiding,name-hiding-element-id",
            "--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/name-hiding",
            "--spring.datasource.url=jdbc:h2:mem:c7-name-hiding-none;DB_CLOSE_DELAY=-1");
    try {
      assertTrue(application.containsBean("reviewOrder"), "the bean named like the element id has to exist");
      application
          .getBean(org.springframework.transaction.support.TransactionTemplate.class)
          .executeWithoutResult(
              status -> application
                  .getBean(NameHidingWorkflowService.class)
                  .startWorkflow("4711"));

      final var repository = application.getBean(NameHidingRepository.class);
      awaitUntil(
          application,
          () -> repository
              .findById("4711")
              .map(aggregate -> aggregate.isCharged() && aggregate.isReviewed())
              .orElse(Boolean.FALSE),
          "both methods to run: the one of the task definition and the one of the element id");
    } finally {
      application.close();
    }

  }

  /**
   * Waits for something which the engine brings about. The deadline is generous on purpose,
   * because this class shares its machine with the other engines of this module.
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

  private static String rootMessage(
      final Throwable throwable) {

    var cause = throwable;
    while ((cause.getCause() != null) && (cause.getCause() != cause)) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

}
