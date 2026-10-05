package io.vanillabp.camunda7.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The nested expressions in the world the other integration tests of this module run in:
 * no serialization format configured, so the engine falls back to Java serialization and
 * a nested value keeps the class the aggregate gave it.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // a database of its own: Spring caches every test context, and an engine which
    // outlives its class would poll the database the next classes work on
    "spring.datasource.url=jdbc:h2:mem:c7-nested-expressions-it;DB_CLOSE_DELAY=-1", "vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/expressions"
})
@DirtiesContext
public class Camunda7NestedExpressionsIT extends AbstractNestedExpressionsIT {

  @Override
  protected Class<?> theClassOfTheNestedBigDecimal() {

    return BigDecimal.class;

  }

  @Override
  protected String theTextOfTheNestedBigDecimal() {

    return "120.50";

  }

  @Override
  protected void assertWhatTheNestedNumberAnswersToScale() {

    // Java serialization hands the BigDecimal back as it was, so the version-1 grammar
    // still reaches its methods here
    assertEquals(Boolean.TRUE, valueOf("${order.total.scale() > 0}"));

  }

  @Override
  protected Class<?> theClassOfTheTopLevelBigDecimal() {

    return BigDecimal.class;

  }

  @Override
  protected String theTextOfTheTopLevelBigDecimal() {

    // Java serialization writes the value the aggregate holds and reads it back
    // unchanged, so a model rendering it reads what version 1 rendered
    return "120.50";

  }

  @Override
  protected void assertWhatTheTopLevelNumberAnswersToScale() {

    assertEquals(Boolean.TRUE, valueOf("${total.scale() > 0}"));

  }

}
