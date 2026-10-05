package io.vanillabp.camunda7.it;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ForeignConditionRepository extends JpaRepository<ForeignConditionAggregate, String> {

}
