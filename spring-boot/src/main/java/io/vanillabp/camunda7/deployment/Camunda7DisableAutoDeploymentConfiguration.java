package io.vanillabp.camunda7.deployment;

import org.camunda.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import jakarta.annotation.PostConstruct;

@Configuration
@AutoConfigureOrder(Ordered.HIGHEST_PRECEDENCE)
public class Camunda7DisableAutoDeploymentConfiguration {

    @Autowired
    private CamundaBpmProperties properties;
    
    @PostConstruct
    public void disableAutoDeployment() {
    
        properties.setAutoDeploymentEnabled(false);
        
    }
    
}
