package io.vanillabp.camunda7.it;

import java.math.BigDecimal;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * The same cells with the SPIN JSON data format. It is configured per adapter. It reaches
 * the embedded engine through the plugin section which VanillaBP passes on (see decision 9
 * in the repository's DECISIONS.md).
 * <p>
 * Nine of the ten cells answer exactly what they answer in the other world. The tenth is
 * the scale. JSON drops it, because JSON has one number type. The number stays the same, so
 * the conversion accepts it either way.
 */
@SpringBootTest(classes = TestApplication.class, properties = {
    "spring.datasource.url=jdbc:h2:mem:c7-param-types-json-it;DB_CLOSE_DELAY=-1", "vanillabp.workflow-modules.c7-it.adapters.c7.resources-location=classpath*:c7-it/paramtypes", "vanillabp.adapters.c7.serialization-format=application/json", "vanillabp.adapters.c7.engine-plugins.spin.plugin-class=org.camunda.spin.plugin.impl.SpinProcessEnginePlugin"
})
@DirtiesContext
public class Camunda7ParamTypesJsonIT extends AbstractParamTypesIT {

  @Override
  protected BigDecimal theDecimalThisWorldGivesBack() {

    return new BigDecimal("120.5");

  }

}
