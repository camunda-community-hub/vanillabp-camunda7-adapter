package io.vanillabp.camunda7.quarkus.test;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * JPA persistence of {@link C7TimerAggregate}.
 */
@ApplicationScoped
public class C7TimerPersistence implements AggregatePersistenceAware<C7TimerAggregate> {

  @Inject
  EntityManager entityManager;

  @Override
  public Class<C7TimerAggregate> getAggregateClass() {

    return C7TimerAggregate.class;

  }

  @Override
  public C7TimerAggregate save(
      final C7TimerAggregate aggregate) {

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
      final C7TimerAggregate aggregate) {

    return aggregate.getId();

  }

  @Override
  public C7TimerAggregate loadById(
      final Object aggregateId) {

    return entityManager.find(C7TimerAggregate.class, aggregateId);

  }

}
