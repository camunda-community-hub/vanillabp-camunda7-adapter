package io.vanillabp.camunda7.quarkus.test.versions;

import java.util.List;

import io.vanillabp.camunda7.quarkus.runtime.Camunda7QuarkusEngineRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * What the old-process-versions test can see of the running application. A prod-mode test
 * runs the application in a forked JVM, so nothing of it can be injected into the test and
 * everything travels through these endpoints.
 */
@Path("/versions")
@Produces(MediaType.APPLICATION_JSON)
@ApplicationScoped
public class C7VersionsController {

  @Inject
  C7VersionsWorkflowService workflowService;

  @Inject
  EntityManager entityManager;

  @Inject
  Camunda7QuarkusEngineRegistry engineRegistry;

  /**
   * Deploys the old model of the process the way a node still running the older release
   * deploys it while this application runs: as a deployment of the workflow module, into the
   * tenant the module's name-clash-avoidance ('by-adapter') gives it. The engine numbers it
   * after the new model this application deployed while it booted.
   */
  @POST
  @Path("/old-model")
  public void deployTheOldModel() {

    engineRegistry
        .engineFor("c7")
        .getProcessEngine()
        .getRepositoryService()
        .createDeployment()
        // deployment name, source and file name are the ones this application's adapter
        // deploys the new model under. The engine compares a deployment with the latest file
        // of the same name, so the next boot finds the old model there and deploys the new
        // one again, as version 3, instead of taking it for a duplicate
        .name("c7-versions")
        .source("camunda7:c7")
        .tenantId("c7-versions")
        .addInputStream(
            "old-process-versions.bpmn",
            getClass().getResourceAsStream("/c7-versions/v1/old-process-versions.bpmn"))
        .deploy();

  }

  /**
   * Starts a workflow on the newest version the engine holds of the process.
   *
   * @return The aggregate's id
   */
  @POST
  @Path("/workflows")
  @Produces(MediaType.TEXT_PLAIN)
  @Transactional
  public String startWorkflow() {

    return String.valueOf(workflowService
        .startWorkflow()
        .getId());

  }

  /**
   * @return One "id|servedBy|openTaskId" per aggregate - an aggregate with an open task id
   *         is a workflow parked in the task which the new model no longer has
   */
  @GET
  @Path("/aggregates")
  @Transactional
  public List<String> aggregates() {

    return entityManager
        .createQuery("select a from C7VersionsAggregate a", C7VersionsAggregate.class)
        .getResultList()
        .stream()
        .map(aggregate -> "%s|%s|%s"
            .formatted(aggregate.getId(), aggregate.getServedBy(), aggregate.getOpenTaskId()))
        .toList();

  }

}
