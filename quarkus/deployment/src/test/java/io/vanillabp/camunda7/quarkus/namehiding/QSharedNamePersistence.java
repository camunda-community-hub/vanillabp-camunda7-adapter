package io.vanillabp.camunda7.quarkus.namehiding;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link QSharedNameAggregate}. The start refuses the model, so it is
 * never asked for anything.
 */
@ApplicationScoped
public class QSharedNamePersistence implements AggregatePersistenceAware<QSharedNameAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<QSharedNameAggregate> getAggregateClass() {

    return QSharedNameAggregate.class;

  }

  @Override
  public QSharedNameAggregate save(
      final QSharedNameAggregate aggregate) {

    return entityManager.merge(aggregate);

  }

  @Override
  public Object getAggregateId(
      final QSharedNameAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public QSharedNameAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(QSharedNameAggregate.class, aggregateId);

  }

}
