package io.vanillabp.camunda7.it;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test application booting a real embedded Camunda 7 engine on H2 (shared with the JPA
 * aggregate persistence) together with the VanillaBP Camunda 7 adapter. Used by
 * {@link Camunda7StartWorkflowIT}.
 */
@SpringBootApplication
public class TestApplication {

}
