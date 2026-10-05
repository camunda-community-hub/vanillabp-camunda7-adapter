package io.vanillabp.camunda7.deployment;

import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.repository.ProcessDefinition;
import org.camunda.bpm.engine.repository.ProcessDefinitionQuery;
import org.camunda.bpm.model.bpmn.Bpmn;
import org.mockito.Mockito;

/**
 * An engine which holds the given versions of one BPMN process, each with its model -
 * what a check reading models an earlier generation of the application deployed sees.
 */
public final class AnEngineHolding {

  private AnEngineHolding() {
    // static helper
  }

  /**
   * @param bpmnProcessId The process id the definitions carry
   * @param modelsByVersion The BPMN of every version the engine holds, by version
   * @return A repository service answering with those definitions and models
   */
  public static RepositoryService theseModels(
      final String bpmnProcessId,
      final Map<String, String> modelsByVersion) {

    // every mock is built BEFORE the first stubbing: creating one between when() and
    // thenReturn() leaves Mockito with a stubbing it considers unfinished
    final var definitions = definitions(bpmnProcessId, modelsByVersion.keySet());
    final var repositoryService = mock(RepositoryService.class);
    final var query = mock(ProcessDefinitionQuery.class, RETURNS_SELF);
    // a version the list did not hold is looked up on its own - these engines hold what
    // they were built with, so the first definition is the honest answer, and an engine
    // holding nothing answers nothing
    final var singleResult = definitions.isEmpty()
        ? null
        : definitions.getFirst();
    Mockito.lenient().when(query.list()).thenReturn(definitions);
    Mockito.lenient().when(query.singleResult()).thenReturn(singleResult);
    when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
    modelsByVersion
        .forEach((
            version,
            model) -> Mockito
                .lenient()
                .when(repositoryService.getBpmnModelInstance("definition-"
                    + version))
                .thenReturn(
                    Bpmn.readModelFromStream(new ByteArrayInputStream(model.getBytes(StandardCharsets.UTF_8)))));
    return repositoryService;

  }

  /**
   * The definitions as the engine's query reports them: oldest version first, the way
   * the version catalog asks for them.
   */
  private static List<ProcessDefinition> definitions(
      final String bpmnProcessId,
      final java.util.Collection<String> versions) {

    return versions
        .stream()
        .sorted(Comparator.comparingInt(Integer::parseInt))
        .map(version -> {
          final var definition = mock(ProcessDefinition.class);
          Mockito.lenient().when(definition.getId()).thenReturn("definition-"
              + version);
          Mockito.lenient().when(definition.getVersion()).thenReturn(Integer.valueOf(version));
          Mockito.lenient().when(definition.getKey()).thenReturn(bpmnProcessId);
          return definition;
        })
        .map(ProcessDefinition.class::cast)
        .toList();

  }

}
