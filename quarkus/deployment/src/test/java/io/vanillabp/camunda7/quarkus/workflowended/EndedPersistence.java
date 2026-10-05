package io.vanillabp.camunda7.quarkus.workflowended;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class EndedPersistence implements AggregatePersistenceAware<EndedAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<EndedAggregate> getAggregateClass() {

    return EndedAggregate.class;

  }

  @Override
  public EndedAggregate save(
      final EndedAggregate aggregate) {

    if (aggregate.getId() == null) {
      entityManager.persist(aggregate);
      entityManager.flush(); // assign the generated ID (used as the business key)
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  @Override
  public Object getAggregateId(
      final EndedAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public EndedAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(EndedAggregate.class, aggregateId);

  }

}
