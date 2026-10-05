package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;

import org.camunda.bpm.engine.RuntimeService;
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
 * A workflow reaches the methods its OWN version names, measured against a real engine and
 * across an upgrade of the application.
 * <p>
 * Version 1 of the process names one expression at each of three elements, version 2 names
 * another one at the same elements. Camunda 7 evaluates the expressions of the model a
 * workflow was started with, so a workflow of version 1 asks for the names of version 1 after
 * the upgrade as well. Those names must lead to the methods of version 1. Before this was
 * fixed, the adapter knew only the tasks of the model the application deploys, found the
 * element of version 2 under the same id and ran the method version 2 names there.
 * <p>
 * The three elements are the three ways a model names a method: a task wired by
 * <code>camunda:expression</code>, a listener written as <code>camunda:expression</code> and
 * a task wired by <code>camunda:delegateExpression</code>. Each sits on a branch of its own,
 * so a failure at one of them leaves the others to be measured.
 * <p>
 * Three generations of the application share one in-memory database, kept alive across the
 * boots by <code>DB_CLOSE_DELAY=-1</code>. The first deploys version 1 and starts two
 * workflows, which wait for a message. The second deploys version 2 and kept the methods of
 * version 1. The third removed them.
 */
@ExtendWith(SuppressOutputExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class Camunda7HandlersOfTheOwnVersionIT {

  private static final String DATABASE = "--spring.datasource.url=jdbc:h2:mem:c7-own-version;DB_CLOSE_DELAY=-1";

  /** The workflow of version 1 which the generation keeping the old methods continues. */
  static Long continuedWithTheOldMethods;

  /** The workflow of version 1 which the generation without the old methods continues. */
  static Long continuedWithoutTheOldMethods;

  @Test
  @Order(1)
  @DisplayName("Two workflows are started on version 1 and wait for their message")
  public void twoWorkflowsWaitOnVersionOne() throws Exception {

    final var application = boot("own-version-before", "v1");
    try {
      final var workflowService = application.getBean(OwnVersionBeforeWorkflowService.class);
      final var transaction = application.getBean(TransactionTemplate.class);
      continuedWithTheOldMethods = transaction.execute(status -> workflowService.startWorkflow()).getId();
      continuedWithoutTheOldMethods = transaction.execute(status -> workflowService.startWorkflow()).getId();

      awaitUntil(
          () -> waitingForTheMessage(application) == 2,
          "the two workflows of version 1 did not reach their message");
    } finally {
      application.close();
    }

  }

  @Test
  @Order(2)
  @DisplayName("A workflow of version 1 reaches the methods version 1 names, although version 2 names others")
  public void aWorkflowOfVersionOneReachesTheOldMethods() throws Exception {

    assertNotNull(continuedWithTheOldMethods, "the workflow of the first case has to exist");

    final var application = boot("own-version-kept", "v2");
    try {
      final var workflowService = application.getBean(OwnVersionKeptWorkflowService.class);
      final var transaction = application.getBean(TransactionTemplate.class);

      transaction.executeWithoutResult(status -> workflowService.continueWorkflow(continuedWithTheOldMethods));
      final var served = awaitTheThreeElements(application, continuedWithTheOldMethods);

      assertEquals(
          Map.of("expression", "old", "listener", "old", "delegate", "old"),
          served,
          "a workflow of version 1 has to reach the methods version 1 names at every element");
    } finally {
      application.close();
    }

  }

  @Test
  @Order(3)
  @DisplayName("A workflow of version 2 reaches the methods version 2 names")
  public void aWorkflowOfVersionTwoReachesTheNewMethods() throws Exception {

    final var application = boot("own-version-kept", "v2");
    try {
      final var workflowService = application.getBean(OwnVersionKeptWorkflowService.class);
      final var transaction = application.getBean(TransactionTemplate.class);

      final var id = transaction.execute(status -> workflowService.startWorkflow()).getId();
      awaitUntil(
          () -> waitingForTheMessage(application) == 2,
          "the workflow of version 2 did not reach its message");
      transaction.executeWithoutResult(status -> workflowService.continueWorkflow(id));
      final var served = awaitTheThreeElements(application, id);

      assertEquals(
          Map.of("expression", "new", "listener", "new", "delegate", "new"),
          served,
          "a workflow of version 2 has to reach the methods version 2 names");
    } finally {
      application.close();
    }

  }

  @Test
  @Order(4)
  @DisplayName("Without the methods of version 1 its workflow ends in incidents instead of running the new methods")
  public void aRemovedMethodOfVersionOneIsAnIncident(
      final CapturedOutput output) throws Exception {

    assertNotNull(continuedWithoutTheOldMethods, "the workflow of the first case has to exist");

    final var beforeTheBoot = output.getAll().length();
    final var application = boot("own-version-removed", "v2");
    try {
      // the start already says what version 1 is missing, before any workflow runs into it
      final var reported = output.getAll().substring(beforeTheBoot);
      assertTrue(
          reported.contains("version '1' of process 'OwnVersionProcess'"),
          () -> "the start has to name the version which misses methods: "
              + reported);
      assertTrue(
          reported.contains("'ownVersionOldExpression'") && reported.contains("'ownVersionOldDelegate'"),
          () -> "and the task definitions nothing serves any more: "
              + reported);
      assertTrue(
          reported.contains("served by NO @WorkflowTask method"),
          () -> "and that no method serves them: "
              + reported);

      final var workflowService = application.getBean(OwnVersionRemovedWorkflowService.class);
      application
          .getBean(TransactionTemplate.class)
          .executeWithoutResult(status -> workflowService.continueWorkflow(continuedWithoutTheOldMethods));
      final var served = awaitTheThreeElements(application, continuedWithoutTheOldMethods);

      assertEquals(
          Map.of(),
          served,
          "no method version 2 names may serve a workflow of version 1");
      final var incidents = incidentsOf(application, continuedWithoutTheOldMethods);
      assertEquals(
          java.util.Set.of("OV_Expression", "OV_Listener", "OV_Delegate"),
          incidents.keySet(),
          () -> "every element of version 1 ends in an incident: "
              + incidents);
      final var incidentOfTheExpressionTask = incidents.get("OV_Expression");
      assertTrue(
          incidentOfTheExpressionTask.contains("'ownVersionOldExpression'"),
          () -> "the incident names the task definition: "
              + incidentOfTheExpressionTask);
      assertTrue(
          incidentOfTheExpressionTask.contains("OV_Expression"),
          () -> "the incident names the element: "
              + incidentOfTheExpressionTask);
      assertTrue(
          incidentOfTheExpressionTask.contains("process version '1'"),
          () -> "the incident names the version: "
              + incidentOfTheExpressionTask);
      assertTrue(
          incidents.get("OV_Delegate").contains("ownVersionOldDelegate"),
          () -> "the incident names the task definition: "
              + incidents.get("OV_Delegate"));
      assertTrue(
          incidents.get("OV_Listener").contains("ownVersionOldListener"),
          () -> "the incident names the expression of the listener: "
              + incidents.get("OV_Listener"));
    } finally {
      application.close();
    }

  }

  /**
   * Waits until each of the three elements was either served or ended in an incident, and
   * answers which generation of methods served which element.
   */
  private static Map<String, String> awaitTheThreeElements(
      final ConfigurableApplicationContext application,
      final Long id) throws Exception {

    awaitUntil(
        () -> served(application, id).size() + incidentsOf(application, id).size() >= 3,
        "the three elements of the workflow were neither served nor did they end in incidents");
    return served(application, id);

  }

  private static Map<String, String> served(
      final ConfigurableApplicationContext application,
      final Long id) {

    final var aggregate = application.getBean(OwnVersionRepository.class).findById(id).orElseThrow();
    final var served = new java.util.HashMap<String, String>();
    if (aggregate.getExpressionServedBy() != null) {
      served.put("expression", aggregate.getExpressionServedBy());
    }
    if (aggregate.getListenerServedBy() != null) {
      served.put("listener", aggregate.getListenerServedBy());
    }
    if (aggregate.getDelegateServedBy() != null) {
      served.put("delegate", aggregate.getDelegateServedBy());
    }
    return served;

  }

  /**
   * The incidents of one workflow, by the element they stand at.
   */
  private static Map<String, String> incidentsOf(
      final ConfigurableApplicationContext application,
      final Long id) {

    final var runtimeService = application.getBean(RuntimeService.class);
    final var instance = runtimeService
        .createProcessInstanceQuery()
        .processInstanceBusinessKey(id.toString())
        .singleResult();
    if (instance == null) {
      return Map.of();
    }
    return runtimeService
        .createIncidentQuery()
        .processInstanceId(instance.getId())
        .list()
        .stream()
        .collect(Collectors.toMap(
            org.camunda.bpm.engine.runtime.Incident::getActivityId,
            incident -> String.valueOf(incident.getIncidentMessage()),
            (
                first,
                second) -> first));

  }

  private static long waitingForTheMessage(
      final ConfigurableApplicationContext application) {

    return application
        .getBean(RuntimeService.class)
        .createEventSubscriptionQuery()
        .eventName("OwnVersionContinue")
        .count();

  }

  /**
   * Waits for something the engine's job executor has to bring about. Generous on purpose:
   * in a full build this class shares its machine with the other engines of this module.
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
   * One generation of the application: its workflow service (a Spring profile decides which)
   * and the version of the model it deploys.
   */
  private static ConfigurableApplicationContext boot(
      final String profile,
      final String bpmnVersion) {

    final var boot = new ArrayList<String>();
    boot.add(DATABASE);
    boot.add("--spring.profiles.active="
        + profile);
    // the aggregates of the first boot are what the later ones continue on
    boot.add("--spring.jpa.hibernate.ddl-auto=update");
    boot.add("--vanillabp.adapters.c7.allow-listeners=true");
    boot
        .add("--vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/own-version/%s"
            .formatted(bpmnVersion));
    return new SpringApplicationBuilder(TestApplication.class).run(boot.toArray(String[]::new));

  }

}
