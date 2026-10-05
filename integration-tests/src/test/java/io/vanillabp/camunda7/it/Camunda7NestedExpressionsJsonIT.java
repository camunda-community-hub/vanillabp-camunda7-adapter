package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The same cases with the SPIN JSON dataformat, which is configured per adapter and
 * reaches the embedded engine through the plugin section VanillaBP passes on (see
 * decision 9 in the repository's DECISIONS.md).
 * <p>
 * The format is a user-visible configuration variant, so it gets a run of its own rather
 * than a test asserting the one value which is known to differ today: what a serializer
 * does to a nested value is exactly the kind of thing that changes without anybody
 * meaning it to.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:c7-nested-expressions-json-it;DB_CLOSE_DELAY=-1", "vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/expressions", "vanillabp.adapters.c7.serialization-format=application/json", "vanillabp.adapters.c7.engine-plugins.spin.plugin-class=org.camunda.spin.plugin.impl.SpinProcessEnginePlugin"
})
@DirtiesContext
public class Camunda7NestedExpressionsJsonIT extends AbstractNestedExpressionsIT {

  @Override
  protected Class<?> theClassOfTheNestedBigDecimal() {

    // JSON has one number type, so the scale the aggregate wrote is gone the moment the
    // value is read back
    return Double.class;

  }

  @Override
  protected String theTextOfTheNestedBigDecimal() {

    return "120.5";

  }

  @Override
  protected void assertWhatTheNestedNumberAnswersToScale() {

    // Not a defect, and worth knowing before choosing a format: a model reading
    // ${order.total.scale()} evaluates under Java serialization and becomes an incident
    // under JSON, so the format decides which models still run. JSON has one number type,
    // and BigDecimal is the value which notices.
    assertTrue(
        failureOf("${order.total.scale() > 0}").contains("Method not found: class java.lang.Double.scale()"),
        failureOf("${order.total.scale() > 0}"));

  }

  @Override
  protected Class<?> theClassOfTheTopLevelBigDecimal() {

    // an object variable records the class it was written with, and Jackson reads the
    // number back into it - which is why the top level keeps the class here while the
    // nested value above does not
    return BigDecimal.class;

  }

  @Override
  protected String theTextOfTheTopLevelBigDecimal() {

    // the class survives, the scale does not: JSON has one number type, so 120.50 is
    // written as 120.5 and comes back as that
    return "120.5";

  }

  @Override
  protected void assertWhatTheTopLevelNumberAnswersToScale() {

    assertEquals(Boolean.TRUE, valueOf("${total.scale() > 0}"));

  }

}
