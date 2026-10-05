package io.vanillabp.camunda7.wiring;

import java.util.List;

import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The emergency exit which takes suspended process definitions out of the startup check
 * for old process versions: the two ways of setting it, everything this adapter says
 * about it, and what it does.
 * <p>
 * Suspending a process definition is not deleting it, which is why a suspended version
 * counts like every other one as long as this switch is not set, why the switch is a
 * system property rather than configuration and why its name carries no adapter part -
 * see decision 12 in the repository's DECISIONS.md. What it is for is the one situation
 * that decision cannot cover: an application which has to run NOW and must not hang on
 * versions nobody is able to clean up at this minute.
 * <p>
 * Keep the reads where they are. Quarkus likes to initialize classes while it builds, so
 * a value remembered in a field would be the answer the BUILD saw rather than the one the
 * operator sets, and the switch would be silently without effect in a native image -
 * which is the worst thing an emergency exit can do.
 */
public final class SuspendedProcessDefinitions {

  private static final Logger log = LoggerFactory.getLogger(SuspendedProcessDefinitions.class);

  /**
   * The system property taking suspended definitions out of the startup check.
   */
  public static final String IGNORE_PROPERTY = "vanillabp.ignore-suspended-process-definitions";

  /**
   * The environment variable doing the same, for a native image and for a container
   * which sets no command line.
   */
  public static final String IGNORE_ENVIRONMENT_VARIABLE = "VANILLABP_IGNORE_SUSPENDED_PROCESS_DEFINITIONS";

  private SuspendedProcessDefinitions() {

    // the switch keeps no state of its own

  }

  /**
   * How the switch was set: the wording naming the way it came in, and the value found
   * there.
   */
  private record Switch(String setBy, String value) {

    private boolean meansYes() {

      return "true".equalsIgnoreCase(value);

    }

  }

  /**
   * The system property wins where both are set, so a command line can overrule an
   * environment nobody wants to touch.
   */
  private static Switch howTheSwitchIsSet() {

    // read here rather than kept in a field: a value a Quarkus build read while
    // initializing this class would be the build's answer, not the operator's
    final var fromProperty = System.getProperty(IGNORE_PROPERTY);
    if (fromProperty != null) {
      return new Switch("system property '%s'".formatted(IGNORE_PROPERTY), fromProperty);
    }
    final var fromEnvironment = System.getenv(IGNORE_ENVIRONMENT_VARIABLE);
    if (fromEnvironment != null) {
      return new Switch("environment variable '%s'".formatted(IGNORE_ENVIRONMENT_VARIABLE), fromEnvironment);
    }
    return null;

  }

  /**
   * Says on every start that the switch is set, and what it costs. Nothing is remembered
   * about having said it: the line is supposed to be in the way until somebody takes the
   * switch out again.
   *
   * @param adapterId The adapter ID
   */
  public static void reportIfTheSwitchIsSet(
      final String adapterId) {

    final var howItIsSet = howTheSwitchIsSet();
    if (howItIsSet == null) {
      return;
    }
    if (!howItIsSet.meansYes()) {
      // a way out nobody can spell is no way out: whoever typed this in an emergency
      // reads why nothing happened instead of guessing
      log.warn(
          """
              Camunda7[{}]: the {} carries the value '{}', and only the value 'true' switches \
              anything off (upper and lower case do not matter). Suspended process definitions are \
              checked like every other version, which is what happens without the switch as \
              well.""",
          adapterId,
          howItIsSet.setBy(),
          howItIsSet.value());
      return;
    }
    log.warn(
        """
            Camunda7[{}]: the {} is set to 'true', so the startup check for old process versions \
            leaves every SUSPENDED process definition unchecked. Suspending a definition does not \
            end the workflows on it and does not supply a @WorkflowTask method this application is \
            missing for them: reactivate such a definition and its workflows walk into a task \
            nobody serves, which you learn about as an incident on a live workflow. This is an \
            emergency exit for one start and not a setting - remove it again once this application \
            is up. Every version actually left out gets a line of its own.""",
        adapterId,
        howItIsSet.setBy());

  }

  /**
   * The definitions of one BPMN process the startup check is to work on, and the report
   * about the ones it will not see.
   * <p>
   * The versions left out are named rather than dropped quietly, so the information is
   * only downgraded from "ends the start" to "stands in the log": whoever cleans up later
   * reads the list here instead of having to take the switch out once more.
   * <p>
   * They are left out of the version CATALOG, not only out of the check, so a
   * <code>camunda:versionTag</code> carried by a suspended version stops resolving while
   * the switch is set. That is the price of the switch being one line rather than a
   * second way through the catalog, and it lasts as long as the emergency does.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param definitions Every definition of that process the engine holds, oldest first
   * @return The definitions which count, oldest first
   */
  public static List<ProcessDefinition> definitionsWhichStillCount(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final List<ProcessDefinition> definitions) {

    final var howItIsSet = howTheSwitchIsSet();
    if ((howItIsSet == null) || !howItIsSet.meansYes()) {
      return definitions;
    }
    final var leftOut = definitions
        .stream()
        .filter(ProcessDefinition::isSuspended)
        .map(definition -> String.valueOf(definition.getVersion()))
        .toList();
    if (leftOut.isEmpty()) {
      return definitions;
    }
    log.warn(
        """
            Camunda7[{}]: version(s) {} of BPMN process '{}' (workflow module '{}') are suspended \
            and are therefore left out of the startup check, because the {} is set to 'true'. \
            Whatever those versions ask of this application stays unreported until the switch is \
            gone.""",
        adapterId,
        String.join(", ", leftOut),
        bpmnProcessId,
        workflowModuleId,
        howItIsSet.setBy());
    return definitions
        .stream()
        .filter(definition -> !definition.isSuspended())
        .toList();

  }

}
