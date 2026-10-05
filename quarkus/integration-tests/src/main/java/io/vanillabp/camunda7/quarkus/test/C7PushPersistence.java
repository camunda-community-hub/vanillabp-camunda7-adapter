package io.vanillabp.camunda7.quarkus.test;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link C7PushAggregate}.
 */
@ApplicationScoped
public class C7PushPersistence implements AggregatePersistenceAware<C7PushAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<C7PushAggregate> getAggregateClass() {

    return C7PushAggregate.class;

  }

  @Override
  public C7PushAggregate save(
      final C7PushAggregate aggregate) {

    if (aggregate.getId() == null) {
      entityManager.persist(aggregate);
      entityManager.flush(); // assign the generated id (used as the business key)
      return aggregate;
    }
    return entityManager.merge(aggregate);

  }

  /**
   * Named explicitly: a workflow the BPMS starts on its own is built by the platform,
   * which needs the id property's name to hand the aggregate its identity - and a
   * hand-written persistence has nothing to derive it from.
   *
   * @return The name of the aggregate's id property
   */
  @Override
  public String getAggregateIdName() {

    return "id";

  }

  @Override
  public Object getAggregateId(
      final C7PushAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public C7PushAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(C7PushAggregate.class, aggregateId);

  }

}
