package io.vanillabp.camunda7.springboot.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.beans.PropertyDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.BeanUtils;
import org.springframework.boot.configurationmetadata.ConfigurationMetadataProperty;
import org.springframework.boot.configurationmetadata.ConfigurationMetadataRepositoryJsonBuilder;
import org.springframework.boot.context.properties.bind.DataObjectPropertyName;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.util.ClassUtils;

import io.vanillabp.camunda7.Camunda7Adapter;
import io.vanillabp.camunda7.springboot.VanillaBpCamunda7Properties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Holds the configuration metadata of this module against the keys the adapter reads. An IDE
 * offers and checks the keys in <code>application.yaml</code> from that metadata. So a key which
 * the code reads but the metadata does not describe is shown as unknown, and a key described but
 * not read is offered for nothing.
 * <p>
 * The keys the adapter reads are the properties of {@link VanillaBpCamunda7Properties}, because
 * Spring Boot binds every key of this adapter onto that class. The test walks the class like the
 * binder does. A map becomes a level whose key the application chooses, written as
 * <code>*</code> here.
 * <p>
 * The metadata has two parts, see decision 48 in the repository's DECISIONS.md. The
 * configuration processor writes one entry per map of the overlay class, and an IDE finds the
 * keys below a map key through the type of the map's values. The hand-written part describes
 * every key of the adapter level once more, under the adapter id which is also the adapter
 * type. This is the only place where a key gets a description, a type and a default which
 * every IDE shows.
 */
@ExtendWith(SuppressOutputExtension.class)
public class EveryKeyIsKnownToTheIdeTest {

  private static final String METADATA = "META-INF/spring-configuration-metadata.json";

  private static final String PREFIX = "vanillabp";

  private static final String ADAPTER_LEVEL = PREFIX
      + ".adapters.*.";

  private static final String DESCRIBED_ADAPTER_LEVEL = PREFIX
      + ".adapters."
      + Camunda7Adapter.ADAPTER_TYPE
      + ".";

  private static final String SCOPE_LEVELS = PREFIX
      + ".workflow-modules.";

  private static Map<String, ConfigurationMetadataProperty> metadata;

  private static Map<String, Type> keysRead;

  @BeforeAll
  public static void readBothSides() throws IOException {

    metadata = metadataOfThisModule();
    keysRead = new TreeMap<>();
    collectKeys(PREFIX, VanillaBpCamunda7Properties.class, keysRead);

  }

  @Test
  @DisplayName("the processor describes every map of the overlay with the type of its values")
  public void everyMapOfTheOverlayIsDescribedWithItsValueType() {

    for (final var property : bindableProperties(VanillaBpCamunda7Properties.class)) {
      final var name = PREFIX
          + "."
          + DataObjectPropertyName.toDashedForm(property.getName());
      final var entry = metadata.get(name);
      assertNotNull(entry, "the configuration processor wrote no entry '%s'".formatted(name));
      assertEquals(
          typeName(property.getReadMethod().getGenericReturnType()),
          typeName(entry.getType()),
          "the type of '%s' in the metadata".formatted(name));
    }

  }

  @Test
  @DisplayName("every key read at the adapter level is described with a type and a description")
  public void everyAdapterLevelKeyIsDescribed() {

    final var missing = new TreeSet<String>();
    keysRead.forEach((
        key,
        type) -> {
      // a key below a map of the adapter level is described by the entry of that map
      if (!key.startsWith(ADAPTER_LEVEL) || key
          .substring(ADAPTER_LEVEL.length())
          .contains("*")) {
        return;
      }
      final var name = DESCRIBED_ADAPTER_LEVEL + key.substring(ADAPTER_LEVEL.length());
      final var entry = metadata.get(name);
      if ((entry == null) || isBlank(entry.getDescription())) {
        missing.add(name);
        return;
      }
      assertEquals(
          typeName(type),
          typeName(entry.getType()),
          "the type of '%s' in the metadata".formatted(name));
    });
    assertTrue(
        missing.isEmpty(),
        "add these keys to META-INF/additional-spring-configuration-metadata.json, with a type and a description: "
            + missing);

  }

