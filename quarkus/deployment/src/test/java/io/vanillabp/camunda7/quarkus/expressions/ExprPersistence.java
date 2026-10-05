package io.vanillabp.camunda7.quarkus.expressions;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link ExprAggregate}.
 */
@ApplicationScoped
public class ExprPersistence implements AggregatePersistenceAware<ExprAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<ExprAggregate> getAggregateClass() {

    return ExprAggregate.class;

  }

  @Override
  public ExprAggregate save(
      final ExprAggregate aggregate) {

    if (aggregate.getId() == null) {
      entityManager.persist(aggregate);
      entityManager.flush();
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  @Override
  public Object getAggregateId(
      final ExprAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public ExprAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(ExprAggregate.class, aggregateId);

  }

}
