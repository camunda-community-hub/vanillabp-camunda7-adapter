package io.vanillabp.camunda7.quarkus.listeners;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link ListenerAggregate}.
 */
@ApplicationScoped
public class ListenerPersistence implements AggregatePersistenceAware<ListenerAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<ListenerAggregate> getAggregateClass() {

    return ListenerAggregate.class;

  }

  @Override
  public ListenerAggregate save(
      final ListenerAggregate aggregate) {

    if (aggregate.getId() == null) {
      entityManager.persist(aggregate);
      entityManager.flush(); // assign the generated id, which is the business key
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  @Override
  public Object getAggregateId(
      final ListenerAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public ListenerAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(ListenerAggregate.class, aggregateId);

  }

}
