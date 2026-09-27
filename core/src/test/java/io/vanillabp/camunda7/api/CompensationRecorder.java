package io.vanillabp.camunda7.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.camunda.bpm.engine.delegate.DelegateExecution;
import org.camunda.bpm.engine.delegate.JavaDelegate;

/**
 * Records what the engine does with the compensation handlers of
 * <code>api/compensation-facts.bpmn</code>: how many of them are inside their delegate at
 * the same moment, on which threads, and in which order they were entered.
 * <p>
 * A handler waits for the other one after it entered. Where the engine runs both at the
 * same time the second arrival releases the first immediately; where it runs them one after
 * the other the wait runs out and the overlap it was looking for never happened. So the
 * measurement answers with a fact either way instead of with a timing which might have been
 * luck.
 */
public final class CompensationRecorder {

  /** How long a handler waits for the other one before it accepts it is alone. */
  private static final long WAIT_FOR_THE_OTHER_HANDLER_MILLIS = 2000;

  private static final AtomicInteger insideAHandler = new AtomicInteger();

  private static final AtomicInteger mostHandlersInsideAtOnce = new AtomicInteger();

  private static final List<String> entered = new CopyOnWriteArrayList<>();

  private static final List<String> threads = new CopyOnWriteArrayList<>();

  private static final List<Integer> siblings = new CopyOnWriteArrayList<>();

  private static volatile CountDownLatch bothHandlersEntered = new CountDownLatch(2);

  private CompensationRecorder() {
  }

  /**
   * Forgets the previous run, so every measurement starts from nothing.
   */
  static void reset() {

    insideAHandler.set(0);
    mostHandlersInsideAtOnce.set(0);
    entered.clear();
    threads.clear();
    siblings.clear();
    bothHandlersEntered = new CountDownLatch(2);

  }

  /**
   * @return The element IDs of the handlers, in the order the engine entered them
   */
  static List<String> handlersInTheOrderTheyWereEntered() {

    return List.copyOf(entered);

  }

  /**
   * @return The highest number of handlers which were inside their delegate at one moment
   */
  static int mostHandlersInsideAtOnce() {

    return mostHandlersInsideAtOnce.get();
  }

  static List<Integer> siblingExecutions() {

    return List.copyOf(siblings);

  }

  /**
   * @return The names of the threads the handlers ran on, in the order they were entered
   */
  static List<String> threadsTheHandlersRanOn() {

    return List.copyOf(threads);

  }

  /**
   * The delegate of an activity which is compensated later - it does nothing, it only has to
   * complete so that the engine remembers it for the compensation throw event.
   */
  public static class DoingNothing implements JavaDelegate {

    @Override
    public void execute(
        final DelegateExecution execution) {

    }

  }

  /**
   * The delegate of a compensation handler - it records itself and then waits for the other
   * handler as long as {@link #WAIT_FOR_THE_OTHER_HANDLER_MILLIS} allows.
   */
  public static class Handler implements JavaDelegate {

    @Override
    public void execute(
        final DelegateExecution execution) throws Exception {

      entered.add(execution.getCurrentActivityId());
      final var parent = ((org.camunda.bpm.engine.impl.persistence.entity.ExecutionEntity) execution).getParent();
      siblings.add(parent == null ? -1 : parent.getExecutions().size());
      threads.add(Thread.currentThread().getName());
      final var inside = insideAHandler.incrementAndGet();
      mostHandlersInsideAtOnce.accumulateAndGet(inside, Math::max);
      final var latch = bothHandlersEntered;
      latch.countDown();
      try {
        latch.await(WAIT_FOR_THE_OTHER_HANDLER_MILLIS, TimeUnit.MILLISECONDS);
      } finally {
        insideAHandler.decrementAndGet();
      }

    }

  }

}
