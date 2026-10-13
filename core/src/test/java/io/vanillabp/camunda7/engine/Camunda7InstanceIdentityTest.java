package io.vanillabp.camunda7.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two <code>camunda7</code> adapter ids only make sense if they are DIFFERENT engines. Each
 * needs a datasource of its own, or a table prefix of its own on a shared datasource. The
 * rule is written once in the adapter's core. The adapter SPI hook
 * {@code validateDistinctAdapterInstances} reaches it.
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
  @DisplayName("The reserved name 'default' is the application's default datasource, too")
  public void theReservedNameDefaultIsTheDefaultDataSource() {

    assertThrows(
        IllegalStateException.class,
        () -> validate(Map.of(
            "c7-old", new Camunda7InstanceIdentity("default", null),
            "c7-new", new Camunda7InstanceIdentity(null, null))));

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

    // the migration setup on ONE database: the same datasource, separate engine tables
    assertDoesNotThrow(
        () -> validate(Map.of(
            "c7-old", new Camunda7InstanceIdentity(null, null),
            "c7-new", new Camunda7InstanceIdentity(null, "NEW_"))));

  }

  @Test
  @DisplayName("The key for the check across adapter types is equal exactly where two camunda7 ids are refused")
  public void theAddressedSystemComparesLikeTheCheckOfOneType() {

    final var identities = List.of(
        new Camunda7InstanceIdentity(null, null),
        new Camunda7InstanceIdentity("", " "),
        new Camunda7InstanceIdentity("default", null),
        new Camunda7InstanceIdentity("legacy", null),
        new Camunda7InstanceIdentity("legacy", "MY_"),
        new Camunda7InstanceIdentity(null, "MY_"));
    for (final var first : identities) {
      for (final var second : identities) {
        assertEquals(
            first.equals(second),
            first
                .addressedSystem()
                .key()
                .equals(second
                    .addressedSystem()
                    .key()),
            () -> "%s against %s".formatted(first, second));
      }
    }

  }

  @Test
  @DisplayName("The addressed system names datasource and prefix, and both ways to separate two engines")
  public void theAddressedSystemGuides() {

    final var system = new Camunda7InstanceIdentity("legacy", null).addressedSystem();

    assertEquals("datasource legacy, table prefix <no table prefix>", system.description());
    assertTrue(
        system
            .howToSeparate()
            .contains("'vanillabp.adapters.<id>.data-source-name'"),
        system::howToSeparate);
    assertTrue(
        system
            .howToSeparate()
            .contains("'vanillabp.adapters.<id>.table-prefix'"),
        system::howToSeparate);
    // a fork adapter is generated from this code, and its key must stay equal to this one
    assertFalse(
        system
            .key()
            .toLowerCase()
            .contains("camunda"),
        system::key);

  }

  @Test
  @DisplayName("A single id is never checked, and an unavailable resolver skips the check")
  public void singleIdAndMissingResolverAreNoOps() {

    assertDoesNotThrow(() -> Camunda7InstanceIdentity.validateDistinct(List.of("c7"), id -> null));
    assertDoesNotThrow(() -> Camunda7InstanceIdentity.validateDistinct(List.of("a", "b"), null));
    assertDoesNotThrow(() -> Camunda7InstanceIdentity.validateDistinct(null, id -> null));

  }

}
