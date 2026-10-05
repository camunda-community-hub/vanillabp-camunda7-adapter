package io.vanillabp.camunda7.quarkus.test.versions;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link C7VersionsAggregate}.
 */
@ApplicationScoped
public class C7VersionsPersistence implements AggregatePersistenceAware<C7VersionsAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<C7VersionsAggregate> getAggregateClass() {

    return C7VersionsAggregate.class;

  }

  @Override
  public C7VersionsAggregate save(
      final C7VersionsAggregate aggregate) {

    if (aggregate.getId() == null) {
      entityManager.persist(aggregate);
      entityManager.flush(); // assign the generated id, which is the business key
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  @Override
  public Object getAggregateId(
      final C7VersionsAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public C7VersionsAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(C7VersionsAggregate.class, aggregateId);

  }

}
