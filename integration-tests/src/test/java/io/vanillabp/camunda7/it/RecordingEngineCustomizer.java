package io.vanillabp.camunda7.it;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.camunda.bpm.engine.delegate.TaskListener;
import org.camunda.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.camunda.bpm.engine.impl.history.event.HistoryEvent;
import org.camunda.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;

import io.vanillabp.camunda7.engine.Camunda7EngineCustomizer;

/**
 * An extension reaching into the embedded engine, in miniature: a parse listener which
 * attaches a built-in task listener of its own to every user task, and a handler for the
 * engine's history events. What the Business Cockpit needs, and nothing else.
 * <p>
 * Everything it sees is recorded, so the tests can ask what happened and in which order.
 */
public class RecordingEngineCustomizer implements Camunda7EngineCustomizer {

  /**
   * The adapter ids this customizer was asked for - one per engine.
   */
  private final Set<String> askedAdapterIds = new LinkedHashSet<>();

  /**
   * The user tasks the parse listener saw, as <code>&lt;adapterId&gt;/&lt;activity
   * id&gt;</code>.
   */
  private final List<String> parsedUserTasks = new CopyOnWriteArrayList<>();

  /**
   * The user tasks which already carried VanillaBP's own built-in CREATE listener when
   * this customizer's parse listener saw them - the proof that a parse listener
   * contributed "after" really runs after the adapter's own.
   */
  private final List<String> userTasksAlreadyWiredByVanillaBp = new CopyOnWriteArrayList<>();

  /**
   * The task-listener invocations of this customizer's own built-in listener.
   */
  private final List<String> taskEvents = new CopyOnWriteArrayList<>();

  /**
   * The history events of process instances, as
   * <code>&lt;eventType&gt;/&lt;processDefinitionKey&gt;</code>.
   */
  private final List<String> processInstanceHistory = new CopyOnWriteArrayList<>();

  @Override
  public List<BpmnParseListener> parseListenersAfter(
      final String adapterId) {

    askedAdapterIds.add(adapterId);
    return List.of(new AbstractBpmnParseListener() {

      @Override
      public void parseUserTask(
          final Element userTaskElement,
          final ScopeImpl scope,
          final ActivityImpl activity) {

        final var taskDefinition = ((UserTaskActivityBehavior) activity.getActivityBehavior()).getTaskDefinition();
        final var where = "%s/%s".formatted(adapterId, activity.getId());
        parsedUserTasks.add(where);
        final var wiredByVanillaBp = taskDefinition
            .getBuiltinTaskListeners()
            .getOrDefault(TaskListener.EVENTNAME_CREATE, new LinkedList<>());
        if (!wiredByVanillaBp.isEmpty()) {
          userTasksAlreadyWiredByVanillaBp.add(where);
        }
        taskDefinition
            .addBuiltInTaskListener(
                TaskListener.EVENTNAME_CREATE,
                task -> taskEvents
                    .add("%s/%s/%s".formatted(adapterId, task.getTaskDefinitionKey(), task.getEventName())));

      }

    });

  }

  @Override
  public HistoryEventHandler historyEventHandler(
      final String adapterId) {

    askedAdapterIds.add(adapterId);
    return new HistoryEventHandler() {

      @Override
      public void handleEvent(
          final HistoryEvent historyEvent) {

        if (historyEvent instanceof HistoricProcessInstanceEventEntity instanceEvent) {
          processInstanceHistory
              .add("%s/%s/%s".formatted(adapterId, instanceEvent.getEventType(),
                  instanceEvent.getProcessDefinitionKey()));
        }

      }

      @Override
      public void handleEvents(
          final List<HistoryEvent> historyEvents) {

        historyEvents.forEach(this::handleEvent);

      }

    };

  }

  /**
   * @return The adapter ids this customizer was asked for
   */
  public Set<String> getAskedAdapterIds() {

    return askedAdapterIds;

  }

  /**
   * @return The user tasks the parse listener saw
   */
  public List<String> getParsedUserTasks() {

    return parsedUserTasks;

  }

  /**
   * @return The user tasks VanillaBP had already wired when this customizer saw them
   */
  public List<String> getUserTasksAlreadyWiredByVanillaBp() {

    return userTasksAlreadyWiredByVanillaBp;

  }

  /**
   * @return The task-listener invocations of this customizer's own listener
   */
  public List<String> getTaskEvents() {

    return taskEvents;

  }

  /**
   * @return The history events of process instances
   */
  public List<String> getProcessInstanceHistory() {

    return processInstanceHistory;

  }

}
