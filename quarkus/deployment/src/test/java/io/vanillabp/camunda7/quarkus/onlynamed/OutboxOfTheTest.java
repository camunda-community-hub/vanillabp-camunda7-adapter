package io.vanillabp.camunda7.quarkus.onlynamed;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for an outbox outside the database, like the one on MongoDB. Without a default
 * datasource the platform has no outbox of its own. The test starts its workflow in the
 * engine directly, so nothing is ever scheduled here.
 */
@ApplicationScoped
public class OutboxOfTheTest implements PhaseTwoOutbox {

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    throw new UnsupportedOperationException("the test starts its workflow in the engine directly");

  }

}
