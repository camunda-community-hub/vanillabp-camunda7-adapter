package io.vanillabp.camunda7.wiring;

import java.util.Collection;
import java.util.List;

import org.camunda.bpm.model.bpmn.BpmnModelInstance;

import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * A reading of held models which finds nothing in any of them. It is for the tests about
 * the catalog itself. There the question is which versions the catalog reports and how often
 * it asks the engine, not what a model contains.
 * <p>
 * It is a reading and not none. A catalog without a reading answers "cannot say" and would
 * never read a model at all.
 */
public class HeldModelsAnsweringNothing implements Camunda7ProcessVersions.HeldModelReading {

  @Override
  public Collection<BpmnTaskSpec> tasksOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final BpmnModelInstance model) {

    return List.of();

  }

  @Override
  public Collection<String> concurrentTokenElementsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final BpmnModelInstance model) {

    return List.of();

  }

  @Override
  public Collection<io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport.ModelIdentifier> identifiersOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final BpmnModelInstance model) {

    return List.of();

  }

  @Override
  public Collection<BpmsInitiatedStartSpec> startEventsOf(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version,
      final BpmnModelInstance model) {

    return List.of();

  }

}
