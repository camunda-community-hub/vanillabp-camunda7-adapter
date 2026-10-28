package io.vanillabp.camunda7.quarkus.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.restassured.RestAssured;
import io.vanillabp.integration.test.utils.OneFreePortPerJvm;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A booted Quarkus application whose Camunda 7 engine evaluates decisions with its older FEEL
 * implementation. The key is what an application needs next to the Operaton adapter. That the
 * engine then does without the FEEL engine library is proven in the core, by
 * {@code Camunda7DmnFeelLegacyBehaviorTest} on a class path without it. This test adds the
 * Quarkus wiring: the runtime configuration key reaches the engine the adapter builds. The same
 * application without the key is {@code Camunda7WorkflowLifecycleTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class Camunda7DmnFeelLegacyBehaviorTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addPackage("io.vanillabp.camunda7.quarkus.test")
          .addAsResource("application.yaml")
          .addAsResource("c7-e2e/processes/task-matrix.bpmn")
          .addAsResource("c7-e2e/processes/signal-catch.bpmn")
          .addAsResource("c7-e2e/processes/aggregate-changed.bpmn")
          .addAsResource("c7-e2e/processes/timer-start.bpmn")
          .addAsResource("c7-e2e/processes/versioned-process.bpmn")
          .addAsResource("dmn-feel/loan-size.dmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // JVM args needed for tracking coverage. See this module's POM for the
      // systemPropertyVariables which feed 'jacoco.agent'
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .setRun(true)
      .setRuntimeProperties(Map.of(
          "quarkus.http.port", Integer.toString(OneFreePortPerJvm.getPort()),
          "quarkus.log.file.enable", "true",
          "quarkus.log.file.path", Path
              .of("target", "c7-dmn-feel-legacy-application.log")
              .toAbsolutePath()
              .toString(),
          "vanillabp.adapters.c7.dmn-feel-legacy-behavior", "true"));

  @Test
  @DisplayName("the key switches the engine to the older FEEL implementation, and it evaluates a decision")
  public void theKeyReachesTheEngine() {

    final Map<String, Object> reported = RestAssured
        .given()
        .baseUri("http://localhost")
        .port(OneFreePortPerJvm.getPort())
        .get("introspect/engine/dmn-feel")
        .then()
        .statusCode(200)
        .extract()
        .jsonPath()
        .getMap("$");

    assertEquals(Boolean.TRUE, reported.get("legacy"), () -> reported.toString());
    assertEquals("FeelEngineFactoryImpl", reported.get("feelImplementation"), () -> reported.toString());
    assertEquals(List.of("small", "medium", "medium", "large"), reported.get("sizes"));

  }

}
