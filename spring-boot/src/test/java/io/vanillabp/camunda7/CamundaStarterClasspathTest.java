package io.vanillabp.camunda7;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the exclusion that makes this adapter work on Spring Boot 4.
 *
 * <p>Camunda ships two builds of the same code: {@code camunda-bpm-spring-boot-starter-4} is compiled
 * against Spring Boot 4, the unsuffixed {@code camunda-bpm-spring-boot-starter} against Spring Boot 3.
 * Both jars contain the very same 100 class names, {@code CamundaBpmAutoConfiguration} among them. The
 * webapp starter has no "-4" variant and reaches the Spring Boot 3 one transitively through
 * {@code camunda-bpm-spring-boot-starter-webapp-core}, so it is excluded in the pom.
 *
 * <p>Without that exclusion both jars are on the class path and it is left to class path order which
 * {@code CamundaBpmAutoConfiguration} gets loaded. If the Spring Boot 3 one wins, the failure is the one
 * this whole migration is about: a {@code ClassNotFoundException} on
 * {@code HibernateJpaAutoConfiguration}, which moved to {@code o.s.boot.hibernate.autoconfigure} in
 * Spring Boot 4. Nothing in a normal build would point at a duplicate dependency as the cause.
 *
 * <p>So this test asserts what the pom intends: exactly one copy of that class, and it comes from the
 * "-4" jar. It goes red as soon as any dependency re-introduces the Spring Boot 3 starter.
 */
class CamundaStarterClasspathTest {

    private static final String AUTO_CONFIGURATION =
            "org/camunda/bpm/spring/boot/starter/CamundaBpmAutoConfiguration.class";

    private List<String> locationsOf(final String resource) throws IOException {

        return Collections.list(getClass().getClassLoader().getResources(resource))
                .stream()
                .map(url -> url.toString())
                .toList();

    }

    @Test
    void camundasSpringBootAutoConfigurationExistsExactlyOnce() throws IOException {

        assertThat(locationsOf(AUTO_CONFIGURATION))
                .as("both Camunda core starters are on the class path - which CamundaBpmAutoConfiguration "
                        + "is loaded then depends on class path order")
                .hasSize(1);

    }

    @Test
    void itComesFromTheSpringBoot4Build() throws IOException {

        assertThat(locationsOf(AUTO_CONFIGURATION).get(0))
                .as("the Spring Boot 3 build of Camunda's starter is being used, which fails on the "
                        + "relocated HibernateJpaAutoConfiguration")
                .contains("camunda-bpm-spring-boot-starter-4");

    }

    @Test
    void theAdapterRegistersItsThreeAutoConfigurations() throws IOException {

        final var imports = new String(getClass().getClassLoader()
                .getResourceAsStream(
                        "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")
                .readAllBytes());

        assertThat(imports.lines().filter(line -> !line.isBlank()).toList())
                .containsExactlyInAnyOrder(
                        "io.vanillabp.camunda7.deployment.Camunda7DisableAutoDeploymentConfiguration",
                        "io.vanillabp.camunda7.jobexecutor.WakeupJobExecutorConfiguration",
                        "io.vanillabp.camunda7.Camunda7AdapterConfiguration");

    }

}
