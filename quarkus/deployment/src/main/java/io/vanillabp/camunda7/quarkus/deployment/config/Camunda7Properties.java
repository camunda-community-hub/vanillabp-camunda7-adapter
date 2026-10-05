package io.vanillabp.camunda7.quarkus.deployment.config;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;

/**
 * Camunda 7 adapter build-time properties. Empty - the adapter's engine settings
 * are RUN_TIME configuration (see the runtime module's
 * {@code VanillaBpCamunda7Properties} overlay).
 */
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface Camunda7Properties {

}
