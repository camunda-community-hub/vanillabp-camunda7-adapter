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
 * What Camunda 7 does with a standard loop, measured rather than read off a table.
 * <p>
 * A modeller draws <code>standardLoopCharacteristics</code> to repeat ONE activity while a
 * condition holds. The BPMN analysis behind graphic 5.1 said Camunda 7 implements it. It does
 * not: the string appears in <code>camunda-engine</code> only in the BPMN schema file the jar
 * ships, in no class of it, and <code>BpmnParse</code> reads
 * <code>multiInstanceLoopCharacteristics</code> alone. The run below says the same from the
 * outside - the model deploys, the activity runs exactly ONCE and the workflow ends, with a
 * loop condition of <code>${true}</code> and a loop maximum of 3, and the engine writes not
 * one line above DEBUG about any of it.
 * <p>
 * So the marker is silently ignored on both Camunda engines, measured on 7.24 here and on
 * 8.8.39, 8.9.21 and 8.10.0-rc1 in the Camunda 8 adapter. A model which counts on it does its
 * work once and moves on, which is the kind of deviation somebody finds in production. Both
 * Deviations pages say so.
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
    // what the engine said about it, so the silence is measured rather than assumed
    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var rootLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    rootLogger.addAppender(logWatcher);
    try {
      // nothing is refused: the engine deploys the model although it implements no standard
      // loop, which is why a modeller hears nothing about the marker
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