  @Test
  @DisplayName("every key read below a workflow module names that level in its description")
  public void everyScopedKeyNamesItsLevels() {

    final var notNamed = new TreeSet<String>();
    keysRead
        .keySet()
        .stream()
        .filter(key -> key.startsWith(SCOPE_LEVELS) && key.contains(".adapters.*."))
        .map(key -> key.substring(key.lastIndexOf(".adapters.*.") + ".adapters.*.".length()))
        .distinct()
        .forEach(key -> {
          final var entry = metadata.get(DESCRIBED_ADAPTER_LEVEL + key);
          if ((entry == null) || !String.valueOf(entry.getDescription()).contains(SCOPE_LEVELS)) {
            notNamed.add(DESCRIBED_ADAPTER_LEVEL + key);
          }
        });
    assertTrue(
        notNamed.isEmpty(),
        "these keys are read below 'vanillabp.workflow-modules' as well, and their description has to say where: "
            + notNamed);

  }

  @Test
  @DisplayName("every key described at the adapter level is read by the adapter")
  public void everyDescribedKeyIsRead() {

    final var notRead = new TreeSet<String>();
    metadata
        .keySet()
        .stream()
        .filter(name -> name.startsWith(DESCRIBED_ADAPTER_LEVEL))
        .filter(name -> !keysRead.containsKey(ADAPTER_LEVEL + name.substring(DESCRIBED_ADAPTER_LEVEL.length())))
        .forEach(notRead::add);
    assertTrue(
        notRead.isEmpty(),
        "the metadata describes keys the adapter does not read: "
            + notRead);

  }

  @Test
  @DisplayName("a default in the metadata is the value the adapter starts with")
  public void everyDefaultIsTheRealOne() {

    metadata.forEach((
        name,
        entry) -> {
      // a key nobody reads is reported by everyDescribedKeyIsRead
      if (!name.startsWith(DESCRIBED_ADAPTER_LEVEL) || (entry.getDefaultValue() == null) || !keysRead
          .containsKey(ADAPTER_LEVEL + name.substring(DESCRIBED_ADAPTER_LEVEL.length()))) {
        return;
      }
      final var path = name
          .substring(DESCRIBED_ADAPTER_LEVEL.length())
          .split("\\.");
      Object owner = BeanUtils.instantiateClass(adapterLevelType());
      PropertyDescriptor property = null;
      for (final var segment : path) {
        if (property != null) {
          final var section = readValue(property, owner);
          // a section which starts empty is built by the binder once a key of it is set
          owner = section != null
              ? section
              : BeanUtils.instantiateClass(property.getPropertyType());
        }
        property = propertyNamed(owner.getClass(), segment);
      }
      final var initial = readValue(property, owner);
      if (initial == null) {
        // the field starts empty, and the default is applied where the key is read.
        // The description then says so, and nothing here can check the number
        return;
      }
      assertEquals(
          initial,
          ApplicationConversionService
              .getSharedInstance()
              .convert(entry.getDefaultValue(), ClassUtils.resolvePrimitiveIfNecessary(property.getPropertyType())),
          "the default of '%s' in the metadata".formatted(name));
    });

  }

  private static Class<?> adapterLevelType() {

    final var adapters = propertyNamed(VanillaBpCamunda7Properties.class, "adapters");
    return valueTypeOf(adapters.getReadMethod().getGenericReturnType());

  }

