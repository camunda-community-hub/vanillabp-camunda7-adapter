package io.vanillabp.camunda7.springboot.webapps;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The application the tests of this module boot: the VanillaBP Camunda 7 adapter, this
 * module, an H2 database and a servlet container. It has no workflow of its own - what
 * is tested here is that the webapps come up and talk to the engine VanillaBP built.
 */
@SpringBootApplication
public class TestApplication {
}
