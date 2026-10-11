package io.vanillabp.camunda7.sync;

/**
 * The serialization format in which Camunda 7 stores a shared value it has no variable type
 * for. Such a value is a nested value, or a number the engine cannot store as itself. The
 * format is resolved per workflow, with a fallback to the workflow module and to the
 * adapter:
 *
 * <pre>
 * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.adapters.&lt;id&gt;.serialization-format
 * vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.serialization-format
 * vanillabp.adapters.&lt;id&gt;.serialization-format
 * </pre>
 *
 * The value is a Camunda serialization data format, e.g.
 * <code>application/json</code> for the SPIN JSON dataformat or
 * <code>application/xstream</code> for
 * <a href="https://github.com/RasPelikan/camunda-xstream">camunda-xstream</a>. The engine needs the
 * matching dataformat on its classpath. That is a dependency of the application. The
 * adapter-level value is also applied to the engine's <code>defaultSerializationFormat</code>.
 * So an application configures the format once and nowhere else.
 * <p>
 * Why it matters: a nested value stored in a readable format keeps DOT-NOTATED
 * expressions working (<code>${order.customer.name}</code>). The engine deserializes the
 * variable before EL navigates it. Without any format, the engine falls back to Java
 * serialization. Cockpit then shows a blob, and the engine's database depends on the
 * application's class versions. So the adapter says so, once, when it writes such a value.
 * <p>
 * A format can lose something too. It decides what a value reads as. JSON has one number
 * type, so a decimal of <code>120.50</code> comes back as <code>120.5</code>. The deployment
 * measures that through the engine's own serializer. It warns about every attribute the
 * models read whose value would not come back as it went in (see
 * {@link Camunda7SerializationRoundTrip}).
 * <p>
 * The platform integrations implement this, because each of them binds its own
 * configuration. {@link #firstConfigured(String...)} does the resolution across the three
 * levels.
 * <p>
 * Decision 9 in the repository's DECISIONS.md says why such a value is written in the
 * engine's own format and not as a JSON string.
 */
public interface Camunda7SerializationFormats {

  /**
   * The format for the values of the given workflow which the engine has no type for.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The format or <code>null</code> where none is configured
   */
  String formatFor(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * The first value which is configured, most specific first. Every platform implementation
   * needs this resolution, and none should write it again.
   *
   * @param candidates The candidates, most specific first
   * @return The first non-blank value or <code>null</code>
   */
  static String firstConfigured(
      final String... candidates) {

    if (candidates == null) {
      return null;
    }
    for (final var candidate : candidates) {
      if ((candidate != null) && !candidate.isBlank()) {
        return candidate;
      }
    }
    return null;

  }

}
