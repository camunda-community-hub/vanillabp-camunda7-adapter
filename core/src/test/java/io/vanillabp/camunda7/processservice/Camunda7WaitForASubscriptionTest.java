package io.vanillabp.camunda7.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How long a correlation which found nothing waiting asks the outbox to wait. The attempts
 * come quickly at first, because the engine usually reaches the event a moment after the
 * operation before it. They come less often the longer the wait lasts, and never after the
 * time-to-live.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7WaitForASubscriptionTest {

  @Test
  @DisplayName("The wait grows with the time waited, between the shortest and the longest wait")
  public void theWaitGrowsAndIsCapped() {

    assertEquals(
        Camunda7ProcessService.SHORTEST_WAIT_FOR_A_SUBSCRIPTION,
        Camunda7ProcessService.nextWaitForASubscription(Duration.ZERO, Duration.ofMinutes(1)));
    assertEquals(
        Duration.ofSeconds(3),
        Camunda7ProcessService.nextWaitForASubscription(Duration.ofSeconds(3), Duration.ofMinutes(1)));
    assertEquals(
        Camunda7ProcessService.LONGEST_WAIT_FOR_A_SUBSCRIPTION,
        Camunda7ProcessService.nextWaitForASubscription(Duration.ofMinutes(10), Duration.ofHours(1)));
    assertEquals(
        Camunda7ProcessService.LONGEST_WAIT_FOR_A_SUBSCRIPTION,
        Camunda7ProcessService.nextWaitForASubscription(Duration.ofMinutes(10), null),
        "an entry without the moment it was planned still waits at most the longest wait");

  }

  @Test
  @DisplayName("The wait never goes past what is left of the time-to-live")
  public void theWaitEndsWithTheTimeToLive() {

    assertEquals(
        Duration.ofMillis(40),
        Camunda7ProcessService.nextWaitForASubscription(Duration.ofSeconds(5), Duration.ofMillis(40)));
    assertEquals(
        Duration.ZERO,
        Camunda7ProcessService.nextWaitForASubscription(Duration.ofSeconds(5), Duration.ofMillis(-1)));

  }

}
