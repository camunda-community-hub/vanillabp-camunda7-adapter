package io.vanillabp.camunda7.quarkus.lossyformat;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link LossyAggregate}.
 */
@ApplicationScoped
public class LossyPersistence implements AggregatePersistenceAware<LossyAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<LossyAggregate> getAggregateClass() {

    return LossyAggregate.class;

  }

  @Override
  public LossyAggregate save(
      final LossyAggregate aggregate) {

    if (aggregate.getId() == null) {
      entityManager.persist(aggregate);
      entityManager.flush();
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  @Override
  public Object getAggregateId(
      final LossyAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public LossyAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(LossyAggregate.class, aggregateId);

  }

}
