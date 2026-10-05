package io.vanillabp.camunda7.it;

import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * The aggregates of the modelled-listener scenario.
 */
@Repository
@Profile("modelled-listeners")
public interface ModelledListenerRepository extends JpaRepository<ModelledListenerAggregate, Long> {
}
