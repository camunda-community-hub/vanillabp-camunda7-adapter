package io.vanillabp.camunda7.springboot.webapps;

import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.web.client.RestClient;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two <code>camunda7</code> adapter ids are two engines - the side-by-side setup of a
 * migration. Both of them show up in the web applications, which offer them in their
 * engine switcher.
 *
 * <p>
 * The second id runs on a datasource of its own, which the application provides as a
 * bean: setting up datasources is the application's business, VanillaBP only refers to
 * one by name.
 * </p>
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(
    classes = {
        TestApplication.class, Camunda7WebappsTwoAdapterIdsTest.SecondDataSource.class
    },
    webEnvironment = WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:c7-webapps-two-ids;DB_CLOSE_DELAY=-1", "vanillabp.adapters.c7-new.type=camunda7", "vanillabp.adapters.c7-new.data-source-name=c7NewDataSource", "vanillabp.prioritized-adapters=camunda7,c7-new"
    })
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
public class Camunda7WebappsTwoAdapterIdsTest {

  /** The datasource of the second engine, as an application would provide it. */
  @Configuration
  public static class SecondDataSource {

    @Bean
    public DataSource c7NewDataSource() {

      return new SimpleDriverDataSource(
          new org.h2.Driver(), "jdbc:h2:mem:c7-webapps-two-ids-new;DB_CLOSE_DELAY=-1", "sa", "");

    }

  }

  @LocalServerPort
  private int port;

  @Test
  @DisplayName("Both engines are offered by the web applications")
  public void bothEnginesAreServed() {

    final var engines = RestClient
        .builder()
        .baseUrl("http://localhost:"
            + port)
        .build()
        .get()
        .uri("/camunda/api/engine/engine/")
        .retrieve()
        .body(String.class);

    assertTrue(engines.contains("vanillabp-camunda7-camunda7"), engines);
    assertTrue(engines.contains("vanillabp-camunda7-c7-new"), engines);

  }

}
