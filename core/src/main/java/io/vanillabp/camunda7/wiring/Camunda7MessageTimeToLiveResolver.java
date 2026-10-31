package io.vanillabp.camunda7.wiring;

import java.time.Duration;

/**
 * Resolves how long a correlated message is kept while nothing waits for it yet. The
 * platform modules implement it on top of the configuration overlay of the adapter. The
 * most specific value wins, across four levels (message &gt; workflow &gt; workflow-module
 * &gt; adapter):
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.message-time-to-live
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.message-time-to-live
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.message-time-to-live
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.messages.&lt;messageName&gt;.adapters.&lt;id&gt;.message-time-to-live
 * </pre>
 *
 * The key and its meaning are the ones of the Camunda 8 adapter. A Camunda 8 cluster keeps a
 * published message for its time-to-live, so a message may be correlated before the
 * workflow reaches the point where it waits. An embedded engine has no such buffer. So this
 * adapter asks the outbox to dispatch the correlation again later, until a subscription
 * waits or the time-to-live is over.
 * <p>
 * Nothing configured means the time-to-live is zero, and so does a configured zero. Then a
 * correlation which nothing waits for is refused in phase one, where the application made
 * the call. That is what version 1 did.
 * <p>
 * The time-to-live is not a window which removes duplicates, unlike on Camunda 8. The
 * outbox dispatches every entry once, so nothing has to be deduplicated here.
 */
@FunctionalInterface
public interface Camunda7MessageTimeToLiveResolver {

  /**
   * How long a correlation of this message waits for a subscription.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID of the process whose model waits for the
   *          message
   * @param messageName The BPMN message name, as the application wrote it (without any
   *          prefix a name-clash-avoidance mode adds)
   * @return The most specific configured time-to-live, or <code>null</code> where no level
   *         configures one
   */
  Duration messageTimeToLiveFor(
      String workflowModuleId,
      String bpmnProcessId,
      String messageName);


  /**
   * Ends the boot where a <code>message-time-to-live</code> is below zero. Zero is allowed. It
   * switches the waiting off for a scope below one which switched it on.
   *
   * @param adapterId The adapter id the values belong to
   * @param negativeKeys The keys with a value below zero, each written as
   *          <code>key=value</code>
   * @throws IllegalStateException Naming the keys and the way out
   */
  static void refuseNegativeValues(
      final String adapterId,
      final java.util.List<String> negativeKeys) {

    if (negativeKeys.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            Adapter '%s' found a message time-to-live below zero: %s! It says how long a \
            correlated message waits for the workflow to reach the point where it waits for \
            it. Write a duration like '10m', or '0s' to refuse a correlation nothing waits for \
            where the application made the call."""
            .formatted(adapterId, String.join(", ", negativeKeys)));

  }

}
