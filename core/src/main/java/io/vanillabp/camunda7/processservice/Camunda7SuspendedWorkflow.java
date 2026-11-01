package io.vanillabp.camunda7.processservice;

import java.time.Duration;
import java.time.Instant;

import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.spi.process.WorkflowSuspendedException;

/**
 * What follows when a task belongs to a suspended workflow.
 * <p>
 * The engine refuses every answer to a task of a suspended process instance. It throws
 * {@code SuspendedEntityInteractionException} from <code>complete</code>, from a BPMN error and
 * from the signal of a parked execution. That type extends {@code BadUserRequestException},
 * which this adapter otherwise reports as a failure repeating cannot fix. A suspension is not
 * such a failure: the same answer goes through once somebody resumes the workflow.
 * <p>
 * So the adapter asks before it acts. The execution and the task carry their suspension state,
 * and the queries this adapter runs anyway return it for free. Phase one throws
 * {@link WorkflowSuspendedException}, so the caller's transaction rolls back. Phase two asks the
 * outbox to wait. An exception of the engine which slips past the question, because somebody
 * suspended the workflow in between, is turned into the same wait. See
 * {@code DECISIONS.pending/2070.md}.
 */
final class Camunda7SuspendedWorkflow {

  private Camunda7SuspendedWorkflow() {
  }

  /**
   * How long an outbox entry waits before it asks a suspended workflow again. The adapter is not
   * told when the workflow is resumed. So this is the longest an answer is late after somebody
   * resumed it, and each wait costs one query.
   */
  static final Duration WAIT_WHILE_SUSPENDED = Duration.ofSeconds(30);

  /**
   * Whether the failure, or one of its causes, is the engine refusing to touch a suspended
   * entity. The type is matched by its simple name, so the same line serves the forks of the
   * engine, whose packages differ.
   *
   * @param failure What a command of the engine threw
   * @return Whether it was refused because of a suspension
   */
  static boolean isASuspension(
      final Throwable failure) {

    var candidate = failure;
    while (candidate != null) {
      if ("SuspendedEntityInteractionException".equals(candidate.getClass().getSimpleName())) {
        return true;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    return false;

  }

  /**
   * What phase one throws for a task of a suspended workflow. The caller's transaction rolls
   * back, and the task stays open.
   *
   * @param adapterId The adapter which holds the task
   * @param kindOfTask <code>task</code> or <code>user task</code>, for the message
   * @param taskId The task the caller named
   * @param operationDescription What was attempted, for the message
   * @return The exception to throw
   */
  static WorkflowSuspendedException refuseTheCall(
      final String adapterId,
      final String kindOfTask,
      final String taskId,
      final String operationDescription) {

    return new WorkflowSuspendedException(
        """
            The workflow of %s '%s' is suspended in the engine of adapter '%s'. The engine refuses \
            every answer to its tasks while it is suspended - aborting the transaction %s it! Make \
            the same call again after the workflow is resumed, for example in Cockpit."""
            .formatted(kindOfTask, taskId, adapterId, operationDescription));

  }

  /**
   * What phase two throws for a task of a suspended workflow. The outbox entry waits, and the
   * wait uses none of its attempts.
   * <p>
   * The wait lasts as long as the workflow is suspended. A suspension is ended by a person, not
   * by time, so there is no moment after which waiting stops making sense. Each answer therefore
   * names a deadline behind its own window. The outbox blocks the entry only once an answer is
   * late past it, and <code>vanillabp.outbox.wait-for-visibility-at-most</code> does not apply. A
   * workflow which is deleted while it is suspended ends the wait as well: the next attempt finds
   * the task gone.
   *
   * @param adapterId The adapter which holds the task
   * @param kindOfTask <code>task</code> or <code>user task</code>, for the message
   * @param taskId The task of the entry
   * @param operationDescription What waits, for the message
   * @return The failure to throw
   */
  static PhaseTwoRetryLater waitInTheOutbox(
      final String adapterId,
      final String kindOfTask,
      final String taskId,
      final String operationDescription) {

    return new PhaseTwoRetryLater(
        """
            Camunda7[%s]: the workflow of %s '%s' is suspended, and the engine refuses the %s. The \
            outbox entry asks again in %s, for as long as the workflow is suspended, and uses none of \
            its attempts. Resume the workflow to let the %s through, or delete the workflow to end \
            the wait."""
            .formatted(
                adapterId,
                kindOfTask,
                taskId,
                operationDescription,
                WAIT_WHILE_SUSPENDED,
                operationDescription), WAIT_WHILE_SUSPENDED, Instant
                    .now()
                    .plus(WAIT_WHILE_SUSPENDED.multipliedBy(2)));

  }

}
