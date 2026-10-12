package io.vanillabp.camunda7.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.wiring.Camunda7TaskConnectable;
import io.vanillabp.camunda7.wiring.Camunda7TaskConnectable.Type;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which tasks the check about hidden task definitions looks at, and what it says. The boots
 * on both platforms prove that the check runs. This test covers the cases a boot does not
 * reach cheaply.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7NamesHidingATaskTest {

  private static Camunda7TaskConnectable task(
      final String elementId,
      final String taskDefinition,
      final Type type) {

    return new Camunda7TaskConnectable("module", "Process", elementId, taskDefinition, type);

  }

  @Test
  @DisplayName("A user task's form key is never an EL name, so it is not looked at")
  public void aUserTaskIsNotLookedAt() {

    final var findings = Camunda7NamesHidingATask
        .findings(
            List.of(task("UT", "chargeCard", Type.USER_TASK)),
            "Process",
            "module",
            Set.of("chargeCard"),
            name -> Optional.of(Object.class));

    assertTrue(findings.isEmpty(), () -> findings.toString());

  }

  @Test
  @DisplayName("Every kind of task reached by an expression is looked at, listeners included")
  public void everyTaskReachedByAnExpressionIsLookedAt() {

    final var findings = Camunda7NamesHidingATask
        .findings(
            List.of(
                task("A", "byExpression", Type.EXPRESSION),
                task("B", "byDelegate", Type.DELEGATE_EXPRESSION),
                task("C", "byListener", Type.EXECUTION_LISTENER_EXPRESSION),
                task("D", "byDelegateListener", Type.EXECUTION_LISTENER_DELEGATE_EXPRESSION),
                task("E", "free", Type.EXPRESSION)),
            "Process",
            "module",
            Set.of(),
            name -> "free".equals(name)
                ? Optional.empty()
                : Optional.of(String.class));

    assertEquals(4, findings.size(), () -> findings.toString());
    assertTrue(findings.get(3).contains("element 'D'"), () -> findings.toString());
    assertTrue(findings.get(3).contains("class java.lang.String"), () -> findings.toString());

  }

  @Test
  @DisplayName("Where a bean and a shared attribute both carry the name, the attribute is named")
  public void theAttributeIsNamedBeforeTheBean() {

    final var findings = Camunda7NamesHidingATask
        .findings(
            List.of(task("A", "chargeCard", Type.EXPRESSION)),
            "Process",
            "module",
            Set.of("chargeCard"),
            name -> Optional.of(Object.class));

    assertEquals(1, findings.size(), () -> findings.toString());
    assertTrue(findings.get(0).contains("the attribute 'chargeCard'"), () -> findings.toString());

  }

}
