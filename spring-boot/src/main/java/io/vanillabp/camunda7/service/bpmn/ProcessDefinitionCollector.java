package io.vanillabp.camunda7.service.bpmn;

import io.vanillabp.spi.process.ProcessDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.camunda.bpm.engine.ProcessEngine;
import org.camunda.bpm.engine.RepositoryService;
import org.camunda.bpm.engine.history.HistoricProcessInstance;
import org.camunda.bpm.model.bpmn.BpmnModelInstance;
import org.camunda.bpm.model.bpmn.instance.CallActivity;

public class ProcessDefinitionCollector {

    private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";

    private final RepositoryService repositoryService;

    public ProcessDefinitionCollector(ProcessEngine processEngine) {
        this.repositoryService = processEngine.getRepositoryService();
    }

    public List<ProcessDefinition> collectAllDefinitions(
            HistoricProcessInstance processInstance,
            String tenantId) {

        var parentDef = repositoryService
                .getProcessDefinition(processInstance.getProcessDefinitionId());

        // processDefinitionId → gesammelte elementIds
        Map<String, List<String>> defToElements = new LinkedHashMap<>();
        defToElements.put(parentDef.getId(), null);

        BpmnModelInstance model = repositoryService
                .getBpmnModelInstance(parentDef.getId());

        for (CallActivity ca : model.getModelElementsByType(CallActivity.class)) {
            String calledElement = ca.getCalledElement();
            if (calledElement == null || calledElement.isBlank()) {
                continue;
            }

            var resolvedDef = resolveCalledDefinition(ca, parentDef, tenantId);
            if (resolvedDef != null) {
                addElement(defToElements, resolvedDef.getId(), ca.getId());
            }
        }

        List<ProcessDefinition> result = new ArrayList<>();
        for (var entry : defToElements.entrySet()) {
            var def = repositoryService.getProcessDefinition(entry.getKey());
            result.add(toProcessDefinition(def, entry.getValue()));
        }
        return result;
    }

    private void addElement(Map<String, List<String>> defToElements,
                            String processDefinitionId,
                            String elementId) {
        defToElements.merge(processDefinitionId,
                new ArrayList<>(List.of(elementId)),
                (existing, incoming) -> {
                    if (existing == null) {
                        return null;
                    }
                    if (!existing.contains(elementId)) {
                        existing.add(elementId);
                    }
                    return existing;
                });
    }

    private org.camunda.bpm.engine.repository.ProcessDefinition
    resolveCalledDefinition(
            CallActivity ca,
            org.camunda.bpm.engine.repository.ProcessDefinition parentDef,
            String tenantId) {

        String calledElement = ca.getCalledElement();

        String binding = ca.getAttributeValueNs(CAMUNDA_NS,
                "calledElementBinding");
        String versionAttr = ca.getAttributeValueNs(CAMUNDA_NS,
                "calledElementVersion");
        String versionTagAttr = ca.getAttributeValueNs(CAMUNDA_NS,
                "calledElementVersionTag");

        if ("deployment".equals(binding)) {
            return createProcessDefinitionQuery(tenantId)
                    .processDefinitionKey(calledElement)
                    .deploymentId(parentDef.getDeploymentId())
                    .singleResult();

        } else if ("version".equals(binding) && versionAttr != null) {
            int version = Integer.parseInt(versionAttr);
            return createProcessDefinitionQuery(tenantId)
                    .processDefinitionKey(calledElement)
                    .processDefinitionVersion(version)
                    .singleResult();

        } else if ("versionTag".equals(binding) && versionTagAttr != null) {
            return createProcessDefinitionQuery(tenantId)
                    .processDefinitionKey(calledElement)
                    .versionTag(versionTagAttr)
                    .orderByProcessDefinitionVersion()
                    .desc()
                    .list()
                    .stream()
                    .findFirst()
                    .orElse(null);

        } else {
            return createProcessDefinitionQuery(tenantId)
                    .processDefinitionKey(calledElement)
                    .latestVersion()
                    .singleResult();
        }
    }

    private org.camunda.bpm.engine.repository.ProcessDefinitionQuery
    createProcessDefinitionQuery(String tenantId) {

        var query = repositoryService.createProcessDefinitionQuery();
        if (tenantId != null) {
            query.tenantIdIn(tenantId);
        }
        return query;
    }

    private ProcessDefinition toProcessDefinition(
            org.camunda.bpm.engine.repository.ProcessDefinition def,
            List<String> usedByElements) {

        return new ProcessDefinition(
                def.getId(),
                def.getKey(),
                def.getVersionTag() != null
                        ? "%s:%d".formatted(def.getVersionTag(), def.getVersion())
                        : Integer.toString(def.getVersion()),
                usedByElements
        );
    }
}
