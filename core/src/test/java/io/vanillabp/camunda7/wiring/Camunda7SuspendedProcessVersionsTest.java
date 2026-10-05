package io.vanillabp.camunda7.wiring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a suspended process definition is worth to this adapter: a version like every
 * other one, unless the emergency exit is taken.
 * <p>
 * Suspending is reversible, so the workflows on a suspended version are still there and
 * the {@code @WorkflowTask} method they miss is still missing - which is why the switch
 * has to be set deliberately and why every start says loudly that it was.
 */
@ExtendWith(SuppressOutputExtension.class)
public class Camunda7SuspendedProcessVersionsTest {

  private static final String ADAPTER = "c7";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private RepositoryService repositoryService;

  private ProcessDefinition definition(
      final int version,
      final boolean suspended) {

    final var definition = Mockito.mock(ProcessDefinition.class);
    Mockito
        .lenient()
        .when(definition.getId())
        .thenReturn("%s:%d:key".formatted(PROCESS, version));
    Mockito
        .lenient()
        .when(definition.getVersion())
        .thenReturn(version);
    Mockito
        .lenient()
        .when(definition.isSuspended())
        .thenReturn(suspended);
    return definition;

  }

  @BeforeEach
  public void setUp() {

    // built before the stubbing below: a mock created inside thenReturn() would be
    // stubbed while the outer stubbing is still open, which Mockito refuses
    final var definitions = List.of(definition(1, true), definition(2, false));

    final var definitionQuery = Mockito.mock(ProcessDefinitionQuery.class, Mockito.RETURNS_SELF);
    Mockito
        .lenient()
        .when(definitionQuery.list())
        .thenReturn(definitions);

    repositoryService = Mockito.mock(RepositoryService.class);
    Mockito
        .lenient()
        .when(repositoryService.createProcessDefinitionQuery())
        .thenReturn(definitionQuery);

  }

  /**
   * The switch is a system property of the JVM, so a case which sets it takes it back -
   * otherwise it colours every test running afterwards.
   */
  @AfterEach
  public void takeTheSwitchBackOut() {

    System.clearProperty(SuspendedProcessDefinitions.IGNORE_PROPERTY);

  }

  /**
   * The versions the startup check would work on. The catalog is built per case, because
   * it asks the engine once and then answers from what it learned.
   */
  private List<String> versionsReportedByTheCatalog() {

    final var versions = new Camunda7ProcessVersions(
        ADAPTER, repositoryService, (
            workflowModuleId,
            bpmnProcessId) -> bpmnProcessId, workflowModuleId -> null, new HeldModelsAnsweringNothing());
    return versions
        .deployedVersionsOf(MODULE, PROCESS)
        .stream()
        .map(DeployedProcessVersion::version)
        .toList();

  }

  /**
   * What ONE action wrote - the captured output accumulates over the whole class, so
   * every case looks at its own tail of it.
   */
  private static String whatIsLoggedBy(
      final CapturedOutput output,
      final Runnable action) {

    final var before = output.getAll().length();
    action.run();
    return output.getAll().substring(before);

  }

  @Test
  @DisplayName("A suspended version counts like every other one")
  public void aSuspendedVersionIsReported() {

    assertEquals(
        List.of("1", "2"),
        versionsReportedByTheCatalog(),
        "the suspended version 1 is part of what the startup check works on");

  }

  @Test
  @DisplayName("The switch takes the suspended version out and names it")
  public void theSwitchTakesTheSuspendedVersionOut(
      final CapturedOutput output) {

    System.setProperty(SuspendedProcessDefinitions.IGNORE_PROPERTY, "true");

    final var before = output.getAll().length();
    final var reported = versionsReportedByTheCatalog();
    final var logged = output.getAll().substring(before);

    assertEquals(List.of("2"), reported, "the suspended version 1 is gone");
    assertTrue(
        logged.contains("version(s) 1 of BPMN process '%s'".formatted(PROCESS)),
        "the version left out is named, but was: "
            + logged);
    assertTrue(
        logged.contains("workflow module '%s'".formatted(MODULE)),
        "and where it belongs to, but was: "
            + logged);

  }

  @Test
  @DisplayName("'TRUE' is the same answer as 'true'")
  public void theValueIsReadWithoutCase() {

    System.setProperty(SuspendedProcessDefinitions.IGNORE_PROPERTY, "TRUE");

    assertEquals(
        List.of("2"),
        versionsReportedByTheCatalog(),
        "upper and lower case do not separate two answers");

  }

  @Test
  @DisplayName("Every start says that the switch is set, and what it costs")
  public void theStartSaysThatTheSwitchIsSet(
      final CapturedOutput output) {

    System.setProperty(SuspendedProcessDefinitions.IGNORE_PROPERTY, "true");

    final var logged = whatIsLoggedBy(output, () -> SuspendedProcessDefinitions.reportIfTheSwitchIsSet(ADAPTER));

    assertTrue(
        logged.contains(SuspendedProcessDefinitions.IGNORE_PROPERTY),
        "the way it was set is named, but was: "
            + logged);
    assertTrue(logged.contains("incident"), "what it costs is named, but was: "
        + logged);
    assertTrue(
        logged.contains("emergency exit for one start"),
        "and that it is not a setting, but was: "
            + logged);

  }

  @Test
  @DisplayName("A value which is not 'true' changes nothing and says so")
  public void aMistypedValueIsReported(
      final CapturedOutput output) {

    System.setProperty(SuspendedProcessDefinitions.IGNORE_PROPERTY, "yes");

    final var logged = whatIsLoggedBy(output, () -> SuspendedProcessDefinitions.reportIfTheSwitchIsSet(ADAPTER));

    assertEquals(
        List.of("1", "2"),
        versionsReportedByTheCatalog(),
        "the suspended version is checked like every other one");
    assertTrue(logged.contains("carries the value 'yes'"), "the value found is named, but was: "
        + logged);
    assertTrue(logged.contains("only the value 'true'"), "and what would have worked, but was: "
        + logged);

  }

  @Test
  @DisplayName("Without the switch nothing is said about it at all")
  public void anUnsetSwitchIsNotWorthALine(
      final CapturedOutput output) {

    final var logged = whatIsLoggedBy(output, () -> SuspendedProcessDefinitions.reportIfTheSwitchIsSet(ADAPTER));

    assertTrue(
        !logged.contains(SuspendedProcessDefinitions.IGNORE_PROPERTY),
        "a switch nobody set is not news, but was: "
            + logged);

  }

}
