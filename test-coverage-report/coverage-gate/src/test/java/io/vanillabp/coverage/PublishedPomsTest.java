package io.vanillabp.coverage;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.PublishedPoms;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What the POMs of this repository hand an application, asserted on the files the build
 * publishes. Two things are asked of them, and the build which writes them sees neither.
 * <p>
 * The first is that a tool which only translates our source stays out of the classpath of
 * the applications using our artifacts. Lombok and the processors of Spring Boot and
 * Quarkus are read while javac runs and have nothing left to do once the class file
 * exists. An application asked for a workflow engine, not for them, and every jar it did
 * not ask for is one more thing to scan, to ship and to answer a CVE report about. The
 * scope which says that is {@code provided}: it puts the jar on our own compile path and
 * hands it to nobody.
 * <p>
 * The second is that a version in such a file is a version and not the name of a
 * property. Our build resolves a name because the POM holding the value is in the
 * reactor. A consumer reads the published file and resolves our parent as the newest
 * build of the snapshot, never as the build the child went out with, so the name is all
 * it gets the day that parent stops defining the property.
 * <p>
 * What the check knows sits in {@link PublishedPoms} of the platform's module
 * 'test-utils'. Every repository of VanillaBP can make this mistake and they all make it
 * in the same way, so the rule lives in one place and each repository calls it. A copy
 * per repository drifts, and the worth of this check is that it still runs in two years.
 * This test is the caller which names the file this repository publishes and the tools of
 * this build.
 * <p>
 * This repository publishes its POMs as they are, so an application reads the same file
 * a reviewer reads, and the check reads it too.
 * <p>
 * It is a gate in the module which already gates this repository as a whole, and that
 * module is the last of the reactor. So it reads what every other module has published by
 * then.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PublishedPomsTest {

  /**
   * The tools of this build, each as {@code groupId:artifactId}. A tool this repository
   * starts using belongs in this list, because nothing else knows that it is one. The
   * list names more than the build uses today so that the usual next one is covered as
   * well.
   */
  private static final Set<String> TOOLS_OF_THIS_BUILD = Set
      .of(
          "org.projectlombok:lombok",
          "io.quarkus:quarkus-extension-processor",
          "org.springframework.boot:spring-boot-autoconfigure-processor",
          "org.mapstruct:mapstruct-processor",
          "io.vanillabp:vanillabp-mapstruct-fluent-accessors");

  @Test
  @DisplayName("no POM of this repository hands an application a tool of the build")
  public void noPomHandsAnApplicationAToolOfTheBuild() {

    PublishedPoms
        .ofTheRepositoryUnderTest(PublishedPoms.THE_SOURCE_POM)
        .handAnApplicationNoToolOfTheBuild(TOOLS_OF_THIS_BUILD);

  }

  @Test
  @DisplayName("no POM of this repository owes a consumer a version it does not carry")
  public void noPomOwesAConsumerAValue() {

    PublishedPoms
        .ofTheRepositoryUnderTest(PublishedPoms.THE_SOURCE_POM)
        .handAnApplicationNoPropertyInsteadOfAValue();

  }

}
