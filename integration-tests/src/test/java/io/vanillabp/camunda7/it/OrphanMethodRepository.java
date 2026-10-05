package io.vanillabp.camunda7.it;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrphanMethodRepository extends JpaRepository<OrphanMethodAggregate, Long> {
}
