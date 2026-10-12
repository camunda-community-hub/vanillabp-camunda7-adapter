package io.vanillabp.camunda7.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.camunda.bpm.engine.impl.cfg.StandaloneProcessEngineConfiguration;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What Camunda 7 does with a standard loop. It is measured here, not read off a table.
 * <p>
 * A modeller draws <code>standardLoopCharacteristics</code> to repeat ONE activity while a
 * condition holds. An earlier BPMN analysis said Camunda 7 implements it. It does not. In
 * <code>camunda-engine</code> the string appears only in the BPMN schema file the jar ships,
 * in no class. And <code>BpmnParse</code> reads <code>multiInstanceLoopCharacteristics</code>
 * alone. The run below shows the same from the outside. The loop condition is
 * <code>${true}</code> and the loop maximum is 3. Still the model deploys, the activity runs
 * exactly ONCE and the workflow ends. The engine writes not one line at WARN or above about any
 * of it.
 * <p>
 * So both Camunda engines silently ignore the marker. This was measured on 7.24 here, and on
 * 8.8.39, 8.9.21 and 8.10.0-rc1 in the Camunda 8 adapter. A model which counts on it does its
 * work once and moves on. Somebody finds such a deviation in production. That is why the
 * adapter refuses such a model while deploying it, see
 * {@code Camunda7StandardLoopRefusalTest}. The test here runs the bare engine, without the
 * adapter. So it keeps measuring what the refusal is about.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7StandardLoopTest {

  private static final String BPMN_PROCESS_ID = "StandardLoopFactsProcess";

  @Test
  @DisplayName("A standard loop deploys without a word and runs the activity once")
  public void aStandardLoopRunsOnce() {

    CountingDelegate.reset();
    final var dataSource = new JdbcDataSource();
    dataSource.setURL("jdbc:h2:mem:standard-loop-facts;DB_CLOSE_DELAY=-1");
    final var configuration = new StandaloneProcessEngineConfiguration();
    configuration.setDataSource(dataSource);
    configuration.setDatabaseSchemaUpdate("true");
    configuration.setJobExecutorActivate(false);
    configuration.setProcessEngineName("standard-loop-facts");
    configuration.setHistoryTimeToLive("P1D");
    final var processEngine = configuration.buildProcessEngine();
    // what the engine said about it, so the silence is measured and not assumed
    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var rootLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    rootLogger.addAppender(logWatcher);
    try {
      // nothing is refused. The engine deploys the model although it implements no standard
      // loop. So a modeller hears nothing about the marker
      final var deployment = processEngine
          .getRepositoryService()
          .createDeployment()
          .addClasspathResource("api/standard-loop-facts.bpmn")
          .deploy();
      assertNotNull(deployment, "the model with the standard loop was deployed");

      processEngine.getRuntimeService().startProcessInstanceByKey(BPMN_PROCESS_ID);

      assertEquals(
          List.of(),
          logWatcher.list
              .stream()
              .filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
              .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
              .toList(),
          "the engine says nothing about the marker it ignores");

      assertEquals(
          1,
          CountingDelegate.rounds(),
          "the loop condition holds and the loop maximum is 3, and the activity still ran once");
      assertEquals(
          0,
          processEngine.getRuntimeService().createProcessInstanceQuery().count(),
          "and the workflow ran to its end rather than waiting anywhere");
    } finally {
      rootLogger.detachAppender(logWatcher);
      processEngine.close();
    }

  }

}
