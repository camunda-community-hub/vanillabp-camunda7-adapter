package io.vanillabp.camunda7.quarkus.namehiding;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link QNameHidingAggregate}, in the caller's JTA transaction.
 */
@ApplicationScoped
public class QNameHidingPersistence implements AggregatePersistenceAware<QNameHidingAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<QNameHidingAggregate> getAggregateClass() {

    return QNameHidingAggregate.class;

  }

  @Override
  public QNameHidingAggregate save(
      final QNameHidingAggregate aggregate) {

    if (entityManager.find(QNameHidingAggregate.class, aggregate.getId()) == null) {
      entityManager.persist(aggregate);
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  @Override
  public Object getAggregateId(
      final QNameHidingAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public QNameHidingAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(QNameHidingAggregate.class, aggregateId);

  }

}
