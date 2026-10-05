package io.vanillabp.camunda7.quarkus.callactivity;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

@ApplicationScoped
public class CallActivityPersistence implements AggregatePersistenceAware<CallActivityAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<CallActivityAggregate> getAggregateClass() {

    return CallActivityAggregate.class;

  }

  @Override
  public CallActivityAggregate save(
      final CallActivityAggregate aggregate) {

    if (aggregate.getId() == null) {
      entityManager.persist(aggregate);
      entityManager.flush(); // assign the generated ID (used as the business key)
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  @Override
  public Object getAggregateId(
      final CallActivityAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public CallActivityAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(CallActivityAggregate.class, aggregateId);

  }

}
