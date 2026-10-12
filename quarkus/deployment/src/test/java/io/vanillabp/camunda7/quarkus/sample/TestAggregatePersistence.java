package io.vanillabp.camunda7.quarkus.sample;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of the {@link TestAggregate}. It saves within the caller's JTA
 * transaction. So the aggregate commits or rolls back together with the embedded engine's
 * state. The transaction tests check exactly that.
 */
@ApplicationScoped
public class TestAggregatePersistence implements AggregatePersistenceAware<TestAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<TestAggregate> getAggregateClass() {

    return TestAggregate.class;

  }

  @Override
  public TestAggregate save(
      final TestAggregate aggregate) {

    if (aggregate.getId() == null) {
      entityManager.persist(aggregate);
      entityManager.flush(); // assign the generated ID (used as the business key)
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  @Override
  public Object getAggregateId(
      final TestAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public TestAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(TestAggregate.class, aggregateId);

  }

}
