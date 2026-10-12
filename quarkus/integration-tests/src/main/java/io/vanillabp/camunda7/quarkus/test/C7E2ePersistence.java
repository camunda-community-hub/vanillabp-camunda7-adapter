package io.vanillabp.camunda7.quarkus.test;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link C7E2eAggregate}. It saves within the caller's JTA transaction.
 * While a task is processed, that is the engine's job transaction.
 */
@ApplicationScoped
public class C7E2ePersistence implements AggregatePersistenceAware<C7E2eAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<C7E2eAggregate> getAggregateClass() {

    return C7E2eAggregate.class;

  }

  @Override
  public C7E2eAggregate save(
      final C7E2eAggregate aggregate) {

    if (aggregate.getId() == null) {
      entityManager.persist(aggregate);
      entityManager.flush(); // assign the generated id (used as the business key)
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  /**
   * Named explicitly. The platform builds a workflow which the BPMS starts on its own. It
   * needs the name of the id property to hand the aggregate its identity. A hand-written
   * persistence has nothing to derive that name from.
   *
   * @return The name of the aggregate's id property
   */
  @Override
  public String getAggregateIdName() {

    return "id";

  }

  @Override
  public Object getAggregateId(
      final C7E2eAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public C7E2eAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(C7E2eAggregate.class, aggregateId);

  }

}
