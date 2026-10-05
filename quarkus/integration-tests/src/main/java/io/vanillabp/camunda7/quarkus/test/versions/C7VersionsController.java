package io.vanillabp.camunda7.quarkus.test.versions;

import java.util.List;

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

  /**
   * Starts a workflow on the generation of the model this boot deployed.
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
   *         is a workflow parked in the task which version 2 no longer has
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
