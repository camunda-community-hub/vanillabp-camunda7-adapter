package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A booted Spring Boot application whose Camunda 7 engine evaluates decisions with its older
 * FEEL implementation. The key is what an application needs next to the Operaton adapter.
 * That the engine then does without the FEEL engine library is proven in the core, by
 * {@code Camunda7DmnFeelLegacyBehaviorTest} on a class path without it. This test adds the
 * wiring: the key in a Spring Boot configuration reaches the engine the adapter builds. The
 * same application without the key is {@code Camunda7TaskProcessingIT}.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    "vanillabp.adapters.c7.dmn-feel-legacy-behavior=true",
    // a database of its own, so the engine of a context which Spring keeps cached does not
    // work on the tables of this one
    "spring.datasource.url=jdbc:h2:mem:c7-dmn-feel-legacy-it;DB_CLOSE_DELAY=-1"
})
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
// closed when the class is done, so its engine does not outlive the test
@DirtiesContext
public class Camunda7DmnFeelLegacyBehaviorIT {

  @Autowired
  private ProcessEngine processEngine;

  @Test
  @DisplayName("the key switches the application's engine to the older FEEL implementation")
  public void theKeyReachesTheEngine() {

    assertTrue(((ProcessEngineConfigurationImpl) processEngine.getProcessEngineConfiguration())
        .isDmnFeelEnableLegacyBehavior());
    assertEquals("FeelEngineFactoryImpl", LoanSizeDecision.feelImplementation(processEngine));

  }

  @Test
  @DisplayName("a decision with a comparison and a range is evaluated with the older FEEL implementation")
  public void aDecisionIsEvaluated() {

    assertEquals(LoanSizeDecision.SIZES, LoanSizeDecision.evaluate(processEngine));

  }

}
