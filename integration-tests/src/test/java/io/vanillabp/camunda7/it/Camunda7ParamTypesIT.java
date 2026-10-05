package io.vanillabp.camunda7.it;

import java.math.BigDecimal;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The parameter-type cases in the world the other integration tests of this module run
 * in: no serialization format configured, so the engine falls back to Java serialization
 * and a decimal comes back the way the aggregate wrote it.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    // a database of its own: Spring caches every test context, and an engine which
    // outlives its class would poll the database the next classes work on
    "spring.datasource.url=jdbc:h2:mem:c7-param-types-it;DB_CLOSE_DELAY=-1", "vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/paramtypes"
})
@DirtiesContext
public class Camunda7ParamTypesIT extends AbstractParamTypesIT {

  @Override
  protected BigDecimal theDecimalThisWorldGivesBack() {

    return new BigDecimal("120.50");

  }

}
