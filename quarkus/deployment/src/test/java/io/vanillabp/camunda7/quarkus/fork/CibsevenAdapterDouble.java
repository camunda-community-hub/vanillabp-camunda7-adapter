package io.vanillabp.camunda7.quarkus.fork;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.quarkus.arc.Unremovable;
import io.vanillabp.camunda7.engine.Camunda7InstanceIdentity;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.AddressedSystem;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Stands in for the adapter of the Camunda 7 fork CIB seven, which does not exist yet. It
 * only answers what the startup check across adapter types asks: its id, its type and the
 * tables it works on. A fork adapter is generated from this adapter's code, so the double
 * builds its tables with the same {@link Camunda7InstanceIdentity}: the application's
 * default datasource, no prefix. Everything a deployment would call does nothing, because
 * no workflow module names this id.
 */
@ApplicationScoped
// nothing injects this bean by its own type; the platform collects it as an adapter
@Unremovable
public class CibsevenAdapterDouble implements AdapterDeploymentService<Object, Object> {

  @Override
  public String getAdapterId() {

    return "cib";

  }

  @Override
  public String getAdapterType() {

    return "cibseven";

  }

  @Override
  public Optional<AddressedSystem> getAddressedSystem() {

    return Optional.of(new Camunda7InstanceIdentity(null, null).addressedSystem());

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