  /**
   * The keys bound onto one class, as the JavaBean binder of Spring Boot finds them: every
   * property with a getter and a setter. A map is a key of its own, and where its values are a
   * class of VanillaBP, it adds a level whose key the application chooses. A class of VanillaBP
   * which is not in a map adds a level named after the property.
   */
  private static void collectKeys(
      final String prefix,
      final Class<?> type,
      final Map<String, Type> keys) {

    for (final var property : bindableProperties(type)) {
      final var key = prefix
          + "."
          + DataObjectPropertyName.toDashedForm(property.getName());
      final var genericType = property
          .getReadMethod()
          .getGenericReturnType();
      if (Map.class.isAssignableFrom(property.getPropertyType())) {
        keys.put(key, genericType);
        if (isNested(valueTypeOf(genericType))) {
          collectKeys(key
              + ".*", valueTypeOf(genericType), keys);
        }
      } else if (isNested(property.getPropertyType())) {
        collectKeys(key, property.getPropertyType(), keys);
      } else {
        keys.put(key, ClassUtils.resolvePrimitiveIfNecessary(property.getPropertyType()));
      }
    }

  }

  private static List<PropertyDescriptor> bindableProperties(
      final Class<?> type) {

    final var result = new ArrayList<PropertyDescriptor>();
    for (final var property : BeanUtils.getPropertyDescriptors(type)) {
      if ((property.getWriteMethod() != null) && (property.getReadMethod() != null)) {
        result.add(property);
      }
    }
    return result;

  }

  private static boolean isNested(
      final Class<?> type) {

    return !type.isPrimitive() && !type.isEnum() && !Collection.class.isAssignableFrom(type) && type
        .getPackageName()
        .startsWith("io.vanillabp");

  }

  private static Class<?> valueTypeOf(
      final Type mapType) {

    final var valueType = ((ParameterizedType) mapType).getActualTypeArguments()[1];
    return valueType instanceof ParameterizedType parameterized
        ? (Class<?>) parameterized.getRawType()
        : (Class<?>) valueType;

  }

  private static PropertyDescriptor propertyNamed(
      final Class<?> type,
      final String dashedName) {

    for (final var property : bindableProperties(type)) {
      if (DataObjectPropertyName
          .toDashedForm(property.getName())
          .equals(dashedName)) {
        return property;
      }
    }
    throw new AssertionError("'%s' has no property '%s'".formatted(type.getName(), dashedName));

  }

  private static Object readValue(
      final PropertyDescriptor property,
      final Object owner) {

    try {
      return property
          .getReadMethod()
          .invoke(owner);
    } catch (final ReflectiveOperationException e) {
      throw new AssertionError("cannot read '%s'".formatted(property.getName()), e);
    }

  }

  /**
   * The processor writes a nested class with a dollar sign and a type argument list without
   * blanks. Java's own type names have a dot and a blank there.
   */
  private static String typeName(
      final Object type) {

    final var name = type instanceof Type javaType
        ? javaType.getTypeName()
        : String.valueOf(type);
    return name
        .replace('$', '.')
        .replace(" ", "");

  }

  private static boolean isBlank(
      final Object text) {

    return (text == null) || text
        .toString()
        .isBlank();

  }

  /**
   * The metadata of THIS module, which is the file next to the overlay class. The class path
   * of the test holds the platform's file as well. It is read with Spring Boot's own reader of
   * the format, which tools use as well.
   */
  private static Map<String, ConfigurationMetadataProperty> metadataOfThisModule() throws IOException {

    final var location = VanillaBpCamunda7Properties.class
        .getProtectionDomain()
        .getCodeSource()
        .getLocation()
        .toString();
    final var files = EveryKeyIsKnownToTheIdeTest.class
        .getClassLoader()
        .getResources(METADATA);
    while (files.hasMoreElements()) {
      final var file = files.nextElement();
      if (!file
          .toString()
          .startsWith(location)) {
        continue;
      }
      try (InputStream in = file.openStream()) {
        return new TreeMap<>(ConfigurationMetadataRepositoryJsonBuilder
            .create(StandardCharsets.UTF_8)
            .withJsonResource(in)
            .build()
            .getAllProperties());
      }
    }
    throw new AssertionError("no %s next to %s, is the configuration processor configured?"
        .formatted(METADATA, location));

  }

}
