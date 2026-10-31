package io.vanillabp.camunda7.quarkus.configdoc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.camunda7.quarkus.runtime.VanillaBpCamunda7Properties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Holds the names of the map keys in the configuration documentation of the runtime module. The
 * Quarkus build writes this documentation for every key of every level. A map key is a place
 * where the application chooses the name, for example an adapter id. Without a name of its own,
 * the documentation names the place after the Java method, so it shows
 * <code>vanillabp.adapters."adapters"...</code>. Every map of the overlay therefore names its key,
 * with the same names as the platform and the other adapters, see decision 49 in the repository's
 * DECISIONS.md.
 * <p>
 * The test reads the file the build wrote, so it sees what an IDE or a generated page sees. It
 * reads only the key names and does not parse the rest of the file, which is an internal format
 * of Quarkus.
 */
@ExtendWith(SuppressOutputExtension.class)
public class EveryMapKeyHasANameTest {

  private static final String CONFIG_MODEL = "META-INF/quarkus-config-doc/quarkus-config-model.json";

  /** The names of the map keys of this module, the table of the decision cited above. */
  private static final Set<String> MAP_KEY_NAMES = Set.of(
      "adapter-id",
      "workflow-module-id",
      "bpmn-process-id",
      "task-definition",
      "message-name",
      "plugin-name",
      "property-name");

  private static final Pattern PROPERTY = Pattern.compile("\"property\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

  private static final Pattern MAP_KEY = Pattern.compile("\"([^\"]*)\"");

  private static Set<String> keys;

  @BeforeAll
  public static void readTheConfigModel() throws IOException {

    final var model = configModelOfTheRuntimeModule();
    keys = new TreeSet<>();
    final var properties = PROPERTY.matcher(model);
    while (properties.find()) {
      keys.add(properties
          .group(1)
          .replace("\\\"", "\""));
    }

  }

  @Test
  @DisplayName("Every map key in the documented keys has one of the shared names")
  public void everyMapKeyHasASharedName() {

    assertFalse(keys.isEmpty(), "the config model names no key at all");
    final var unnamed = new TreeSet<String>();
    for (final var key : keys) {
      final var mapKeys = MAP_KEY.matcher(key);
      while (mapKeys.find()) {
        if (!MAP_KEY_NAMES.contains(mapKeys.group(1))) {
          unnamed.add(key);
        }
      }
    }
    assertEquals(Set.of(), unnamed, "these keys have a map key without a shared name, "
        + "add @ConfigDocMapKey to the map");

  }

  @Test
  @DisplayName("The keys of a task carry the name of every level above them")
  public void theTaskLevelNamesEveryLevel() {

    for (final var prefix : List.of(
        "vanillabp.adapters.\"adapter-id\".",
        "vanillabp.workflow-modules.\"workflow-module-id\".adapters.\"adapter-id\".",
        "vanillabp.workflow-modules.\"workflow-module-id\".workflows.\"bpmn-process-id\".adapters.\"adapter-id\".",
        "vanillabp.workflow-modules.\"workflow-module-id\".workflows.\"bpmn-process-id\".tasks.\"task-definition\".adapters.\"adapter-id\".",
        "vanillabp.adapters.\"adapter-id\".engine-plugins.\"plugin-name\".properties.\"property-name\"")) {
      assertTrue(keys
          .stream()
          .anyMatch(key -> key.startsWith(prefix)),
          "no documented key starts with "
              + prefix);
    }

  }

  private static String configModelOfTheRuntimeModule() throws IOException {

    final var location = VanillaBpCamunda7Properties.class
        .getProtectionDomain()
        .getCodeSource()
        .getLocation()
        .toString();
    final var files = EveryMapKeyHasANameTest.class
        .getClassLoader()
        .getResources(CONFIG_MODEL);
    while (files.hasMoreElements()) {
      final var file = files.nextElement();
      // a directory in the reactor build, a jar file otherwise
      if (!file
          .toString()
          .contains(location)) {
        continue;
      }
      try (InputStream in = file.openStream()) {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
    }
    throw new AssertionError("no %s next to %s, is the extension processor configured?"
        .formatted(CONFIG_MODEL, location));

  }

}
