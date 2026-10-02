package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The iteration a task runs in, measured against a real embedded Camunda 7 engine for both
 * kinds of task a model holds: a service-like one and a user task. They share a file because they
 * ask one question of two kinds of task, and whoever compares the answers should not have to open
 * two files for them.
 * <p>
 * The service-like half runs the two ways a model names the process it calls.
 * <p>
 * The chain of a task reaches over the call activity which started its process, and whether
 * it may is a question about the workflow aggregate: a called process which continues the
 * business case of its caller belongs in that caller's iteration, one with an aggregate of
 * its own does not. The deployment answers that question and writes the answer onto the call
 * activity, which it can do for a call activity naming the called process by its id and not
 * for one naming it in an expression. This test runs one model holding both and reads the
 * two answers out of one string.
 * <p>
 * The same model on Camunda 8 is
 * {@code Camunda8MultiInstanceIT#theIterationCrossesACallActivityNamedByAnExpression}, and
 * it reports the levels of the caller there. A model may not depend on which adapter runs
 * it, which is why this is measured and not described.
 * <p>
 * The user-task half asks the same of a user task inside a multi-instance subprocess. This
 * adapter notifies about a user task from a task listener, which is handed a task rather than
 * an execution, so the levels were missing there while every other kind of task reported
 * them. Camunda 8 reported them all along, which made one model answer two ways again.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // own database: contexts are cached and live in parallel, and a second engine with a
    // job executor on the same H2 database would compete for this test's jobs
    "spring.datasource.url=jdbc:h2:mem:c7-mi-call-by-expression-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done: this IT has a database (and therefore a context) of its
// own, and an engine outliving its test keeps its job executor running against a database
// the next classes work on
@DirtiesContext
public class Camunda7MultiInstanceByExpressionIT {

  @Autowired
  private MiCallExprWorkflowService workflowService;

  @Autowired
  private MiCallExprRepository repository;

  @Autowired
  private MiUserTaskWorkflowService userTaskWorkflowService;

  @Autowired
  private MiUserTaskRepository userTaskRepository;

  /**
   * What the tasks of the called process wrote, read fresh from the database because they
   * run in the engine's own transaction.
   */
  private String whatTheCalledProcessReported(
      final Long aggregateId) {

    return repository
        .findById(aggregateId)
        .map(MiCallExprAggregate::getReported)
        .orElse(null);

  }

  /**
   * Waits until the condition holds, and gives up quietly when it does not. The assertion
   * of the test reads the string either way, so a run which reports too little says what it
   * reported instead of saying that it waited.
   */
  private void awaitAtMostThirtySeconds(
      final Supplier<Boolean> condition) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (!Boolean.TRUE.equals(condition.get()) && (System.currentTimeMillis() < deadline)) {
      Thread.sleep(100);
    }

  }

  @Test
  @DisplayName("The iteration crosses a call activity which names the called process in an expression")
  public void theIterationCrossesACallActivityNamedByAnExpression() throws Exception {

    final var aggregateId = workflowService.startCaller().getId();
    assertNotNull(aggregateId);

    awaitAtMostThirtySeconds(() -> {
      final var reported = whatTheCalledProcessReported(aggregateId);
      return (reported != null) && (reported.split(",").length == 5);
    });

    assertEquals(
        "MCE_Group:c1#0/2>MCE_Call:p1#0/2,"
            + "MCE_Group:c1#0/2>MCE_Call:p2#1/2,"
            + "MCE_Group:c2#1/2>MCE_Call:p1#0/2,"
            + "MCE_Group:c2#1/2>MCE_Call:p2#1/2,"
            + "MCE_Round:s1#0/1",
        whatTheCalledProcessReported(aggregateId),
        "the first four calls belong to a call activity the deployment could not resolve, and "
            + "the fifth one to a call activity naming the called process by its id");

  }

  /**
   * What the notifications about the user tasks wrote, sorted. Both rounds of both groups
   * create their task in one transaction, so the order the engine notifies in is the
   * engine's business and not what this test measures.
   */
  private List<String> whatTheUserTasksReported(
      final Long aggregateId) {

    return userTaskRepository
        .findById(aggregateId)
        .map(MiUserTaskAggregate::getReported)
        .map(reported -> Arrays.stream(reported.split(",")).sorted().toList())
        .orElse(List.of());

  }

  @Test
  @DisplayName("A user task inside a multi-instance subprocess is told its element, its index and its total")
  public void aUserTaskIsToldTheIterationItRunsIn() throws Exception {

    final var aggregateId = userTaskWorkflowService.startWorkflow().getId();
    assertNotNull(aggregateId);

    awaitAtMostThirtySeconds(() -> whatTheUserTasksReported(aggregateId).size() == 4);

    assertEquals(
        List
            .of(
                "c1#0/2 MUT_Group:c1#0/2>MUT_Review:p1#0/2",
                "c1#1/2 MUT_Group:c1#0/2>MUT_Review:p2#1/2",
                "c2#0/2 MUT_Group:c2#1/2>MUT_Review:p1#0/2",
                "c2#1/2 MUT_Group:c2#1/2>MUT_Review:p2#1/2"),
        whatTheUserTasksReported(aggregateId),
        "the named parameters and the resolver read the same two levels, outermost first");

  }

}
