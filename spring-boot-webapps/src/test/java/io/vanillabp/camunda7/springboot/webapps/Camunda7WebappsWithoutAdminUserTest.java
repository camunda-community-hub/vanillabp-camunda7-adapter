package io.vanillabp.camunda7.springboot.webapps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.camunda.bpm.engine.ProcessEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.web.client.RestClient;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application which brings its own users leaves the <code>admin-user</code> section
 * out. Then this module creates nobody, and the web applications ask the visitor to set
 * an administrator up - which is their own behaviour, not something VanillaBP arranges.
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = "spring.datasource.url=jdbc:h2:mem:c7-webapps-no-admin;DB_CLOSE_DELAY=-1")
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
public class Camunda7WebappsWithoutAdminUserTest {

  @LocalServerPort
  private int port;

  @Autowired
  private ProcessEngine engine;

  @Test
  @DisplayName("Without a configured administrator no user is created")
  public void noUserIsCreated() {

    assertEquals(
        0,
        engine
            .getIdentityService()
            .createUserQuery()
            .count());

  }

  @Test
  @DisplayName("The web applications are served nevertheless")
  public void webappsAreServedNevertheless() {

    final var entry = RestClient
        .builder()
        .baseUrl("http://localhost:"
            + port)
        .build()
        .get()
        .uri("/camunda")
        .retrieve()
        .toBodilessEntity();

    assertTrue(entry.getStatusCode().is3xxRedirection(), () -> ""
        + entry.getStatusCode());
    assertTrue(
        entry
            .getHeaders()
            .getFirst(HttpHeaders.LOCATION)
            .contains("/camunda/app/"));

  }

}
