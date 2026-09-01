package io.vanillabp.camunda7.deployment;

import org.camunda.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.core.Ordered;

import jakarta.annotation.PostConstruct;

/*
 * Registered through META-INF/spring/...AutoConfiguration.imports, so it has to be an
 * @AutoConfiguration: @AutoConfigureOrder is only honoured for auto-configurations, and with a plain
 * @Configuration the ordering was silently ignored.
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
public class Camunda7DisableAutoDeploymentConfiguration {

    @Autowired
    private CamundaBpmProperties properties;
    
    @PostConstruct
    public void disableAutoDeployment() {
    
        properties.setAutoDeploymentEnabled(false);
        
    }
    
}
