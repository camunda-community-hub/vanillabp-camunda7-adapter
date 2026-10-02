package io.vanillabp.camunda7.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.delegate.DelegateTask;
import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the engine hands a TASK LISTENER, which is the one thing the levels of a user task
 * depend on.
 * <p>
 * This adapter notifies a <code>&#64;WorkflowTask</code> method about a user task from a task
 * listener, and a task listener is given a {@link DelegateTask} rather than an execution. So
 * the question of story 884 was whether that task can say which iteration it runs in at all.
 * It can: the task hands out the execution it hangs on, the walk starts there the way it
 * starts on the service-like side, and it answers while the task is created as well as while
 * it is cancelled. The levels of a user task therefore cost nothing but the call, which is
 * why this adapter makes it.
 * <p>
 * Measured against a real embedded engine, because a listener which runs inside the engine's
 * own command is not something a test double says anything about.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7MultiInstancesAtAUserTaskTest {

  private static final List<String> ORDERS = List.of("first", "second");

  private static final List<String> ROUNDS = List.of("alpha", "beta", "gamma");

  /**
   * What the walk reported for every task event the engine fired, in the order the events
   * arrived. One line per event: the event name, the task and the levels.
   */
  private static final List<String> REPORTED = new ArrayList<>();

  /**
   * Walks from the execution a task listener is handed and writes down what it finds. Built
   * the way this adapter builds its own listener, which is what makes the answer the answer
   * of the adapter.
   */
  private static class WhatTheTaskKnows implements TaskListener {

    @Override
    public void notify(
        final DelegateTask delegateTask) {

      final var levels = Camunda7MultiInstances.of(delegateTask.getExecution(), null);
      REPORTED
          .add(
              "%s %s %s"
                  .formatted(delegateTask.getEventName(), delegateTask.getTaskDefinitionKey(), levels));

    }

  }

  /**
   * Attaches that listener to every user task of the deployed models, which is where
   * {@code Camunda7AsyncBpmnParseListener} attaches the listener of this adapter.
   */
  private static class ListeningToEveryUserTask extends AbstractBpmnParseListener {

    @Override
    public void parseUserTask(
        final Element userTaskElement,
        final ScopeImpl scope,
        final ActivityImpl activity) {

      final var taskDefinition = ((UserTaskActivityBehavior) activity.getActivityBehavior()).getTaskDefinition();
      taskDefinition.addBuiltInTaskListener(TaskListener.EVENTNAME_CREATE, new WhatTheTaskKnows());
      taskDefinition.addBuiltInTaskListener(TaskListener.EVENTNAME_DELETE, new WhatTheTaskKnows());

    }

  }

  private static ProcessEngine anEngineWithTheModels(
      final String databaseName) {

    final var dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(databaseName));
    final var configuration = new StandaloneProcessEngineConfiguration();
    configuration.setDataSource(dataSource);
    configuration.setDatabaseSchemaUpdate("true");
    configuration.setJobExecutorActivate(false);
    configuration.setProcessEngineName(databaseName);
    configuration.setHistoryTimeToLive("P1D");
    // a list of its own: the engine adds its own listeners to the one it is given
    configuration.setCustomPostBPMNParseListeners(new ArrayList<>(List.of(new ListeningToEveryUserTask())));
    final var engine = configuration.buildProcessEngine();
    engine
        .getRepositoryService()
        .createDeployment()
        .addModelInstance(
            "api/mi-user-tasks.bpmn",
            Bpmn
                .readModelFromStream(
                    Camunda7MultiInstancesAtAUserTaskTest.class
                        .getClassLoader()
                        .getResourceAsStream("api/mi-user-tasks.bpmn")))
        .deploy();
    return engine;

  }

  @Test
  @DisplayName("A user task inside a multi-instance subprocess is told the iteration of that subprocess")
  public void aUserTaskInsideAMultiInstanceSubprocessIsToldItsIteration() {

    REPORTED.clear();
    final var engine = anEngineWithTheModels("mi-user-task-in-a-subprocess");
    try {
      engine
          .getRuntimeService()
          .startProcessInstanceByKey("MiUserTaskProcess", Map.of("orders", ORDERS));

      final var firstTask = engine.getTaskService().createTaskQuery().taskDefinitionKey("Review").singleResult();
      assertNotNull(firstTask, "the first iteration waits at the user task");
      // the second iteration follows the first: the subprocess iterates sequentially
      engine.getTaskService().complete(firstTask.getId());
      final var secondTask = engine.getTaskService().createTaskQuery().taskDefinitionKey("Review").singleResult();
      engine.getTaskService().complete(secondTask.getId());

      assertEquals(
          List
              .of(
                  "create Review {Reviews=MultiInstanceValue[element=first, index=0, total=2]}",
                  "create Review {Reviews=MultiInstanceValue[element=second, index=1, total=2]}"),
          REPORTED,
          "both iterations are told their element, their index and the total");

    } finally {
      engine.close();
    }

  }

  @Test
  @DisplayName("A multi-instance user task is told its own iteration, while it is created and while it is cancelled")
  public void aMultiInstanceUserTaskIsToldItsOwnIteration() {

    REPORTED.clear();
    final var engine = anEngineWithTheModels("mi-user-task-itself");
    try {
      final var workflowId = engine
          .getRuntimeService()
          .startProcessInstanceByKey("MiUserTaskItselfProcess", Map.of("orders", ROUNDS))
          .getId();

      final var task = engine.getTaskService().createTaskQuery().taskDefinitionKey("ReviewEach").singleResult();
      assertNotNull(task, "the first round waits at the user task");
      // the cancellation of the whole workflow, which is what a CANCELED notification
      // travels with
      engine.getRuntimeService().deleteProcessInstance(workflowId, "the test is done with it");

      assertEquals(
          List
              .of(
                  "create ReviewEach {ReviewEach=MultiInstanceValue[element=alpha, index=0, total=3]}",
                  "delete ReviewEach {ReviewEach=MultiInstanceValue[element=alpha, index=0, total=3]}"),
          REPORTED,
          "the element carrying the characteristics is the level, and a task going away still "
              + "knows it");

    } finally {
      engine.close();
    }

  }

}
