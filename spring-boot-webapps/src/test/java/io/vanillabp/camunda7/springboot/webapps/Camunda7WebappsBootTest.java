package io.vanillabp.camunda7.springboot.webapps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.camunda.bpm.container.RuntimeContainerDelegate;
import org.camunda.bpm.engine.spring.ProcessEngineFactoryBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Boots an application with this module and asks the web applications what they serve.
 *
 * <p>
 * The interesting assertion is the engine NAME: it proves that Cockpit, Tasklist and
 * Admin talk to the engine VanillaBP built for the adapter id, and not to one Camunda's
 * own auto-configuration created.
 * </p>
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:c7-webapps-boot;DB_CLOSE_DELAY=-1", "vanillabp.adapters.camunda7.webapps.admin-user.id=demo", "vanillabp.adapters.camunda7.webapps.admin-user.password=demo", "vanillabp.adapters.camunda7.webapps.admin-user.first-name=Demo", "vanillabp.adapters.camunda7.webapps.admin-user.last-name=User"
    })
// the runtime container is JVM-wide, so this context releases its engine when the class
// is done instead of leaving it there for the next test class
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
public class Camunda7WebappsBootTest {

  private static final String ENGINE = "vanillabp-camunda7-camunda7";

  @LocalServerPort
  private int port;

  @Autowired
  private ApplicationContext context;

  private RestClient rest;

  @BeforeEach
  public void restClient() {

    // redirects are NOT followed: where the web application sends a visitor is part of
    // what is asserted here
    rest = RestClient
        .builder()
        .baseUrl("http://localhost:"
            + port)
        .build();

  }

  @Test
  @DisplayName("The engine auto-configuration of Camunda is switched off in a booted application")
  public void camundasOwnAutoConfigurationIsOff() {

    // the unit test of the post processor proves what it contributes, this one proves
    // that it RUNS. Interface and spring.factories key have to name the same type, and
    // measured: naming the old key while implementing the new interface fails the boot
    // with "not assignable to factory type", so this test is what catches a half-done
    // migration
    assertEquals(
        "false",
        context.getEnvironment().getProperty(Camunda7WebappsEnvironmentPostProcessor.CAMUNDA_BPM_ENABLED),
        "without it Camunda's starter builds a second engine on the same datasource");

  }

  @Test
  @DisplayName("A visitor of /camunda is sent into a web application of this engine")
  public void webappsAreServed() {

    final var entry = rest
        .get()
        .uri("/camunda")
        .retrieve()
        .toBodilessEntity();

    assertTrue(entry.getStatusCode().is3xxRedirection(), () -> ""
        + entry.getStatusCode());
    final var location = entry
        .getHeaders()
        .getFirst(HttpHeaders.LOCATION);
    assertNotNull(location, "the web application should redirect somewhere");
    assertTrue(location.contains("/camunda/app/"), location);

    final var app = rest
        .get()
        .uri("/camunda/app/cockpit/"
            + ENGINE
            + "/")
        .retrieve()
        .toEntity(String.class);

    assertEquals(HttpStatus.OK, app.getStatusCode());
    assertTrue(
        app
            .getBody()
            .contains("Camunda"),
        () -> "the page should be a Camunda web application: "
            + app.getBody());

  }

  @Test
  @DisplayName("They serve the engine VanillaBP built, and only that one")
  public void theEngineIsTheOneVanillaBpBuilt() {

    final var engines = rest
        .get()
        .uri("/camunda/api/engine/engine/")
        .retrieve()
        .toEntity(String.class);

    assertEquals(HttpStatus.OK, engines.getStatusCode());
    assertEquals("[{\"name\":\""
        + ENGINE
        + "\"}]", engines.getBody());

    // Camunda's engine auto-configuration would have added a second engine on the
    // application's datasource, whose job executor would acquire VanillaBP's jobs
    assertEquals(
        0,
        context.getBeanNamesForType(ProcessEngineFactoryBean.class).length,
        "Camunda's engine auto-configuration must not run");
    assertEquals(
        1,
        context.getBeanProvider(io.vanillabp.camunda7.springboot.engine.Camunda7EngineHolder.class)
            .stream()
            .count(),
        "VanillaBP builds one engine per adapter id, and there is one id here");
    assertTrue(
        RuntimeContainerDelegate.INSTANCE
            .get()
            .getProcessEngineService()
            .getProcessEngineNames()
            .contains(ENGINE));

  }

  @Test
  @DisplayName("The configured administrator can log in")
  public void theAdministratorCanLogIn() {

    // the first response hands out the token every modifying request has to carry
    final var page = rest
        .get()
        .uri("/camunda/app/cockpit/"
            + ENGINE
            + "/")
        .retrieve()
        .toBodilessEntity();
    final var cookies = page
        .getHeaders()
        .get(HttpHeaders.SET_COOKIE);
    assertNotNull(cookies, "the web application should hand out its cookies");
    final var csrfToken = cookies
        .stream()
        .filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
        .map(cookie -> cookie.substring("XSRF-TOKEN=".length(), cookie.indexOf(';')))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no CSRF token in "
            + cookies));

    final var credentials = new LinkedMultiValueMap<String, String>();
    credentials.add("username", "demo");
    credentials.add("password", "demo");

    final var login = rest
        .post()
        .uri("/camunda/api/admin/auth/user/"
            + ENGINE
            + "/login/cockpit")
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .header("X-XSRF-TOKEN", csrfToken)
        .header(HttpHeaders.COOKIE, String.join("; ", cookies))
        .body(credentials)
        .retrieve()
        .toEntity(String.class);

    assertEquals(HttpStatus.OK, login.getStatusCode(), login::getBody);
    assertTrue(
        login
            .getBody()
            .contains("\"userId\":\"demo\""),
        login::getBody);
    assertTrue(
        login
            .getBody()
            .contains("cockpit"),
        () -> "the administrator should be authorized for cockpit: "
            + login.getBody());

  }

}
