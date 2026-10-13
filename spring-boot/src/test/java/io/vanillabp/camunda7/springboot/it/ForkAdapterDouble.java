package io.vanillabp.camunda7.springboot.it;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.AddressedSystem;

/**
 * Stands in for an adapter of a Camunda 7 fork, such as <code>cibseven</code>, which does not
 * exist yet. It only answers what the startup check across adapter types asks: its id, its
 * type and the tables it works on. A fork adapter is generated from this adapter's code, so
 * the test builds those tables with the same {@code Camunda7InstanceIdentity}. Everything a
 * deployment would call does nothing, because no workflow module names this id.
 */
public class ForkAdapterDouble implements AdapterDeploymentService<Object, Object> {

  private final String adapterId;

  private final String adapterType;

  private final AddressedSystem tables;

  public ForkAdapterDouble(
      final String adapterId,
      final String adapterType,
      final AddressedSystem tables) {

    this.adapterId = adapterId;
    this.adapterType = adapterType;
    this.tables = tables;

  }

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  @Override
  public String getAdapterType() {

    return adapterType;

  }

  @Override
  public Optional<AddressedSystem> getAddressedSystem() {

    return Optional.of(tables);

  }

  @Override
  public Class<Object> getModelType() {

    return Object.class;

  }

  @Override
  public Class<Object> getProcessContextType() {

    return Object.class;

  }

  @Override
  public List<Map.Entry<String, Object>> readBpmn(
      final String workflowModuleId,
      final String filename,
      final InputStream bpmn,
      final boolean isVanillaBpBpmn) {

    return List.of();

  }

  @Override
  public Object prepareBpmn(
      final String workflowModuleId,
      final Object existingContext,
      final String filename,
      final String bpmnProcessId,
      final Object model) {

    return existingContext;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final Object model,
      final Object context) {
    // nothing is wired: no workflow module names this id
  }

  @Override
  public void deployResources(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {
    // nothing is deployed: no workflow module names this id
  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {
    // nothing runs: no workflow module names this id
  }

}
