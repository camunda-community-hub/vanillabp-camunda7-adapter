package io.vanillabp.camunda7.quarkus.sample;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.camunda.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.camunda.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.camunda.bpm.engine.impl.history.event.HistoricProcessInstanceEventEntity;
import org.camunda.bpm.engine.impl.history.event.HistoryEvent;
import org.camunda.bpm.engine.impl.history.handler.HistoryEventHandler;
import org.camunda.bpm.engine.impl.pvm.process.ActivityImpl;
import org.camunda.bpm.engine.impl.pvm.process.ScopeImpl;
import org.camunda.bpm.engine.impl.util.xml.Element;

import io.vanillabp.camunda7.engine.Camunda7EngineCustomizer;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * An extension reaching into the embedded engine on Quarkus: a parse listener seeing the
 * service tasks of the deployed models and a handler for the engine's history events.
 * Everything it sees is recorded, so the test can ask what happened.
 */
@ApplicationScoped
public class RecordingEngineCustomizer implements Camunda7EngineCustomizer {

  private final Set<String> askedAdapterIds = new LinkedHashSet<>();

  private final List<String> parsedServiceTasks = new CopyOnWriteArrayList<>();

  private final List<String> processInstanceHistory = new CopyOnWriteArrayList<>();

  @Override
  public List<BpmnParseListener> parseListenersAfter(
      final String adapterId) {

    askedAdapterIds.add(adapterId);
    return List.of(new AbstractBpmnParseListener() {

      @Override
      public void parseServiceTask(
          final Element serviceTaskElement,
          final ScopeImpl scope,
          final ActivityImpl activity) {

        parsedServiceTasks.add("%s/%s".formatted(adapterId, activity.getId()));

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
              .add(
                  "%s/%s/%s"
                      .formatted(adapterId, instanceEvent.getEventType(), instanceEvent.getProcessDefinitionKey()));
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
   * @return The service tasks the parse listener saw
   */
  public List<String> getParsedServiceTasks() {

    return parsedServiceTasks;

  }

  /**
   * @return The history events of process instances
   */
  public List<String> getProcessInstanceHistory() {

    return processInstanceHistory;

  }

}
