package io.vanillabp.camunda7.wiring;

/**
 * Tells whether this application serves the listeners somebody modelled. The platform modules
 * implement it on top of the adapter's configuration overlay. There are THREE levels (workflow
 * &gt; workflow-module &gt; adapter), and the most specific value wins:
 *
 * <pre>
 * vanillabp.adapters.&lt;id&gt;.allow-listeners
 * vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;.allow-listeners
 * vanillabp.workflow-modules.&lt;m&gt;.workflows.&lt;w&gt;.adapters.&lt;id&gt;.allow-listeners
 * </pre>
 *
 * {@link io.vanillabp.camunda7.sync.Camunda7SerializationFormats} did this first: a key of this
 * adapter, resolved per level through an interface the platforms implement. The Camunda 8
 * adapter reads the same three levels for the same key. Both keys say what a model may contain.
 * If they reached different levels on the two adapters, that would be worse.
 *
 * <h2>Why there is no task level</h2>
 *
 * That level is keyed by a task DEFINITION. But this key decides whether a modelled listener
 * becomes a task at all. So when the key is read, there is no task definition yet to key a level
 * by. If somebody sets a value at task level anyway, one warning tells them the three levels
 * which work.
 *
 * <h2>Why more specific may switch it OFF</h2>
 *
 * The most specific configured value wins in both directions. So a workflow module which serves
 * its listeners can have one workflow which does not.
 */
@FunctionalInterface
public interface Camunda7AllowListenersResolver {

  /**
   * What applies where no level configures anything. The listeners somebody modelled are not
   * served, and a model which has one does not boot. VanillaBP 1 served them without saying so.
   * Serving them has a cost, and that is why it is not the default here.
   */
  boolean DEFAULT_ALLOW_LISTENERS = false;

  /**
   * The answer together with the key it came from. The startup report has to name the line a
   * reader can find in their own configuration.
   *
   * @param allowed Whether the listeners of the model are served by
   *          <code>@WorkflowTask</code> methods
   * @param propertyKey The fully spelled key which decided it, or <code>null</code> where
   *          nothing is configured and the default applies
   */
  record Setting(
                 boolean allowed,
                 String propertyKey) {

    /**
     * The answer of an application which configures nothing.
     */
    public static final Setting NOTHING_CONFIGURED = new Setting(DEFAULT_ALLOW_LISTENERS, null);

  }

  /**
   * Answers for one workflow whether the application's methods serve the execution listeners
   * somebody modelled. The answer also says where it was configured. A message about an ignored
   * listener has to name the level somebody can change.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The most specific configured setting, never <code>null</code>
   */
  Setting allowListenersFor(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * Asks a resolver which may not be there. Tests build the deployment service without one. And a
   * resolver may answer nothing.
   *
   * @param resolver The resolver or <code>null</code>
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The resolved setting, never <code>null</code>
   */
  static Setting resolve(
      final Camunda7AllowListenersResolver resolver,
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (resolver == null) {
      return Setting.NOTHING_CONFIGURED;
    }
    final var resolved = resolver.allowListenersFor(workflowModuleId, bpmnProcessId);
    return resolved == null
        ? Setting.NOTHING_CONFIGURED
        : resolved;

  }

}
