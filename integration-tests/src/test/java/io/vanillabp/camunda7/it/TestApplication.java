package io.vanillabp.camunda7.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The test application. It boots a real embedded Camunda 7 engine on H2 together with the
 * VanillaBP Camunda 7 adapter. The engine shares H2 with the JPA aggregate persistence. Used by
 * {@link Camunda7StartWorkflowIT}.
 */
@SpringBootApplication
public class TestApplication {

}
