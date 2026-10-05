package io.vanillabp.camunda7.quarkus;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.camunda7.quarkus.listeners.ListenerAggregate;
import io.vanillabp.camunda7.quarkus.listeners.ListenerPersistence;
import io.vanillabp.camunda7.quarkus.listeners.ListenerWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The same model as {@link Camunda7ModelledListenerTest}, booted without
 * {@code allow-listeners}. A listener runs application code at a moment the engine owns, so
 * the adapter serves one only where the application said so, and it says which key turns it
 * on rather than leaving a model which silently does nothing.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7ListenersNobodyAllowedTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addClass(ListenerAggregate.class)
          .addClass(ListenerPersistence.class)
          .addClass(ListenerWorkflowService.class)
          .addAsResource("modelled-listeners-refused/application.yaml", "application.yaml")
          .addAsResource(
              "c7-listeners/processes/listener-process.bpmn",
              "c7-listeners/processes/listener-process.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        final var message = Camunda7BootFailure.messagesOf(throwable);
        Assertions.assertTrue(
            message.contains("allow-listeners"),
            () -> "expected the boot to name the key which allows listeners but got: "
                + message);
      });

  @Test
  public void aModelledListenerNobodyAllowedFailsTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
