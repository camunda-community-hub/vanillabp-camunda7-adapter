![VanillaBP](../readme/vanillabp-headline.png)
# Camunda 7 adapter for Spring Boot

Camunda 7 is a BPMN engine Java library which processes BPMN files deployed along with Java classes which implement the behavior of BPMN tasks. The engine uses a relational database to persist state and to store history processing data. Therefore proper transaction handling is crucial e.g. to control behavior in special situations like unexpected errors of connected components. Due to the nature of the library and it's persistence Camunda 7 is a good fit up to mid-range scaled use-cases.

This adapter is aware of all the details needed to keep in mind on using Camunda 7 and implements a lot of best practices based on a long years experience.

## Features

### Module aware deployment

To avoid interdependencies between the implementation of different use-cases packed into a single microservice the concept of [workflow modules](https://github.com/vanillabp/spring-boot-support#workflow-modules) is introduced. This adapter builds a Camunda deployment for each workflow module found in the classpath.

Since Camunda is not aware of workflow modules the Camunda tenant-id is used to store the relationship between BPMNs and DMNs and their workflow module. As a consequence Camunda's tenant-ids cannot be used to distinguish "real" tenants any more. To keep track easier, one might introduce user-groups for Camunda Cockpit which only shows resources of one workflow module.

Additionally, in a clustered environment during rolling deployment, to not start jobs by Camunda's job-executor originated by newer deployments add this setting in your application.yaml of the microservice' container:

```yaml
camunda:
   bpm:
      job-execution:
         deployment-aware: true
```

### SPI Binding validation

On starting the application BPMNs of all workflow modules will be wired to the SPI. This includes

1. BPMN files which are part of the current workflow module bundle (e.g. classpath:processes/*.bpmn)
1. BPMN files deployed as part of previous versions of the workflow module
   1. Older versions of BPMN files which are part of the current workflow module bundle
   1. BPMN files which are not part of the current workflow module bundle any more
   
This ensures that correct wiring of all process definitions according to the SPI is done.

### BPMN

#### Multi-instance

For Camunda 7 all the handling of multi-instance is done under the hood.

*Hint:* If you want to be prepared to upgrade to Camunda 8 then use collection-based multi-instance since Camunda 8 does not support cardinality-based multi-instance. To avoid troubles on deserializing complex elements in your collection we strongly recommend to only use collections which consist of primitive values (e.g. in the taxi ride sample the list of driver ids).

#### Message correlation IDs

On using receive tasks one can correlate an incoming message by it's name. This means that a particular workflow is allowed to have only one receive task active for this particular message name. Typically, this is not a problem. In case your model has more than one receive task active you have to define unique correlation IDs for each receive task of that message name to enable the BPMS to find the right receive task to correlate to. This might be necessary for multi-instance receive tasks or receive tasks within a multi-instance embedded sub-process.

In Camunda 7 the correlation ID can be set using a local process variable. Since the name of this process variable is not standardized but the adapter needs to know about it, the this implementation assumes the name of that variable according to this naming convention: `BPMN process ID` + `"-"` + `message name`. Setting a local variable can be done in the Camunda Modeler by defining an "input mapping" on the task.

For the taxi ride sample we have the BPMN process ID `TaxiRide` and e.g. the message name `RideOfferReceived`, so the local variable name is assumed as set to `TaxiRide-RideOfferReceived`. 

*Hint:* If you want to be prepared to upgrade to Camunda 8 then always set this correlation ID as a local variable since this is mandatory on using Camunda 8.

### Transaction behavior

Defining the right Camunda 7 transactional behavior can be difficult. We have seen a lot of developers struggling with all the possibilities Camunda 7 provides regarding transactions. The implementation uses one particular best-practice approach and applies this to every BPMN automatically: Every BPMN task/element is a separate transaction.

In the very beginnings of Camunda it was sold as a feature to not require one transaction for each BPMN task/element. Meanwhile computers are fast and cheap and avoiding data loss as well as building cheap software is more important than optimization of software. Therefore it is better to have separate transactions rather than dealing with tricky transactional behavior.

To support this, on deploying bundled BPMN files the Camunda feature "Async before" is set on every task automatically. The only exceptions are usertasks, tasks using implementation type "external" as well as natural wait states like receive tasks because all of them implement the desired behavior implicitly. Additionally, the "Async after" is set on each and every task to ensure tasks completed successfully are not rolled back any more.

If there is an exception in your business code and you have to roll back the transaction then Camunda's job of picking up the task has to be rolled back as well to ensure the retry-mechanism. Additionally, the `TaskException` is used for expected business errors handled by BPMN error boundary events which must not cause a rollback. To achieve both one should mark the service bean like this:

```java
@Service
@WorkflowService(workflowAggregateClass = Ride.class)
@Transactional(noRollbackFor = TaskException.class)
public class TaxiRide {
```

### Job-Executor

The Camunda job-executor is responsible for processing asynchronous continuation of BPMN tasks. It has a delay due to polling the database for jobs (see [Backoff Strategy](https://docs.camunda.org/manual/7.18/user-guide/process-engine/the-job-executor/#backoff-strategy)). If there is manual interaction to the process-engine (e.g. process started, message correlated or user-task completed) you want asynchronous tasks to be completed as soon as possible. For example, you want to give feedback in the UI of a "validation" task following a user-task. Therefore this adapter wakes up the Job-Exceutor to check for new jobs after a manual interaction's transaction is completed.

In cloud environments one typically wants to free resources in idle times to not waste money (at protect the environment :deciduous_tree:) since resources are charged by time of usage. Camunda's Job-Executor using a polling strategy to find new jobs which either  keeps the database in use or introduces huge delays of execution if max-delays are set to big values. This Camunda 7 VanillaBP adapter alters this behavior by keeping the Job-Executor sleep until the next timed job's due-date (e.g. timer-event). In conjunction with waking up the job-executor in case of manual interaction this helps to minimize database usage.

This feature is enabled by default and if not wanted needs to be disabled by this Spring property:

```yaml
camunda:
  bpm:
    job-execution:
      wakeup: false
```

However, if enabled you have to [set a DB index](https://docs.camunda.org/manual/7.6/user-guide/process-engine/the-job-executor/#the-job-order-of-job-acquisition) as hinted on starting your Spring Boot application with this feature enabled:

```
VanillaBP's job-executor is using jobExecutorPreferTimerJobs=true
and jobExecutorAcquireByDueDate=true. Please add DB-index according
to https://docs.camunda.org/manual/7.6/user-guide/process-engine/the-job-executor/#the-job-order-of-job-acquisition
```

*Hint:* This feature requires to enable Spring Boot's scheduled tasks [as described here](https://www.baeldung.com/spring-scheduled-tasks#enable-support-for-scheduling).
