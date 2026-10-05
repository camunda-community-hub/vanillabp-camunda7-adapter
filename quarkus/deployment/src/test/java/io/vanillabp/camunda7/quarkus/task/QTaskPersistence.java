package io.vanillabp.camunda7.quarkus.task;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link QTaskAggregate} - loads and saves within the caller's
 * JTA transaction (the engine's job transaction when processing tasks).
 */
@ApplicationScoped
public class QTaskPersistence implements AggregatePersistenceAware<QTaskAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<QTaskAggregate> getAggregateClass() {

    return QTaskAggregate.class;

  }

  @Override
  public QTaskAggregate save(
      final QTaskAggregate aggregate) {

    if (aggregate.getId() == null) {
      entityManager.persist(aggregate);
      entityManager.flush(); // assign the generated ID (used as the business key)
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  @Override
  public Object getAggregateId(
      final QTaskAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public QTaskAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(QTaskAggregate.class, aggregateId);

  }

}
