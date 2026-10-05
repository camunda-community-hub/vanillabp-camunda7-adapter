package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two <code>camunda7</code> adapter ids only make sense if they are DIFFERENT
 * engines: an own datasource, or an own table prefix on a shared one.
 * The rule is implemented once in the adapter's core and reached through the
 * adapter SPI hook {@code validateDistinctAdapterInstances}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7InstanceIdentityTest {

  private static void validate(
      final Map<String, Camunda7InstanceIdentity> identities) {

    Camunda7InstanceIdentity.validateDistinct(List.copyOf(identities.keySet()), identities::get);

  }

  @Test
  @DisplayName("Two ids on the application's default datasource are the same engine")
  public void sameDefaultDataSourceFails() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> validate(Map.of(
            "c7-old", new Camunda7InstanceIdentity(null, null),
            "c7-new", new Camunda7InstanceIdentity(null, null))));

    assertTrue(exception.getMessage().contains("c7-old"), exception::getMessage);
    assertTrue(exception.getMessage().contains("c7-new"), exception::getMessage);
    assertTrue(exception.getMessage().contains("data-source-name"), exception::getMessage);
    assertTrue(exception.getMessage().contains("table-prefix"), exception::getMessage);

  }

  @Test
  @DisplayName("Two ids on the same named datasource and prefix are the same engine")
  public void sameNamedDataSourceFails() {

    assertThrows(
        IllegalStateException.class,
        () -> validate(Map.of(
            "c7-old", new Camunda7InstanceIdentity("legacy", "MY_"),
            "c7-new", new Camunda7InstanceIdentity("legacy", "MY_"))));

  }

  @Test
  @DisplayName("Distinct datasources or distinct table prefixes make two ids distinct engines")
  public void distinctInstancesAreAccepted() {

    assertDoesNotThrow(
        () -> validate(Map.of(
            "c7-old", new Camunda7InstanceIdentity("legacy", null),
            "c7-new", new Camunda7InstanceIdentity(null, null))));

    // the migration setup on ONE database: same datasource, separate engine tables
    assertDoesNotThrow(
        () -> validate(Map.of(
            "c7-old", new Camunda7InstanceIdentity(null, null),
            "c7-new", new Camunda7InstanceIdentity(null, "NEW_"))));

  }

  @Test
  @DisplayName("A single id is never checked, and an unavailable resolver skips the check")
  public void singleIdAndMissingResolverAreNoOps() {

    assertDoesNotThrow(() -> Camunda7InstanceIdentity.validateDistinct(List.of("c7"), id -> null));
    assertDoesNotThrow(() -> Camunda7InstanceIdentity.validateDistinct(List.of("a", "b"), null));
    assertDoesNotThrow(() -> Camunda7InstanceIdentity.validateDistinct(null, id -> null));

  }

}
