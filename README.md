![Header](./readme/vanillabp-headline.png)

# VanillaBP adapter for Camunda 7

[![](https://img.shields.io/badge/Community%20Extension-An%20open%20source%20community%20maintained%20project-FF4700)](https://github.com/camunda-community-hub/community)
![Compatible with: Camunda Platform 7](https://img.shields.io/badge/Compatible%20with-Camunda%20Platform%207-26d07c)
[![](https://img.shields.io/badge/Lifecycle-Incubating-blue)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#incubating-)
[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

This is the [VanillaBP](https://www.vanillabp.io) Version 2 adapter for
[Camunda 7](https://camunda.com/), the embedded workflow engine.

If you want to **use** this adapter, read the
[Wiki](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki). The VanillaBP concepts it builds
on are explained in the [VanillaBP Wiki](https://github.com/vanillabp/adapter-platform-integration/wiki). This
`README.md` is for contributors.

> **Status: Version 2, in development.** These parts work: BPMN deployment, starting workflows
> (also the starts the engine does on its own), task processing (`@WorkflowTask`),
> completing and canceling asynchronous tasks, user tasks, message correlation, signals, the
> end-of-workflow notification, the aggregate sync (`@SyncWithBPMS`, `aggregateChanged`),
> the viewer/history API, process versions and the BPMS-election awareness probes. All of it
> runs on the embedded engine and in the caller's transaction. What this adapter does NOT
> deliver is listed under [Known deviations](#known-deviations).

## Documentation and supported platforms

This adapter runs on both platforms VanillaBP supports:

1. **Spring Boot**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fcamunda-community-hub.github.io%2Fvanillabp-camunda7-adapter%2Fspring-boot-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://camunda-community-hub.github.io/vanillabp-camunda7-adapter/spring-boot-report)
2. **Quarkus**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fcamunda-community-hub.github.io%2Fvanillabp-camunda7-adapter%2Fquarkus-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://camunda-community-hub.github.io/vanillabp-camunda7-adapter/quarkus-report)

Coverage is measured separately per platform. The tests of one platform never cover the code
of the other. Click a badge to open its report.

## Coordinates

The adapter is built on top of `adapter-platform-integration`. That is the platform-neutral
migration adapter plus the Spring Boot integration. The mechanisms this adapter plugs into are
drawn there, not here. Each picture sits in the section of `migration-adapter/README.md` which
describes the mechanism, and [`diagrams/README.md`](https://github.com/vanillabp/adapter-platform-integration/blob/main/diagrams)
lists them. In most pictures Camunda 7 is one branch next to Camunda 8 and the
Process-Engine-API. This repository cannot draw that comparison on its own.

```xml
<dependency>
  <groupId>org.camunda.community.vanillabp</groupId>
  <artifactId>camunda7-adapter-spring-boot</artifactId>
  <version>2.0.0-SNAPSHOT</version>
</dependency>
```

|        Module         |                Artifact                |                                                   Contents                                                   |
|-----------------------|----------------------------------------|--------------------------------------------------------------------------------------------------------------|
| `core`                | `camunda7-adapter`                     | Platform-neutral SPI implementations + engine wiring.                                                        |
| `spring-boot`         | `camunda7-adapter-spring-boot`         | Spring Boot auto-configuration registering the adapter.                                                      |
| `spring-boot-webapps` | `camunda7-adapter-spring-boot-webapps` | Optional: serves Camunda's Cockpit, Tasklist and Admin at `/camunda` against the engines this adapter built. |

## Configuration

The adapter is a VanillaBP adapter of type `camunda7`. To configure an adapter instance, give it
an id and set its `type` to `camunda7`:

```yaml
vanillabp:
  adapters:
    c7:
      type: camunda7
  prioritized-adapters:
    - c7
```

You may configure the same type under several ids, for example two Camunda 7 engines side by
side during a migration. **Each id gets its OWN embedded engine**, named
`vanillabp-camunda7-<id>`. Two embedded engines must never share one database schema, because
then they would share one engine state. So every additional id needs either a datasource of its
own or a [table prefix of its own](#two-engines-on-one-database-table-prefix). Otherwise the boot
fails with a guiding message. `Camunda7AdapterBootTest#twoAdapterIdsOfTypeCamunda7YieldPerIdEnginesAndBeans`
and `#twoAdapterIdsSharingTheSameDataSourceFailWithGuidingMessage` test both halves on Spring
Boot, `Camunda7SameDataSourceValidationTest` on Quarkus. `Camunda7InstanceIdentityTest` tests
the comparison itself.

Settings per adapter id. All are optional and live at the canonical location
`vanillabp.adapters.<id>.*`:

```yaml
vanillabp:
  adapters:
    c7:
      type: camunda7
      # create/upgrade the engine schema on boot (engine values, e.g. true, false,
      # create-drop); default: true
      database-schema-update: true
      # engine-wide default history time-to-live (Camunda 7.24 rejects deployments
      # of processes without one); default: P180D; a process may override it via
      # camunda:historyTimeToLive
      history-time-to-live: P180D
      # OPTIONAL: the name of an APPLICATION-PROVIDED datasource this id's engine
      # runs on (required for every additional camunda7 id - see the transaction
      # caveat below!). Setting up datasources is deliberately the application's
      # concern - VanillaBP never builds its own pool. Spring Boot: the name of a
      # DataSource bean; Quarkus: the name of a declared named Agroal datasource
      # (quarkus.datasource.<name>.*).
      data-source-name: legacy
      # OPTIONAL: the engine's table prefix, which lets two ids run as two engines on
      # ONE datasource. Camunda does not create prefixed tables, so they have to exist
      # and database-schema-update has to be false - see below.
      table-prefix: NEW_
      # OPTIONAL: the job executor waits until the next job is due instead of polling
      # every 5 to 60 seconds, and a transaction which writes a job wakes it.
      # Default: false - see 'An idle engine lets go of its database' below.
      sleep-until-something-is-due: true
      # OPTIONAL: whether the engine's metrics reporter writes its counters to the
      # database every 900 seconds. Unset means the opposite of the key above, so an
      # engine which is allowed to wait is not woken by its own metrics.
      db-metrics-reporting: false
```

On Spring Boot, declare the additional datasource bean with
`defaultCandidate = false`. Then it stays out of by-type injection, and Spring Boot's
default-datasource auto-configuration stays active. This is the standard pattern for
additional application datasources:

```java
@Bean(defaultCandidate = false)
public DataSource legacy() {
    return DataSourceBuilder.create()...build();
}
```

BPMN files are read from each workflow module's configured `resources-location` and
deployed to the embedded engine of every prioritized adapter.

### Behaviour

- **BPMN deployment.** On boot the adapter reads every executable BPMN process
  (`<bpmn:process isExecutable="true">`) of each workflow module. A file may contain several.
  It deploys them as one Camunda deployment. The name-clash-avoidance mode decides where the
  deployment lands, see
  [Keeping workflow modules apart](#keeping-workflow-modules-apart). Duplicate filtering
  is on, so unchanged models are not deployed again on every boot.
- **Starting a workflow (two-phase).** The workflow-aggregate ID becomes the Camunda
  **business key**. The tenant is whatever the
  [name-clash-avoidance mode](#keeping-workflow-modules-apart) says. Phase one asks the
  engine. Phase two creates the instance after the caller's commit. The phase-two outbox
  dispatches it, and it skips an instance which is already there (see
  [decision 2](./DECISIONS.md#2-a-workflow-is-progressed-after-the-callers-commit)). So an
  application which uses this adapter needs a phase-two outbox. The VanillaBP platform
  integration provides one for JPA/JDBC and MongoDB setups.
- **Asynchronous continuations (job executor).** Each engine runs an idiomatic
  `SpringJobExecutor` on a managed thread pool. The thread names contain the adapter id. On
  Quarkus the engine brings its own pool. The executor does not start right away. It starts
  when the deployment pipeline starts workflow processing, after the application is ready.
  On graceful shutdown it stops once the last workflow module stopped, before the engine
  closes. A job which is due now is picked up right after the commit which wrote it,
  because the engine gives its own executor a hint for those. A job due LATER waits for the
  next acquisition cycle, unless the adapter id asks to wait for the due date instead. See
  [An idle engine lets go of its database](#an-idle-engine-lets-go-of-its-database).
- **Adapter ids with an OWN (named) datasource.** An engine on a named datasource
  cannot join the caller's transaction at all. Its commands commit on an
  adapter-internal transaction manager bound to that datasource. For outbound calls nothing
  changes: every progressing operation runs after the caller's commit anyway.
  For inbound calls both halves of the delivery contract change. The handler cannot run in the
  engine's job transaction, because the application's persistence has no part in it. So
  VanillaBP opens the transaction the workflow aggregate is saved in, and that one
  commits first. If the job fails afterwards, the engine hands it out again, and the handler's
  work is already committed. Every remote BPMS has this at-least-once window. So such an
  adapter id answers `deliversTasksAtLeastOnce()` with `true` and names each delivery by
  the id of the current job. The core then answers the repetition from its delivery log
  and does not run the `@WorkflowTask` method a second time (see
  [decision 6](./DECISIONS.md#6-a-task-handler-runs-inside-the-engines-own-job-transaction---the-delivery-record-part-superseded-by-decision-39)).

Each of the four bullets has tests. `Camunda7StartWorkflowIT` tests the deployment and the
two-phase start. `Camunda7JobExecutorLifecycleIT` and its Quarkus twin test that the executor
runs only while processing is started. `Camunda7RepeatedDeliveryIT` tests the
difference the datasource makes
(`repeatedDeliveryOnAnOwnDataSourceIsAnsweredFromTheRecord` against
`repeatedDeliveryOnTheSharedDataSourceRunsTheHandlerAgain`). `Camunda7MissingDeliveryLogIT`
tests what the startup says about a missing delivery log in each mode.

### An idle engine lets go of its database

The Camunda 7 job executor does not wait, it polls. Every 5 seconds, widening to 60 while
nothing happens, and every one of those cycles is a database command. An application which
spends most of its life waiting for a timer pays for that. On a database billed by
active use, you pay for doing nothing.

One key per adapter id changes it:

```yaml
vanillabp:
  adapters:
    c7:
      sleep-until-something-is-due: true
```

A cycle which found nothing then asks the engine for the job with the earliest due date
still ahead. It waits until exactly that moment. If there is no job at all, it waits until
somebody wakes it, and every engine command asks its own commit to do that. So these all
shorten the wait: starting a workflow, correlating a message, completing a task, sending a
signal, and a continuation the engine wrote for itself. The adapter does not have to name any
of them.

Be clear about what this is. It is not the immediate wake-up which version 1 sold as the
feature. The engine has always given its own executor a hint after a commit which wrote a job
due now. And it is not a longer polling interval. That would make a timer late, and the
polling would still not stop.

One thing is left for the operator. Where the key is on, the adapter asks the engine to
acquire jobs by due date. The reason: if the executor wakes for the earliest due job but then
acquires in some other order, it picks the wrong job as soon as more jobs are due than one
cycle takes. That order needs a database index on the due date of `ACT_RU_JOB`. Camunda
[documents](https://docs.camunda.org/manual/7.24/user-guide/process-engine/the-job-executor/)
it, and only the operator can create it in their database. The startup message asks for it.
The engine's `jobExecutorPreferTimerJobs` stays as it is. Which kind of job to prefer is a
different question from when to wake up.

The engine's metrics reporter goes quiet as well. It has a timer of its own and writes its
counters through a database command every 900 seconds. That would wake this engine four
times an hour. Where the key is on, that writing stops, and the counters are still kept in
memory. An application which needs them written sets `db-metrics-reporting: true`. The
startup message says what that costs.

VanillaBP's own machinery keeps polling, though. The phase-two outbox asks its store every
`vanillabp.outbox.poll-interval`, 10 seconds by default. The retention cleanup of the
task delivery log runs once an hour. If you switch this on and watch your database, you
see those two. So the startup message names them, and nobody has to look for them in the
engine.

On one node, a lost wake-up costs at worst the acquisition cycle which is already running.
A wake-up which arrives after a cycle read the database is kept, and it ends that cycle's
wait. So it never costs the wait itself. Camunda's own loop drops such a wake-up and pays one
idle interval for it. Here the same loss would cost the whole wait, up to a year when no job
is due at all. That is why this adapter closes that gap. The reasoning is
[decision 23](./DECISIONS.md#23-a-wake-up-which-arrives-while-the-next-wait-is-decided-still-ends-that-wait).

A second application which writes jobs into the same engine is not covered. Its commit runs
in another process and does not reach this one. A node may be waiting on a due date it
computed before that write. It learns about the job the next time it asks the engine. This
adapter does not support two applications on one engine.

The reasoning is [decision 18](./DECISIONS.md#18-an-idle-engine-is-told-when-to-wake-up-instead-of-asking-whether-it-is-time-yet).
`Camunda7DueDateSleepTest` tests the waiting rule against a real engine and counts the
connections an idle engine takes. `Camunda7WakeupInTheGapTest` tests that a job still runs
when it is written while the next wait is being decided. `Camunda7SleepingEngineIT` and its
Quarkus twin test that a booted application on either platform takes no connections while
nothing is due.

### Embedded-engine wiring

The `spring-boot` module builds the engine(s) itself from
`org.camunda.bpm.engine.spring.SpringProcessEngineConfiguration`. That class ships with
`org.camunda.bpm:camunda-engine-spring-6`. Its Spring dependencies are `provided`, so
the application's Spring Boot 4.1 / Spring Framework 7 is used. On purpose, the
`camunda-bpm-spring-boot-starter` is **not** used (see
[Known issues](#known-issues)). The engine of each configured adapter id:

- uses the application's `DataSource` and the application's `PlatformTransactionManager`.
  The engine tables `ACT_*` live next to the aggregates. Engine commands join
  the caller's transaction. This in-transaction guarantee is the whole point of the
  C7 adapter. The exception: `vanillabp.adapters.<id>.data-source-name` names an
  application-provided `DataSource` bean. Then the engine runs on that bean's datasource
  with an adapter-internal transaction manager (see the transaction caveat above). The
  adapter never builds a pool. If the bean is missing, the boot fails and lists the
  available `DataSource` beans,
- creates or upgrades its schema on boot (`database-schema-update`, default `true`),
- runs asynchronous continuations on a `SpringJobExecutor` with its own
  managed thread pool, active only while workflow processing is started, and
- applies a default history time-to-live (`history-time-to-live`, default `P180D`).
  Camunda 7.24 rejects deployments of processes without one. A process may still
  override it with `camunda:historyTimeToLive`.

A `DataSource` and a `PlatformTransactionManager` must be present, unless every configured
id brings its own datasource. A Camunda 7 application always needs a database.
`Camunda7AdapterBootTest#configuredAdapterWithoutDataSourceFailsWithGuidingMessage`
tests that message. `Camunda7UnknownDataSourceNameTest` tests the message for a
`data-source-name` which names nothing.

### What an extension may add to the engine

An extension of VanillaBP, above all the Business Cockpit, needs hooks inside the engine. It
needs a BPMN parse listener to attach its own task listeners. And it needs a way to learn that a
workflow started or ended. Version 1 took both from Camunda's Spring Boot starter, which this
adapter does not use. `Camunda7EngineCustomizer` (module `core`, package
`io.vanillabp.camunda7.engine`) replaces it:

|              Method               |                                What it contributes                                |
|-----------------------------------|-----------------------------------------------------------------------------------|
| `parseListenersBefore(adapterId)` | parse listeners running before VanillaBP's own — the model as its author wrote it |
| `parseListenersAfter(adapterId)`  | parse listeners running after VanillaBP's own — the usual case                    |
| `historyEventHandler(adapterId)`  | a handler for the engine's history events, installed next to the engine's own     |
| `customize(adapterId, config)`    | the engine configuration itself, for what the three above do not cover            |

Every method has a default, so an extension implements only what it needs. A customizer is
asked once per configured adapter id, because two ids are two engines. On Spring Boot it is a
bean of that type, on Quarkus a CDI bean. Both engine holders collect them while they build
their engine.

Two things are worth knowing. A parse listener contributed "after" may attach a built-in task
listener. That listener runs AFTER the ones VanillaBP attaches. An extension which tracks user
tasks depends on this order. And the history event handler is installed as a
`CompositeDbHistoryEventHandler`. So the engine writes its history exactly as before, and the
contributed handler sees every event as well. `Camunda7EngineCustomizerIT` tests both on Spring
Boot, `Camunda7EngineCustomizerTest` on Quarkus.

### What an extension may ask this adapter

Camunda 7 runs embedded. An extension of this adapter runs in the same JVM (Java virtual
machine), on the same execution tree and against the same process definitions. So every fact
it needs is a fact this adapter already looked up. Where an extension had to read the engine's
internals a second time, the two readings drifted apart on the next engine release. So it asks
the package `io.vanillabp.camunda7.api` (module `core`) instead. Like `Camunda7EngineCustomizer`,
this is API of this repository. It is not part of the VanillaBP adapter SPI, because no other
BPMS shares any of these mechanisms.

|                    Entry point                    |                                       What it answers                                        |
|---------------------------------------------------|----------------------------------------------------------------------------------------------|
| `Camunda7MultiInstances.of(execution)`            | the multi-instance scopes an execution runs in, keyed by BPMN element and outermost first    |
| `Camunda7MultiInstances.of(engine, executionId)`  | the same, for a caller holding only a user task's execution id                               |
| `Camunda7MultiInstances.of(..., registry)`        | either of the two, crossing a call activity which names its called process in an expression  |
| `Camunda7TaskDefinitions.of(formKey, elementId)`  | what a user task is called: the form key, and the element id where there is none             |
| `Camunda7TaskDefinitions.formKeyOf(...)`          | the form key AS WRITTEN, read from the model, from a parsed task or from the engine          |
| `Camunda7Executions.rootProcessInstanceIdOf(...)` | the root of a workflow, an instance nobody called being its own root                         |
| `Camunda7EngineFacts`                             | one per configured adapter id: the tenant, the transaction answer, the registry, the version |

`Camunda7EngineFacts` is a bean per configured `camunda7` adapter id on both platforms. On
Spring Boot it is named `Camunda7_EngineFacts_<id>`. On Quarkus it is an entry of the produced
`List<Camunda7EngineFacts>`. `adapterId()` tells two engines apart. Through it an extension
reaches two things. `taskRegistry().resolve(tenantId, processDefinitionKey)` leads back from what
the engine reports to the workflow module and the plain BPMN process id.
`definitionOf(processDefinitionId)` answers the deployed version from the cache which a
running workflow already filled. An operator reads that version through
`DeployedProcessVersion.displayVersion()`. The platform writes it, so that every BPMS spells one
deployment the same way.

Read two sentences on the types themselves before you use them. First, the registry fills up
while the deployment pipeline runs. It is complete for a workflow module once `wireBpmn` ran
for it, and complete for the application once the pipeline finished. If you ask earlier, you
ask a registry which is still being filled. Second, a form key which is an expression resolves
to its own text: `${formOf(task)}` stays `${formOf(task)}`. It never resolves to the value the
engine computes from it. The computed value differs per workflow instance. Otherwise one task
would reach a consumer under as many identities as it has instances.

`joinsTheApplicationTransaction()` gets its own paragraph, because it was answered wrongly
once. It is true while the engine runs on the application's own data source. It is false once
the adapter id has its own data source through `vanillabp.adapters.<id>.data-source-name`. That
holds on Quarkus as well, where the engine is built on the container's transaction manager. A
JTA (Jakarta Transactions) transaction around two independent data sources is still two
commits. And everywhere else this adapter already treats such an engine as separate.
`Camunda7EngineFactsOnQuarkusTest` tests it on that platform, `Camunda7EngineFactsIT` and
`Camunda7AdapterBootTest` on Spring Boot.

### The multi-instance levels this adapter reports

`Camunda7MultiInstances` walks the execution tree upwards and collects every multi-instance
activity on the way, outermost first. The walk takes the BPMN model of EVERY execution it stands
on, not just the one it started in. That lets it leave the process the task is modelled in. An
execution of the calling process has to be looked up in the calling model. If it is looked up in
the model of the called process, the level is lost. Version 1 took the model once and lost the
level there. So did this version, until the walk got a model per execution.

The walk steps into a calling process where the called process continues the caller's workflow
aggregate. The deployment writes this onto the call activity as a `camunda:property` named
`vanillabp:sameWorkflowAggregate` (decision 22 in [`DECISIONS.md`](./DECISIONS.md)). A process with
an aggregate of its own is a business case of its own. It hears nothing about the iteration which
called it.

A call activity may name the process to call in an expression. Then it has nothing to carry that
note, because the model does not say which process will be called. The walk asks the core for it
while the workflow runs, through `Camunda7TaskRegistry`. The start listener of a called process
asks the same question there. So the overloads of `Camunda7MultiInstances` which take the
registry cross such a call activity, and the ones without it end there. Where the model spells
the called process out, the answer written at deployment stands, and nobody asks again.
Workflows which still run in an older version of a process need exactly that.
`Camunda7MultiInstanceByExpressionIT` runs one model against the engine which names the same
called process both ways. `Camunda7MultiInstancesAcrossCallActivitiesTest` tests the shapes.

Both contexts of this adapter answer the walk: the one of a service-like task and the one of a
user task. A user task is notified from a task listener. The engine hands that listener a
`DelegateTask`, not an execution. So the levels were missing there, while every other kind of
task reported them. `DelegateTask#getExecution` is the execution the task waits on.
`Camunda7MultiInstancesAtAUserTaskTest` measured this while a task is created and while it is
cancelled. So the listener starts the walk where the service-like side starts it, and both
contexts carry the registry.

One level cannot be answered at all. The deployment says so, so a handler does not have to find
out. The item of an iteration is the variable named by `camunda:elementVariable`. A
multi-instance element whose model names none has no item to report. This is true above all for
one based on a cardinality. The index and the total are there either way.

`Camunda7MultiInstanceItems` joins the two halves of that while `wireBpmn` runs. For each task,
this adapter walks the chain of multi-instance elements ENCLOSING it. It keeps the ones which name
no `camunda:elementVariable`. For every task wired here, the core answers
`WorkflowTaskWiring#multiInstanceElementNames`: the element ids for which the methods serving the
task declare `@MultiInstanceElement`. Where the two meet, the boot ends. The message names the
task, the element, the attribute and the two ways out.

Why the chain and not the whole process? A handler gets the item of the rounds its own element
runs in, and of nothing else. An element in another branch never reaches it, whatever that
element names. So refusing such a pair would end the boot of a correct model. This adapter read
the process until wave 118. Camunda 8 read the chain from the start.

Neither half alone is enough. An element which iterates a number of times is a model somebody
meant to write. So is a handler which reads only the index and the total. Refusing either would
end the boot of an application which does nothing wrong. Only the pairing is a defect. It used
to show up as a `null` parameter, with nothing saying why.

An element id this model does not know is no finding. The chain crosses a call activity. So a
task of a called process asks for an element of its caller, and this model is the wrong place to
look for it. The core is asked by the task definition and by the element id, because a method
may name either of the two.

The same question is asked about a version the engine still HOLDS. There the answer is passed on
and ends nothing. Nobody can redraw such a model. So the adapter puts the chain of every task it
reads, outermost first, into `BpmnTaskSpec#multiInstanceElementsWithoutAnItem`. The core checks
it against the methods which still serve that version. A modelled listener carries its chain
too, because a listener method reads its item from the same iteration as a task's method. An
adapter which does not read the shape answers `null` there, and the core asks nothing.
`Camunda7ItemsOfHeldVersionsTest` reads three held versions: one with an item, one without, and
one whose listener sits inside a round that names none.

### Two engines on one database: `table-prefix`

`vanillabp.adapters.<id>.table-prefix` sets Camunda's `databaseTablePrefix`. This is how
two adapter ids become two engines on ONE datasource. It is the side-by-side migration setup on
a single database. Every statement the engine issues at runtime goes through MyBatis,
which adds the prefix in front. That part works. Camunda leaves out creating the tables, and
it says so in its own API,
`ProcessEngineConfigurationImpl#setDatabaseTablePrefix`:

> the prefix is not respected by automatic database schema management. If you use
> `DB_SCHEMA_UPDATE_CREATE_DROP` or `DB_SCHEMA_UPDATE_TRUE`, activiti will create the
> database tables using the default names, regardless of the prefix configured here.

This is the same on every database. The schema management runs the DDL (data definition
language) files shipped with the engine
(`org/camunda/bpm/engine/db/create/activiti.<database>.create.*.sql`) statement by statement.
Those statements name the tables word for word in every dialect.
`Camunda7TablePrefixEngineBehaviourTest` in the core module records this. With a
prefix and `database-schema-update: true`, the engine creates a full set of unprefixed
`ACT_*` tables. Then it dies on its first query against the prefixed `ACT_GE_PROPERTY`.
That is what Camunda does here.

So a prefixed adapter id means: its tables are there already.

```yaml
vanillabp:
  adapters:
    c7:
      type: camunda7
    c7-new:
      type: camunda7
      table-prefix: NEW_
      database-schema-update: false
```

`Camunda7TablePrefixSchema` checks this before the engine is built. So a wrong
configuration costs neither a MyBatis stack trace nor a set of stray tables in the shared
database. `Camunda7TablePrefixSchemaTest` covers the creating values, the missing
tables and the prefix which names a schema. A prefix together with a creating
`database-schema-update` ends the boot. So does a prefix whose tables are missing. Both messages
name the prefix, the datasource, the missing tables and the two ways on. `Camunda7TablePrefixIT`
runs the working setup: two adapter ids on one H2 database, one of them prefixed. Both deploy the
workflow module and start workflows which stay in their own engine.

**Why the adapter does not create the tables.** It could transform Camunda's statements.
The integration test's `PrefixedEngineSchema` does that, after two attempts which looked
right and were not. Renaming every `ACT_` also renames the columns `ACT_ID_` and
`ACT_INST_ID_`, and the first query fails on a column which is not there. Taking
"ends with an underscore" as the sign of a column leaves the index `ACT_IDX_EVENT_SUBSCR_CONFIG_`
unrenamed. There it collides with the unprefixed engine of the same database. Neither
mistake shows up before something runs. VanillaBP would have to carry that rename for seven
engine components, six dialects and every engine upgrade. That would make it the owner of a
schema whose version bookkeeping (`ACT_GE_SCHEMA_LOG`) stays Camunda's anyway. So the rename
belongs to whoever owns the schema. They apply it with Liquibase or Flyway, as the wiki page
[Creating the engine tables yourself](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Configuration#creating-the-engine-tables-yourself)
describes. If nobody wants to own it, `data-source-name` gives the adapter id a
database of its own. There the engine creates and upgrades its schema as usual.

## Task processing (execution model)

`@WorkflowTask` methods are wired to BPMN tasks by expression. Implement a service
task as *Expression* `${myTaskDefinition}` (or *Delegate expression*). The
expression text names the method's task definition, which defaults to the method
name. At deployment the adapter checks that every BPMN task of a process has a
`@WorkflowTask` method, with guiding messages. It also forces `asyncBefore`/`asyncAfter`
onto service-like tasks.
So every task runs in its own job transaction, and the embedded engine behaves like a
remote BPMS.

All of that applies only to a process which a `@WorkflowService` class of this application
claims. The parse listener runs for every model the engine parses. So it asks
`Camunda7TaskRegistry#claimsTheProcessDefinition` before it changes anything. Two kinds of
process keep their flags and get no listener: a process this application deploys without
claiming it (the application marked it with `implemented-externally`), and a process somebody
else deployed into the same database. `wireBpmn` returns for an unclaimed process before it
collects a task, so no check refuses or warns about it either. Every such question goes to
`WorkflowTaskWiring#isClaimedByAWorkflowService`. Why:
[decision 43](./DECISIONS.md#43-the-parse-listener-changes-only-a-process-the-application-claims). `Camunda7UnclaimedProcessesAreLeftAloneTest` holds a claimed, a marked
and a foreign process in one engine.

The wiring leaves one task alone: a business rule task which calls a DECISION
(`camunda:decisionRef`). The engine evaluates it against a decision table this module
deployed, so there is no `@WorkflowTask` method to ask for. A business rule task wired by
an expression stays an ordinary VanillaBP task. The result of the decision reaches a
following task like any other variable, through the task's input mapping and a
`@TaskParam` parameter. `Camunda7TaskProcessingIT#aDecisionTableOfTheModuleIsDeployedAndEvaluated`
runs both rules of a table end to end.

The other direction is checked as well, and no adapter has to remember it. A
`@WorkflowTask` method which matches no task of any BPMN process of its workflow module
ends the boot. The message names the method and the fix. The core runs that check itself
(`WorkflowTaskWiring.validateNoUnwiredWorkflowTaskMethods`) once every adapter of the
module finished deploying. This adapter forgot to call it for a year, which is why the
duty moved (story 158). `Camunda7TaskWiringValidationIT` tests both directions.

Handlers run INSIDE the engine's job transaction. On Spring Boot it is Spring-managed, on
Quarkus it is JTA with the CDI request context activated. The workflow aggregate is
loaded by the business key, the method is called with bound parameters, and the
aggregate is saved. Business changes and engine state commit or roll back together.
Outcomes:

- normal return - the task completes;
- `TaskException` - the task completes with a BPMN error (error-boundary
  routing); the aggregate changes COMMIT (V1 contract - do not add your own
  `@Transactional`);
- any other exception - the job transaction rolls back and the job executor
  retries (finally: incident);
- methods declaring `@TaskId` leave the task open (asynchronous completion via
  `ProcessService#completeTask`). Such tasks have to be wired by
  *Delegate expression*. An *Expression* task completes when the expression
  returns, so it could never stay open.

`Camunda7TaskProcessingIT` runs every one of those outcomes against the embedded engine
(`taskExceptionRoutesErrorBoundaryAndCommits`, `technicalExceptionRollsBackAndRetries`,
`asyncTaskStaysOpen`, `asyncBeforeAndAfterForcedOntoServiceTasks`).
`Camunda7WorkflowLifecycleTest` runs the same flows on Quarkus.

**The application does not start on that last defect.** While wiring,
the adapter asks the core whether the method serving a task completes
asynchronously (`WorkflowTaskWiring#workflowTaskCompletesAsynchronously`).
The `WorkflowTaskRegistry` answers from the method's `@TaskId` parameter. The adapter
aborts the deployment for every task wired by *Expression* whose method wants to
keep it open. `Camunda7TaskELResolver` keeps the same guard for a model which
reached the engine another way. Both report the same message, which lives
once in `Camunda7TaskConnectable#asynchronousTaskWiredByExpression`
(`Camunda7TaskWiringValidationIT#asynchronousTaskWiredByExpressionAbortsBoot`). The reverse
case stays silent on purpose. *Delegate expression* serves a method without
`@TaskId` just as well, because the behavior leaves the activity when the handler
returns.

The question is asked with the task definition and with the element id, the same way the
wiring validation matches a method. A method names either of the two. With only one key, an
application which wires by `@WorkflowTask(id = ...)` gets past the check. It then meets the
defect on a live workflow instead (`Camunda7AsynchronousTaskWiringTest`). A modelled listener is
the exception. It is asked by its task definition alone. An element can carry a task and a
listener at once. There the element id would answer for the task's method and refuse a correct
model.

**Completing/canceling async tasks (`ProcessService#completeTask`/`#cancelTask`):**
the `@TaskId` value is the ID of the parked execution. Completing signals
that execution. Canceling signals it with the adapter's cancel marker, and the
behavior passes on the BPMN error (error-boundary routing). Both are two-phase.
Phase one asks whether the task is still there, so a caller learns about a gone task
at the place where it called. Phase two signals the execution after the commit, and the
outbox dispatches it. So a rollback leaves the task open.
`awarenessOfTask` finds a task by its execution ID plus a business-key check
and a SCOPE check (see below). `@TaskEvent CANCELED` IS delivered on Camunda 7. An END
execution listener is attached at parse time. When the activity of the open task is canceled
(interrupting boundary event, instance termination), it calls the handlers which subscribe to
lifecycle events. This happens inside the cancellation's transaction.

**User tasks:** the user task's `camunda:formKey` is the task
definition. A `@WorkflowTask` method for it is the notification handler. It is called on the
engine's global CREATE and DELETE task-listener events (CREATED /
CANCELED via `@TaskEvent`, the task's ID via `@TaskId`). These listeners are attached as BUILT-IN
listeners at parse time, so they run before the ones a modeller defined. The handler
never completes the task. `ProcessService#completeUserTask` maps to
`TaskService.complete`, `#cancelUserTask` to `TaskService.handleBpmnError`
(error-boundary routing). Both are two-phase like every other progressing operation: phase one
checks that the task is still there, phase two acts after the commit. `awarenessOfUserTask`
finds a task by its task ID plus a business-key check and the same scope check.

A user task of a claimed process needs such a method. Or it needs the line
`implemented-externally=true`, which says that something else serves it, a task list for example.
Without either the boot ends. Version 1 did the same: its parse listener wired every user task
with `allowNoMethodFound=false`. The rule and the message live in the core, so all adapters say
the same sentence ([decision 36](./DECISIONS.md#36-a-user-task-nothing-serves-is-named-in-a-claimed-process-and-nothing-is-refused---superseded-by-decision-42)
is superseded by [decision 42](./DECISIONS.md#42-a-user-task-needs-a-method-or-a-line-and-an-external-task-may-pass-with-the-line)). A user task marked that way still gets the
engine's listener events. The adapter skips them, because no method asks for them.

**What the awareness probes answer for:** the election
contract of `MigratableProcessService` says an adapter answers only for the scope
it is ASKED about. A Camunda 7 business key is the workflow-aggregate id.
It is unique per aggregate type, but not across an engine. So the probes
narrow every query by the `WorkflowScope` the core hands them. That is the process
definition keys of its BPMN processes (`processDefinitionKeyIn`, including
secondary processes of the same `@WorkflowService`), plus the tenant its
workflow module runs in (`tenantIdIn`, or `withoutTenantId` where the mode uses
none). This holds for running instances and for the history query behind a
`COMPLETED`. The two task probes check the instance the same way, on top of
the business key. `awarenessOfWorkflowForRedispatch` narrows its history query the
same way. So a workflow of another workflow module, of another tenant or of a
process this application never wired is `UNKNOWN_TO_BPMS`. The election then
moves on to the BPMS which really holds it. `Camunda7AwarenessScopeTest` tests every one
of those narrowings (`aForeignWorkflowIsNotClaimed`, `theTenantIsPartOfTheScope`,
`anotherModuleOfTheSameAdapterIsNotClaimed`, `aSecondaryProcessIsClaimed`,
`theHistoryIsScopedAsWell`). `Camunda7AwarenessTest` tests what an unreachable engine
answers.

**What a retried start counts:** an aggregate may carry a second workflow once its first
one ended. The core asks `awarenessOfWorkflowForRedispatch` before it dispatches a start
again, and it hands over the moment the start was planned. The adapter counts only the
instances in the history which started at or after that moment (`startedAfter`, which
Camunda compares inclusively). Otherwise the ended first workflow would answer for the
second one, and the retry would be skipped. The start time comes from the clock of the
node which ran the start, and the moment from the clock of the node which planned it.
Where those clocks differ by more than the time between planning and dispatching, the
answer is "unknown" and the start runs again. The check for a running instance in
`startWorkflow` catches that, and the same check covers an engine without history.
An entry planned before the moment was recorded carries none, and then every workflow
counts. `Camunda7SecondWorkflowOfAnAggregateTest` tests it.

The write behind `aggregateChanged` answers for the same scope. Here a mistake costs more than
a wrong answer. In Camunda 7 a variable write makes the engine evaluate conditional events
again. And the push writes a technical marker even for an aggregate which shares nothing. So
an unscoped write would ADVANCE a workflow of another module, of another adapter id
during a migration, or of another application on that database. So the global-scope
branch narrows by definition key and tenant like the probes do. Two
instances within one scope end the operation with a message which names them, not with
a `singleResult()` stack trace. "The workflow is gone" is the tolerated case of
an at-least-once phase two, and it is judged within the scope as well. The branch which writes
into the scope of a parked task needs no comparison. It is addressed by an
execution id the engine handed out, and that id names exactly one execution. The tests for the
scoped push are `Camunda7AwarenessScopeTest#aPushReachesTheOwnWorkflowOnly`,
`#aPushInAMigrationStaysWithItsAdapterId` and `#aPushOfAnEndedWorkflowIsTolerated`.

**Message correlation:** `correlateMessage` is two-phase like every other
progressing operation (tenant = workflow module, business key = aggregate ID). Phase
one asks whether a subscription is waiting. If none is, it fails the caller's transaction,
so "nothing matched" stays a synchronous answer. Phase two correlates after
the commit, through the outbox. It tolerates a subscription which is gone by then. So a
rollback leaves the instance waiting. A correlation id matches through the V1
local-variable convention `<primary bpmnProcessId>-<messageName>` at the receiving
scope. The name keeps the primary process even where the message is caught in a called
process. This is the one place in this adapter where a value of a called process is named
after the process at the top. It stays that way because the models set the variable. A
new name would be one which no existing model sets (decision 45 in the repository's
DECISIONS.md). `startWorkflowByMessage` uses `correlateStartMessage()` and is two-phase the
same way, with a check whether the workflow already started. No variables are ever set. This
is the payload doctrine. `Camunda7TaskProcessingIT#correlateMessageResumesProcess`,
`#correlateMessageWithCorrelationId`, `#rolledBackCorrelationLeavesInstanceWaiting` and
`#startWorkflowByMessageStartsInstance` test this.

**A message starts only the process of its own process service.** While it wires a model,
the adapter reports the plain names of the message start events of each process
(`BpmsInitiatedStartInvoker#reportStartMessages`). The core refuses any other message
before phase one. If the adapter cannot read the message name of a process, it does not report
it, and the core does not check it. The adapter keeps the rule itself as well. Phase one reads the
model of the process definition, and phase two names that definition in the correlation
(`processDefinitionId`). Without the definition id Camunda 7 would start every process
which waits for the message. Version 1 did exactly that. A correlation which names a
definition must not name a tenant as well. It does not need to, because the definition
was looked up in the module's tenant.
`Camunda7TaskProcessingIT#startWorkflowByMessageRefusesTheMessageOfAnotherProcess` and
`#bothPhasesOfAStartByMessageStayWithTheOwnProcess` test this.

The definition is the newest version of the process in the module's tenant. It is the same one
`startWorkflow` starts. A version which somebody deploys from outside while the application runs
is the newest one, too. `Camunda7StartByMessageIT` tests this.

Both phases compare the message name with the name in the model. That name is always
plain. Camunda 7 refuses to deploy a message start event whose name is an expression, even
one which needs no variables. The message of the check before phase one says so.
`Camunda7MessageNamedByExpressionTest` measured this on Camunda 7.24.0.

BPMN expressions like gateway conditions or multi-instance collections
(`${riskAcceptable}`, `${items}`) resolve against the workflow aggregate
which the business key identifies. They use a getter, a boolean getter or a field. On Spring
Boot, Spring beans still resolve. VanillaBP does not serve external tasks (`camunda:topic`),
see [External tasks](#external-tasks).

### Listeners somebody modelled

A `camunda:executionListener` is a place where the engine lets the application in. VanillaBP 1 let
a `@WorkflowTask` method serve such a listener and said nothing about it. This version
serves it again where the application asks for it.

`vanillabp.adapters.<id>.allow-listeners` is the switch, default `false`. It resolves at three
levels, and the most specific configured value wins in both directions:

```
vanillabp.adapters.<id>.allow-listeners
vanillabp.workflow-modules.<m>.adapters.<id>.allow-listeners
vanillabp.workflow-modules.<m>.workflows.<w>.adapters.<id>.allow-listeners
```

There is no TASK level. That level is keyed by the task DEFINITION. But this key decides whether
a listener becomes a task at all. So when the key is read, there is no task definition yet to key a
level by. A value set at task level gets one guiding warning, which names the three levels that
work, and the boot goes on. The Camunda 8 adapter reads the same key at the same three levels.

`Camunda7Listeners#listenersOf` reads the BPMN of a process. `collectModelledListeners` keeps
exactly one group of what it finds: a listener written as `camunda:expression` or
`camunda:delegateExpression` whose unwrapped expression text is a task definition which a
`@WorkflowTask` method names. `delegateExpression="${archiveOrder}"` is served where
`@WorkflowTask(taskDefinition = "archiveOrder")` exists, and only then.

That second half keeps the feature from taking something away. A test of this repository found
it: `repeated-delivery.bpmn` carries
`camunda:executionListener delegateExpression="${failTheJobOnce}"`, and the name belongs to a Spring
bean which implements the engine's own `ExecutionListener`. With Spring or CDI a delegate expression
resolves against the application context. So that is an ordinary Camunda 7 model. It is older than
VanillaBP and none of its business. A `camunda:class` and a `camunda:script` listener are the same
case without an expression at all. None of them is refused or reported. Whatever resolves them
keeps resolving them.

Only the task-definition route counts, and `workflowTaskHandlerExists` is asked with it.
`@WorkflowTask(id = ...)` names the ELEMENT. One element may carry a task and a listener at once,
and `RD_Task` above carries both. So the element id cannot say which of them a method means.

A `camunda:taskListener` is not read at all. VanillaBP itself notifies the `@WorkflowTask` method of
a user task, on creation and on cancellation.

The listeners VanillaBP attaches are none of these, and nothing a modeller writes can switch them
off. These are the cancellation listener, the user-task listeners, the listener of a start event the
engine fires on its own, and the one which reports a workflow's end. They are attached to the
element the engine PARSED. An extension reaches the engine the same way, through
`Camunda7EngineCustomizer` (see
[decision 14](./DECISIONS.md#14-an-extension-reaches-the-engine-through-a-customizer-not-through-the-engine)).
The collection reads the BPMN, so it sees only what a modeller wrote. The separation
needs no prefix which somebody has to keep up to date.

Where the switch is off and a model carries a served listener, `collectModelledListeners` ends the
boot. This is on purpose not left to the core's wiring validation. A connector asks VanillaBP to
leave an element alone, so the validation finds a task which nothing serves and ends the boot by
itself. A listener, in contrast, asks VanillaBP to serve something. Without the key there is no task
spec, and so nothing for the validation to miss. Instead, the engine would evaluate the listener's
expression on its own. A workflow which reaches the element would then either fail on a name
nothing resolves, or run a method the application never meant for that element. The message names
every listener of the process with its element, its event and its expression. It also names the
three levels and the cost.

Where the switch is on, the listener is a task like any other one:

- It becomes a `BpmnTaskSpec`. So `validateTaskWiring` asks for a `@WorkflowTask` method and ends the
  boot where none exists. And `validateNoUnwiredWorkflowTaskMethods` reports a method which matches no
  listener of any wired process. Version 1 wired its listeners privately and checked neither direction.
- `Camunda7TaskELResolver` resolves the listener's own expression. A `camunda:delegateExpression`
  gives a `Camunda7ListenerNotification`. The engine expects a listener object there, and an
  activity behavior would try to leave the element. A `camunda:expression` calls the handler while
  the expression evaluates and answers `null`. The listener is done when the handler returns.
- What the workflow aggregate shares is written onto the execution inside the engine's own
  transaction, exactly as for a task. This is true for every listener and every event. On Camunda 8
  it is true only for an execution listener on `end`. There a `start` execution listener and a task
  listener write nothing into the process instance. So a module which runs on both engines
  behaves differently at those two placements.

The event is part of a listener's identity: one method serves one event of one element. `@TaskEvent`
receives `CREATED` when that listener fires, whichever event the modeller picked for it. The reason:
a method without that parameter subscribes to `CREATED` alone, and any other value would silently
never call such a method.

`CANCELED` is the second event a listener knows. A modelled listener fires at its own moment and at
no other. An interrupting boundary event or a terminating end event may take an element away. Then
the element never reaches a `start` listener, and the method learns nothing. So
`Camunda7TaskCancellationListener` serves the listeners of an element as well as its task.
`Camunda7AsyncBpmnParseListener#parseProcess` attaches it to the elements which carry one. The
process is asked once, after the engine parsed its whole scope, not once per element type. A
listener may sit wherever a modeller can select something. An element the engine has no activity
for is never canceled, and it is skipped with a debug line. A sequence flow is the case a modeller
reaches.

A listener the modeller put on `end` gets nothing extra from VanillaBP. This engine fires an END
execution listener also when the element is canceled. That is how the cancellation listener hears
a cancellation at all. So such a method already hears the moment, and a second report would be the
same moment twice. `Camunda7Listeners#isACancellation` is that rule.
`Camunda7TaskRegistry#registerListenerNeedingACancellation` is what the wiring writes down for the
parse listener to read.

An element which already carries the cancellation listener is left alone. Every service-like
activity carries it, because the transaction boundaries put it there. A second copy would report
one cancellation twice.

Two more shapes end the boot, besides the missing key. Each message names the listener and the way
out. `refuseListenersSharingATaskDefinition` answers two served listeners of one element under ONE
expression. One method would serve both events, and nothing it could ask would tell it which one it
is in. Version 1 decided this case with a `findFirst`. Two listeners of one element under DIFFERENT
expressions are served, one method each. And a method declaring `@TaskId` is refused while the
process is wired. A listener is notified and done, so such a task can never stay open, and the id
would complete nothing.
A method which throws `TaskException` is answered at runtime, not at boot, because no signature
shows it. `Camunda7ListenerNotification` names the cause. Otherwise the engine would raise an
incident which says nothing. The engine is inside a transition of its own and has no token to
route.

Every boot of a workflow module whose listeners are served writes one framed WARN. It names the
key, the module, and every served listener with its element, its event and its expression. It also
says what it costs, how a cancellation reaches the method, and the way back. Nothing silences it, see
[decision 20](./DECISIONS.md#20-a-listener-somebody-modelled-is-a-task-and-only-where-the-application-asked-for-it).
Where the switch is on and no model of the module carries a listener, the boot writes one line instead
of the frame.

### What names a delivery here, and what names an activation

A delivery identity is answered where the engine has a datasource of its own. An activation
identity is answered always. The two look like one value under two names, but their contracts are
opposite. A delivery identity has to stay EQUAL while the engine repeats itself. An activation
identity has to DIFFER between two activations of one element.

On the application's datasource there is nothing to name. The handler runs inside the engine's
own job transaction. So a redelivery proves that nothing was committed, and no processed delivery
exists to remember. On an engine datasource of its own the two commits are separate. There the
delivery is named by the id of the job which runs it. The engine keeps that id while it lowers
the job's retries, and the next activation of the element runs on a job of its own. That is
exactly what the core needs to tell a repetition from new work.

A user-task notification is named in neither mode. A user task gets no job for itself: one
transaction creates every user task the token reaches. So the current job would name several
notifications, and the second would look like a repetition of the first. The values which are
unique per task, the task id and the activity instance, are generated while the task is created.
So the rollback which causes the repetition also causes a new value. A handler notified about a
user task therefore runs again after a crash. It did the same before an own datasource could be
configured.

The engine answers the activation in both modes.
`DelegateExecution#getActivityInstanceId()` reads `<element-id>:<instance-id>`. So the second
element of a multi-instance activity and the next iteration of a loop each get their own. The core
puts it into the idempotency key of a message correlation planned while the handler runs. That
keeps three siblings of one workflow aggregate from sharing a key.

Two more values travel with every delivery, and VanillaBP writes both into the delivery record. One
is the element id a modeller wrote on the BPMN element. The other is the id the engine gave the
running instance. Neither steers anything here. Somebody who reads the record uses them to find the
task outside VanillaBP, for example an operator who searches Cockpit or an extension which links
into the model. The adapter has both at hand in every mode. The element id was read from the model
while wiring, and the execution and the user task each name their process instance.
`Camunda7RepeatedDeliveryIT#theRecordNamesTheElementAndTheWorkflow` reads both back out of the
table.

The element id is not the task definition. Here a task definition is the expression text of a
service task and the form key of a user task. So somebody who looks for the element needs the
element id.

The engine datasource mode has a picture of its own, [Camunda 7 on an engine datasource of its
own](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/README.md#deliveries-vanillabp-already-processed-taskdeliverylog-spi).
It puts the two commits on a time line. It shows how the record answers the job which the engine
repeats. It sits next to the core's delivery log, because only that mode makes a repetition
visible to the core at all.

`Camunda7RepeatedDeliveryIT#deliveryRepetitionIsAnsweredFromTheDatasourceMode` tests the
delivery half in both modes. The user-task half is an assumption, not something a
test checks. It follows from two facts: a user task gets no job of its own, and the task id
is generated while the task is created. A Camunda 7 which reused a task id across a
rollback would prove it wrong.

## Outbound operations: one handler per operation

Everything this adapter sends to the engine is a `PhaseOperationHandler`. There is one per
operation in `Camunda7ProcessService.phaseOperations()`. `phaseOne` asks inside the
caller's transaction, `phaseTwo` acts after the commit. The operation itself belongs to
VanillaBP's `PhaseOperation`: its persisted name, what deduplicates it, which engine serves it,
and how a failure is worded. So an operation added later costs this adapter
one entry in that map and nothing else.

The embedded engine can answer phase one for free and in the same transaction. That is
why this adapter asks more there than a remote one can. A task which is gone, a message
nothing waits for, a correlation id no execution expects, a message start event which is
not deployed: all of them fail where the application made the call.
`Camunda7PhaseOneChecksTest` tests that.

A check which finds the task gone throws `io.vanillabp.spi.process.TaskNotFoundException`.
The SPI documents this type for a task which no BPMS knows any more. The platform raises the
same type when its own probe found out. Which of the two answers depends on whether a
delivery record exists. An application cannot see that, so it must not decide what
an application can catch.
`Camunda7RepeatedDeliveryIT#aStaleCompletionRaisesTheGuidingException` runs it against
an engine on its own datasource.
`Camunda7UserTaskDeliveryRecordIT#aUserTaskDeliveryIsRecordedAndSharpensAWrongCompletion`
runs it against the shared one, where a record is written as well (decision 39).

Every phase two is idempotent, because the outbox dispatches at-least-once. A start
skips an instance which already carries the business key. Completing or cancelling
checks the task. Correlating checks the subscription. Each check happens BEFORE the
write. A failing engine command marks the dispatcher's transaction rollback-only,
and it would take the whole dispatch with it.
`Camunda7TaskProcessingIT#awarenessAndPhaseTwoEdgeCases` runs the repeated dispatches.

### When a failed phase two is repeated, and when it is not

A phase two which failed is tried again, because most engine failures pass. Examples are a locked
row, or the optimistic locking this adapter went two-phase for in the first place.
`Camunda7ProcessService.isPhaseTwoFailureRepeatable` answers `false` for three of them. The
core then blocks the outbox entry and does not keep trying up to
`vanillabp.outbox.block-after-attempts`.

|   what the cause chain carries   |                        why repeating cannot fix it                         |
|----------------------------------|----------------------------------------------------------------------------|
| `BadUserRequestException`        | the engine rejected the request itself, say a task id which does not exist |
| `ELException` and its subclasses | the model asked the values for something they have not got                 |
| `Camunda7RefusedStart`           | the start named a process this engine does not hold                        |

The second row is the expression language. Its subclasses are the ones for an unknown
method and for an unknown property. Camunda ships that language shaded (repackaged under its own
package name). So the exception is recognised by the name of the class, not by an import. The
package it sits in belongs to the engine's own implementation, and a fork of the engine shades it
somewhere else.

Calling every expression failure permanent is a judgement call. A method which a flattened value
does not have never turns up. But a navigation into an attribute which is null today could read
a value tomorrow. Both count as permanent here. An expression which fails while an
instance is created loses the instance. Nothing in the engine shows it, and nobody in the
application waits for it. So hours of attempts help no one.
`AbstractNestedExpressionsIT#aConditionalStartEventWhichThrowsBlocksTheStartAfterOneAttempt`
runs that case, the conditional start event of an event subprocess.

The third row is the start of a process which nothing deployed. The engine answers it with a
`NullValueException`. That is a `ProcessEngineException`, just like a database which was
briefly away. So the start of a committed aggregate was tried until its attempts ran
out, and the workflow never came into being. The exception says nothing about the
operation. For an operation on a workflow which exists, the same answer is the
at-least-once remainder which the outbox is right to repeat. So the start marks its own refusal.
`startProcessInstance` wraps what the engine threw into a `Camunda7RefusedStart`, and only
that wrapper is permanent. `Camunda7RefusedStartTest` measures the engine's answer against
a real engine and checks the verdict.

The same class records the other half of that measurement, the half nothing can be done
about. A Camunda 8 cluster refuses a model whose only start event is a timer or a message.
Camunda 7 simply STARTS it, at that event. So an application which moves such a
workflow between the two meets a running workflow here and a blocked outbox
entry there.

Everything else is repeatable. When in doubt, the platform asks an adapter to repeat.

## Decision tables

The boot deploys the `.dmn` files of a workflow module in the SAME Camunda
deployment as its BPMN files. Same tenant, same duplicate filtering, one version step for
process and decision together. So a business rule task which binds its decision to the
deployment (`camunda:decisionRefBinding="deployment"`) finds it. So does the default
`latest` binding.

Under `use-prefix` the decision ids are rewritten like the process ids. The
`camunda:decisionRef` of the business rule tasks is rewritten with them, so both sides name
the same string (`Camunda7ScopingTest#aBusinessRuleTaskFindsItsRenamedDecision`). A
`decisionRef` given as an expression gets the prefix written in front of it, the same as a
call activity's `calledElement`. Camunda 7 reads both attributes as one expression. So
`${whichDecision}` reaches the engine as `loan-approval__${whichDecision}`, and the engine looks
up the prefixed id of whatever the expression yields.
`Camunda7DecisionByExpressionTest` runs such a model against an engine, with and without the
prefix. This mode cannot follow a reference to a decision which the module does not
deploy. That one is renamed here, but not in the engine. Deploy the decision with the module,
or keep the tenant isolation of `by-adapter`.

## Keeping workflow modules apart

The [name-clash-avoidance mode](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-modules#how-name-clashes-are-avoided)
decides how the identifiers of a workflow module are scoped. `by-adapter` deploys into a
Camunda tenant named after the workflow module. `tenant-id` overrides the name, for the whole
adapter or for one workflow module. This is the Version-1 behaviour. `use-prefix` deploys
without a tenant, and the adapter rewrites process ids, `camunda:calledElement` and
`camunda:decisionRef` references, message and signal names, escalation and error codes.
`none` scopes nothing.

Two decisions worth recording:

- **The default is `by-adapter`, the tenant per workflow module.** Version 1 deployed this way.
  So an application which upgrades without touching its configuration finds its
  running workflows again. And it costs this engine nothing, because a tenant id is an attribute
  of the deployment (see below). The default was `none` between 2026-08-11 and
  2026-08-22, which broke exactly that upgrade path. Where an application
  chooses `none`, the adapter WARNs per workflow module and names all three ways of
  keeping modules apart. It does so until `accept-unscoped-identifiers` confirms that the
  identifiers are unique. That confirmation is a statement about the application, not a
  log level. So it is not simply a logger configuration.
- **A BPMN error code belongs to one workflow module, and so does its catcher.** The code a
  `TaskException` raises is built from the module of the process whose task raised it
  (`Camunda7WorkflowTaskBehavior`). The codes in a model are rewritten with the module
  whose file declares them. So for every call activity this adapter can see, both sides of a
  throw and its catcher are the same module. Under `use-prefix` a static `camunda:calledElement`
  gets this module's prefix. Under `by-adapter` the engine looks for the
  called process in the tenant of the CALLING instance. One attribute leaves the module:
  `camunda:calledElementTenantId`. A model which writes it gets a warning while the
  application starts. The error the called process raises carries the other module's prefix,
  the boundary event waits for this module's prefix, and the called workflow fails with an
  incident. The warning is the whole answer. The code is not built from the caller's module
  instead. That would be a second rule for one identifier, and both processes would
  carry a name neither asked for. `Camunda7CrossModuleCallActivityTest` tests the message.
  A called element given as an expression is the application's own string, which no
  deployment can resolve.
- **Task definitions are NOT prefixed**, unlike on Camunda 8. A Camunda 7 task definition
  is the expression text of the task (`camunda:expression`/`camunda:delegateExpression`)
  or the `camunda:formKey`. VanillaBP's EL (expression language) resolver resolves it WITHIN
  the process. Nothing subscribes to it engine-wide, so there is nothing to clash with.

`Camunda7ScopingTest` tests what each mode rewrites (`usePrefixRewritesEngineWideIdentifiers`,
`usePrefixKeepsTaskDefinitions`, `otherModesDoNotTouchTheModel`,
`callActivityByExpressionStaysEvaluable`). `Camunda7DeploymentServiceTest#defaultsToByAdapter`
and `#unscopedIdentifiersAreReported` test the default and the WARN.
`Camunda7NameClashAvoidanceIT` runs a workflow end to end with prefixed identifiers.

### Whether the tenant separates two workflow modules

One name clash loses a model: two workflow modules of one application which use the same BPMN
process id. The core refuses it. Under `by-adapter` nothing is prefixed. So the
core holds two equal process ids and cannot tell whether they collide. There the engine keeps the
modules apart, and the mechanism belongs to the adapter. So the core asks
(`ownIsolationSeparatesWorkflowModules`). This adapter answers by resolving the tenant of
each of the two modules, exactly as the deployment resolves the tenant it deploys under. Then it
compares the names.

The answer is about the engine, not about a property. Two things follow from that. First, a
tenant name for the whole adapter (`vanillabp.adapters.<id>.tenant-id`) puts every module into ONE
tenant. So two modules which share a process id really do collide, and the boot of the second one
ends. No check could do that while the core had to judge two plain ids on its own. Second, no
tenant is a scope like any other. A module under `use-prefix` or `none` reaches the engine without
one. The engine does not separate two such modules, but it does separate one of them from a module
with a tenant. For this reason, and for the message, the name resolves per workflow module. See
decision 19 in the repository's DECISIONS.md.

The name is resolved per workflow module. The module's own section comes first
(`vanillabp.workflow-modules.<module>.adapters.<id>.tenant-id`), the adapter's after it. There
is no name per workflow, because a tenant id is an attribute of the deployment, and this adapter
makes one deployment per workflow module. A name which the mode would ignore still ends the boot.
In every case the message quotes the key which set it, not always the adapter's key.

A tenant id is an ATTRIBUTE of the deployment and of the process definitions, instances
and tasks below it. Any name is accepted. No tenant has to exist, and none is created.
Registered tenants (`ACT_ID_TENANT`, written by `IdentityService#newTenant`) exist for
tenant memberships and the authorizations built on them. Most applications have none.
An application may register tenants, but not the one VanillaBP deploys into. Then the adapter
WARNs, because the deployment works, but nobody is authorized for those workflows
(`Camunda7TenantCheckTest#unregisteredTenantIsReported`, with
`#noRegisteredTenantsStaySilent` for the ordinary application).

Without a tenant the engine cannot answer which workflow module a running instance belongs
to. The adapter resolves it from the process definition key it registered while wiring.
That keeps everything working which depends on it. This includes the live evaluation of
workflow-aggregate attributes in BPMN expressions.

### What the boot asks about the names

Scoping keeps the workflow modules of THIS application apart. It says nothing about a name
which another application deployed into the same engine years ago. In that case the engine
decides on its own which side a start or a message reaches. So the boot asks, once per workflow
module and right after the deployment returned. The core turns the answer into a WARN.

There are three questions, and each answers for what it can:

- What the engine already holds. A process definition key takes a batch filter, so one statement
  asks about every BPMN process of the module. A decision id is asked about one by one,
  because its query has no such filter. A suspended definition counts. The tenant is part of
  the question wherever the mode deploys into one.
- What the models of this deployment declare. The message names, signal names, error codes and
  escalation codes are read anyway while the module is scoped, plus the ids of the decisions it
  brings. So if two workflow modules of one application end up under one name, the WARN names
  them. This costs no query at all.
- What a version the engine still holds declares. The check about older versions reads those
  models anyway, so the same names are read from them. A name which a module deployed years ago
  is checked against what another module deploys today.

The Camunda deployment NAME tells a foreign holder apart from this application's own earlier
deployment. Every VanillaBP generation, version 1 included, deploys a workflow module under the
module's own id. So a deployment with that name is our own history and says nothing. A definition
deployed under another name is reported. The deployment source then says how sure the adapter is.
A source of `camunda7:<adapter id>` means another adapter id or another workflow module. That may
be this very application, and the warning says so. Any other source was written by something else,
and the finding is certainly foreign. A second application which deploys a workflow module of the
same id stays invisible, because no column of the engine's deployment table names an application.

The engine has no index for message names, signal names, error codes and escalation codes which
it could be asked. So nothing is said about what it holds of them. Task definitions are no
question here at all, for the reason above: they are local to a process. A finding never fails a
deployment. If the engine does not answer, every query is silently dropped. No property switches
any of this on or off (see decision 17 in the repository's DECISIONS.md).

## Sharing the workflow aggregate

This adapter shares like every other BPMS. The values of the workflow
aggregate are written as Camunda process variables, and the engine evaluates its
expressions against them. Being embedded is no reason to do it differently. A model which reads
something else works here and breaks on every remote BPMS. `@SyncWithBPMS` exists to prevent
exactly that. So the adapter's default is `AggregateSyncMode.FULL`, and an
application which wants to share less adds annotations. VanillaBP never reads the variables back.
The aggregate stays the source of truth.

The values are written at every point where the adapter talks to the engine for the application:
starting a workflow (also by message), completing a `@WorkflowTask` method
(including the BPMN-error path), completing or cancelling an asynchronous task, completing
or cancelling a user task, correlating a message, and `aggregateChanged`. The task
completion is the hard one. A gateway right behind a service task
decides on what that task just computed. So the values are written INSIDE the engine's
transaction, right after the handler returned and before the activity is left. A broadcast
signal writes nothing, because it reaches workflows of other aggregates.

An application which does nothing ends up sharing everything. So VanillaBP does not let
this pass quietly. Take an aggregate which carries neither `@SyncWithBPMS` nor
`@NoSyncWithBPMS`, anywhere in what it reaches. Its workflow refuses to start, and the
message names the aggregate and the attributes which travel. An aggregate which holds only
its id is fine, because that value reaches the engine whatever the sync model says. There are
two ways on. Tell the aggregate what the models really need, or let this one workflow share
all of it:

```yaml
vanillabp:
  workflow-modules:
    my-module:
      workflows:
        MyProcess:
          allow-full-sync-with-bpms: true
```

The permission is read at the workflow and nowhere else. The same line at the workflow module
or at the application does not apply. It gets a message which says where it belongs.
The test applications of this repository use the permission. Their aggregates hold test
data, so sharing all of it is correct. The reason is written here, because the YAML formatter of
the build drops comments written inside a mapping.

A value for which the engine has a variable type becomes a scalar variable: a short, an integer,
a long, a double, a boolean, a string, a date and bytes, plus a `Character`, which is
written as a string. Everything else keeps its class in an object variable, in the format
the application configures (`vanillabp.adapters.<id>.serialization-format`, which you can
override per workflow module and per workflow). One case is a nested value. That keeps
`${order.customer.name}` working, because the engine deserializes before EL navigates. The other
case is a number this engine has no type for: a `BigDecimal`, a `BigInteger` or a `Float`. Those are
not widened to a double, so a model reads the value the application holds, as in version 1
(see decision 16 in the repository's DECISIONS.md). Without a format the engine falls back to Java
serialization. The adapter warns about that once: Cockpit shows a blob, and the engine's database
holds serialized instances of the application's classes. And where the configured format cannot
carry a type unchanged, the deployment says so for each attribute the models read. This is
measured through the engine's own serializer, not taken from a list
(`Camunda7SerializationRoundTrip`, `Camunda7LossyFormatCheckIT`).

A format needs a dataformat plugin (camunda-xstream, SPIN). A plugin reaches an
embedded engine which this adapter builds under `vanillabp.adapters.<id>.engine-plugins`. There is
a named section per plugin with `plugin-class` and its `properties`. Camunda's
`PropertyHelper` applies them. That is the code which reads the `<property>` elements of a
`bpm-platform.xml`, so the plugin's types are converted as documented. This is the one place
which reads the same on both platforms. And it is per adapter id, which a side-by-side
migration needs. A plugin which needs more than a constructor without arguments is contributed
as a `ProcessEnginePlugin` bean instead. Those apply to every engine this adapter builds.

The one place where variables ARE read is `@TaskParam`. It takes the value from the task's
input mapping, a hand-over which the model asks for on purpose.

The format is read for the process the values are written into, not for the
process whose process service was called. A process service belongs to the process at the
top. But a task, a waiting message and a called process belong to the process which contains
them. So all of these read the format under the id of the called process: completing or
cancelling a task of a called process, pushing for it, and correlating a message the called
process waits for. The engine's own task delivery has always done the same.
`Camunda7SettingsOfACalledProcessTest` tests it (see decision 45 in the repository's DECISIONS.md).

`aggregateChanged(aggregate)` writes the shared values with `setVariables` at the process
instance. Then it writes them at the instance of every called process which continues the
aggregate. In Camunda 7 a called process is a process instance of its own, with variables of its
own. So a gateway which waits in it never saw a value written only into the caller. A called process
continues the aggregate where it carries the same business key. The walk stops at a called
process with an aggregate of its own. `aggregateChanged(aggregate, taskId)` writes with
`setVariablesLocal` at the execution
of the scope the task RUNS in. To find it, the adapter walks around two scopes:

- the scope which Camunda gives an activity of its own where the model asks for one (a task with
  a boundary event attached, one instance of a multi-instance activity). Variables
  written there serve that activity's boundary events and vanish when it ends.
- the multi-instance BODY, whose variables all instances would share.

This makes conditional events usable on Camunda 7. The engine evaluates the
condition of a waiting conditional event when a variable of its scope or of a parent scope
changes, and at no other time. So only a write at the scope the task runs in reaches
an event subprocess with a conditional start event in that same scope. Where the
application shares nothing at all, a push would carry no values and so change nothing. So
the adapter writes the technical variable `vanillabpAggregateChanged`, which holds the time of
the push.

The EL resolver serves the WIRED TASKS. It still answers attribute names as well, but only
as the **migration fallback** described above. And only where the engine has no variable of that
name: workflows started with an older version carry none, and version 1 also resolved
attributes without a getter or through an `isX()` which returns a non-boolean. Each such read
is logged once, with the way out. The fallback will be removed together with the SPI methods
behind it (`workflowAggregateHasProperty`, `resolveWorkflowAggregateProperty`). No version is
named for that, so ask the VanillaBP team if you need a date.

While the application starts, `wireBpmn` reports the expressions whose PATH stops before it
reaches a value the engine holds. The report names the element, the expression, the segment which
stops it, what the engine will do with the null, and the fix. It reads the whole path
(`order.customer.address.city`), not only its first name. The shared values are a
structure, and the sync model meets an expression at every segment. One of three things stops a
path. The segment is a readable attribute which is not shared. Or the type before it
has no such attribute at all. Or the value before it reaches the engine as one value, a
number or a text, and carries nothing below it. An enum arrives as its name, so it counts
as a text.

The message differs by PLACEMENT, not by severity, because the same null costs
very different things. A sequence flow with a default flow continues quietly. One without a
default flow raises an incident. A timer or a multi-instance collection refuses the null and says
what it wanted. A conditional event answers false and waits forever, with no incident and
no log line. A path which several elements read is reported once, with the placement which
fails most quietly. The message promises the migration fallback only where it exists, which is a
top-level name. For any other reported path it says that nothing answers it.

Where the declared types cannot decide, the check says nothing at all. Such types are a `Map`, an
interface, an abstract type, and a collection which does not say what its elements are. The check
does not judge a method call either, so the path ends before `getTotal()` and before an indexed
access. It is a WARN and never a failed deployment. The check reads expressions, and if it
misreads one, that must not keep an application from starting.

`Camunda7AggregateSyncIT` tests sharing
(`theGatewayBehindATaskReadsWhatTheTaskComputed`, `nestedValuesBecomeObjectVariables`,
`unannotatedAggregateSharesEverything`). `Camunda7VariablesTest` tests the conversion.
`Camunda7AggregateChangedIT` tests the two scopes and the conditional event
(`aConditionalEventWaitsForThePush`, `aTaskScopeIsSkipped`). `Camunda7EnginePluginsTest`
tests the plugin section. `Camunda7ExpressionIdentifiersTest` tests the paths and placements
the startup reads. `Camunda7UnsharedExpressionCheckIT` tests what a boot actually says
about the models of the expression suite.

## What a `@TaskParam` reads here

The value is the engine's answer. The conversion into the type the handler declared belongs
to the platform. `Camunda7WorkflowTaskBehavior#getTaskParameter` returns
`execution.getVariableLocal(name)`. That is the deserialized value of the task's local
variable, and the method converts nothing. What a parameter may be declared as, and which pairs
are refused, is documented once, together with the conversion, in the
[migration adapter](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/README.md),
section "What a `@TaskParam` may be declared as".

Three things about that value are Camunda 7's own.

An input mapping writes the value a SECOND time. The engine evaluates the expression and
stores the result as a local variable of the task's execution. That write uses the
engine's `defaultSerializationFormat`. This adapter fills it from the adapter level only
(`Camunda7EngineHolder`, and `Camunda7QuarkusEngineHolder` on Quarkus). The aggregate push
takes its format from the level resolved for the workflow. So if an application configures
`serialization-format` for one workflow module, the two disagree. That half is read from
the code, not measured. The runs which measured this configured the format for the adapter,
where the two agree.

The model decides what a parameter reads without an input mapping. Camunda 7 reads the
execution's own scope. So a task which sits directly in the process sees a process variable.
The same task on a branch of a parallel gateway, or inside an embedded subprocess, sees
`null`. Camunda 8 does the opposite and resolves a job's variables up the
scope hierarchy. So a model moved between the two changes what its handler gets. Declare
the input mapping, and do not rely on either.

The serialization format decides the scale of a decimal, not its value. Without a format the
engine writes Java serialization, and a `BigDecimal` of `120.50` comes back as `120.50`. With
`application/json` it comes back as `120.5`. Both are the same number, so both convert into
a `Double`, and an `int` refuses both. `AbstractParamTypesIT` runs the same cells in
both worlds. `Camunda7ParamTypesIT` and `Camunda7ParamTypesJsonIT` name the one cell
which differs.

## Signals

`ProcessService.sendSignal(name)` broadcasts through `RuntimeService.createSignalEvent`
inside the caller's transaction. So a rollback takes the broadcast with it. The signal is
scoped like every other identifier of the workflow module. It is sent for the module's tenant,
or without a tenant where identifiers are prefixed. An engine on its own datasource cannot join
that transaction. It broadcasts after the commit through the outbox, like a remote BPMS.
`Camunda7SendSignalIT#broadcastContinuesTheWaitingWorkflow` and
`#rollbackTakesTheBroadcastWithIt` test both halves.

## The start of a workflow, and workflows which ended

The adapter attaches an execution listener to EVERY start event which a process itself holds,
including the plain one and the message one. It reports the start to the core, and the core
decides what that start is. The listener runs inside the engine's own transaction. So the
workflow aggregate and the process instance commit together. A failure rolls both back,
and the engine retries.

A workflow is named by its workflow aggregate, and that name is the instance's business
key. If the instance already carries a key whose workflow aggregate exists, the workflow
is already ours, and nothing is built. That is the application's own start. If it
carries no key, somebody started it without going through VanillaBP. Then the application's
`@WorkflowStartedByBpms` method builds the aggregate and names it. The adapter writes that
name into the business key, and one INFO line says so. If it carries a key which no workflow
aggregate has, the start is refused, because only VanillaBP names a workflow.

A called process brings no key, and it is no foreign start. This engine hands a called
process no business key. So the deployment writes
`camunda:in businessKey="#{execution.processBusinessKey}"` onto a call activity whose called
process works on the aggregate of its caller. A call activity which names the process to
call in an expression has nothing to write it onto, because the model does not say which
process will be called. The listener answers it instead. It finds the call activity which
started the instance in the execution tree. It asks the core whether the two processes work on
one workflow aggregate. If they do, the called process uses the caller's name. If they
do not, nothing is inherited, and the called process gets its own aggregate, as it should.
So the same model reaches the same aggregate here as on a BPMS which copies the
caller's values by itself. `Camunda7CallByExpressionIT` runs both ways of naming the called
process against the engine. `Camunda7CalledProcessStartTest` tests the cases the listener tells
apart.

An event subprocess is left out of this. Its start event fires inside a workflow which is
already running and already has its aggregate, so nothing is started there. Only
the start events which the process itself holds count. This is true in the deployment, which
tells the core about them, and in the parse listener, which attaches the execution listener. The
engine draws the same line while it parses: a start event whose scope is no process definition
becomes a scope start event. `Camunda7EventSubprocessStartsNoWorkflowTest` tests both places. It
also lets an event subprocess take over a running workflow, to show that nothing else changed.

The kind of the start event decides none of this, and it cannot. Anybody with access to
the engine can start any of these processes, with a key or without one. So a timer start
event says nothing about who started this instance. The state of the workflow says it. Some
processes have a start event which the engine fires by itself: a timer, a signal, a condition.
Such a process ends the boot if it has no `@WorkflowStartedByBpms` method. A process with only
plain or message start events needs none, until somebody starts it without going through
VanillaBP. That start is refused, and its message names the method to write. The reasoning is
[decision 28](./DECISIONS.md#28-the-business-key-is-the-name-vanillabp-gave-the-workflow-and-only-that),
which supersedes
[decision 24](./DECISIONS.md#24-a-start-is-the-applications-own-where-the-id-already-has-an-aggregate---superseded-by-decision-28).
`Camunda7ForeignStartIT` runs every case against the engine.

The cost is one execution listener per start event in the parsed process definition,
and one load of the workflow aggregate per start of a workflow. The listener runs in the
engine's own transaction. So that load hits the persistence context the start already uses,
and the first task of the workflow reads the same aggregate a moment later anyway.

There is no number to measure here. That is worth saying, because a remote BPMS has one.
An adapter which writes the listener INTO the model before deploying it makes every start
event of every deployed process grow, and somebody can count the bytes. This adapter
attaches the listener to the element the engine PARSED. So the deployed bytes stay the
modeller's own, the engine creates no job for it, and no query answers differently because
of it. What a start does shows up in the workflow aggregate and nowhere else.
`Camunda7ForeignStartIT` reads it there. A gap with a reason is not a gap. This paragraph is the
reason why there is no cost test next to that one.

Where a workflow service declares a `@WorkflowEnded` method, the adapter attaches an END
execution listener to the PROCESS scope, again inside the engine's transaction. Camunda 7
tells the two kinds apart. An execution with a delete reason was canceled or deleted
(`CANCELED`). Everything else ran to its end (`COMPLETED`, with the id of the element it
ended at). Processes without such a method get no listener.

A modeller would call two paths a cancelation, but here they are not. A terminate end event and
an interrupting event subprocess both end the instance WITHOUT a delete reason. So the
application hears `COMPLETED`. The id reported with it is the element the instance ended
at. In the first case that is the terminate end event, in the second the event subprocess. So
an id which arrives there is not always an end event. Camunda 8 answers the same
for both paths. That is why the SPI promises no mapping of modelled paths at all.
`Camunda7WorkflowEndKindIT` tests both against the embedded engine.

Both listeners follow the datasource mode, like the task delivery does. An engine on a
datasource of its own runs its transaction where the application's persistence cannot join.
So VanillaBP opens the transaction the aggregate is written in, and the two commit one after
the other. If the engine transaction of a start fails afterwards, the start is retried and builds
the aggregate again. If the transaction of an end notification fails, it is delivered again. This
is the at-least-once shape of that mode. It is the reason why a `@WorkflowEnded` method has to
tolerate a repetition there.

`Camunda7BpmsInitiatedStartIT#timerStartCreatesTheAggregate` tests the start.
`Camunda7WorkflowEndedTest#workflowEndedIsReported` and
`Camunda7DeploymentServiceTest#servedOrUnusedWorkflowEndStaysSilent` test the end and the
processes which get no listener.

## Versions of a process

The engine counts the version of a process definition upwards per BPMN process id. A running
instance stays on the version it was started with. The adapter reports that version with
every task, user-task event, engine-performed start and workflow end. It resolves the version
ONCE per process definition id and then answers from memory. Tasks are delivered inside the
engine's transaction, so every workflow would pay for a repository query per execution.

A version boundary may also name the model's `camunda:versionTag`. To place a tag in the
deployment order, the adapter needs the engine's definition query. It runs that query once per
process while the application starts, right after the deployment, so a tag deployed by this
very start is included. After that, it runs it again only for a version it has never seen. A
rolling deployment produces such versions. What the deployment itself reported costs no query at
all: the deploy command names the version the engine assigned to every model, tag included.
`Camunda7ProcessVersionIT#theVersionDecidesWhichMethodRuns` tests the routing.
`Camunda7StartupQuestionCostTest` tests how many questions a start asks.

### A workflow is served from the model of its own version

Camunda 7 evaluates the expressions of the model a workflow was started with. Say version 2 of a
process names `${checkTheNewWay}` at an element where version 1 named `${checkTheOldWay}`. Then a
workflow of version 1 still asks for `checkTheOldWay`. So every lookup which starts from an
execution asks `Camunda7TaskRegistry#tasksOf` for the tasks of the execution's own process
definition. These lookups are the EL resolver and the two task listeners. The registry reads that
model once per definition, through the extraction `wireBpmn` runs, and keeps it.

The list of tasks per process still exists. `wireBpmn` fills it, and it answers where no
definition is at hand. It must not answer a running workflow. It holds the tasks of every model
wired under the process. Its lookup by element would hand a workflow of version 1 the task which
version 2 wires at the same id. That is what happened before, see
[decision 40](./DECISIONS.md#40-a-workflow-is-served-from-the-model-of-its-own-version).
`Camunda7HandlersOfTheOwnVersionIT` runs the upgrade against an engine.

The models of those versions are read for more than their tasks. A workflow on an older
version loses an update exactly like one on the newest model. Two tokens in one workflow are
two branches which write one workflow aggregate. Without a version attribute on that aggregate,
one of the two writes disappears without an error. So the adapter also answers which elements of
a held version can put a second token into a workflow. It uses the same walk it reports for the
model it just deployed. The core warns once per BPMN process and names the version the elements
came from. The case worth reading is a parallel gateway which the newest model dropped. The
workflows which still carry it were started before that change, and they are the ones which run
longest. `Camunda7ConcurrentTokensTest` tests the constructs and the reading of a held version.
The core asks only about a version which workflows really run on.

Compensation is the same finding drawn differently, and it is reported in a shape of its own.
`Camunda7ConcurrentTokens#compensationOf` reads every compensation throw event of a process
together with the handlers it starts. The adapter reports the ones which start more than one
handler. From that event on, the workflow holds a token per handler. A reader has to see which
event starts which handlers, not a flat list of ids. A throw event which undoes a single
activity is left out.

This engine starts the handlers one after the other, not side by side. This was
measured before the message was written. `Camunda7CompensationTokensTest` runs a real engine and
records three things. Both compensating executions exist while only one handler is inside its
delegate. The `asyncBefore` which this adapter sets on every service task never turns a handler
into a job. And the order in which the handlers run is not stable. Why the report is made anyway
is in
[decision 29](./DECISIONS.md#29-compensation-is-reported-although-this-engine-starts-the-handlers-one-after-the-other).
A version the engine still holds carries its compensation as plain element ids
among the others. The shaped message belongs to the model which somebody can still redraw.

### A compensation runs in one transaction

The same measurement answers a second question, and the answer is a warning of its own. Here every
service-like task of a model gets `asyncBefore`, and so a job and a transaction of its
own. A compensation handler is the one place where the engine does not follow. It starts the
handler outside the normal flow, where no job is created. So all the handlers of a throw event
run in the transaction of that event.

This was measured on 2026-10-01 against the pinned engine 7.24.0, with a throw event which
compensates two finished service tasks. Both handlers ran in the same command context, the
engine's own unit of work, and one commit covered both. The two activities they compensated each
ran in their own transaction. So the flags do work where the engine makes a job of an activity.
And when one handler threw, the other one had to run again. The engine rolled back the whole
compensation, took one retry off the single job, and ran both handlers again on the next attempt.

So the deployment warns once per compensation throw event and names the event and the handlers it
starts. It asks for handlers which may run twice. And it asks to keep work the engine cannot roll
back out of them. It is a warning, not a refusal: the model is correct, and no flag of this adapter
changes what the engine does. `Camunda7CompensationTransactionReportTest` tests the message,
`Camunda7CompensationTokensTest` the measurement. The
[Deviations page](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Deviations#a-compensation-runs-in-one-transaction)
lists it among the gaps.

### A process id which is only declared

A workflow module may name a BPMN process id which none of its files carries any more. This is
how a renamed process keeps being served: the new id is the primary one, and the old id is
declared as a secondary process. Nothing is deployed under the old id. So the adapter never sees
it while wiring, and only the core knows it was declared at all. So the core asks after the module
was deployed (`AdapterDeploymentService#processVersionCatalogOf`). This adapter answers with
the same catalog it answers everything else with. That costs one definition query for that id.

That reaches the check. The versions the engine still holds under the old id are read like the
older versions of any process, and the workflows which run on them are counted.

The runtime is the second half, and it needs more than a catalog. The engine evaluates the
expressions of the model a workflow was STARTED with. `Camunda7TaskRegistry` holds the
connectables behind them per process which this adapter WIRED. Nothing was wired for the old id.
So a workflow on it used to reach its next task, find nothing, and end in an incident. Now it is
wired. `startWorkflowProcessing` asks the core which ids the module declares without a model
(`WorkflowTaskWiring#taskWiringOfProcessesNobodyDeployed`). For each of them it reads the model of
every version the engine holds, straight from the engine's own repository. It uses the same
extraction a deployed model goes through. The connectables come from those models,
because the type of a task lives there and nowhere else. A `camunda:expression` task completes
when its handler returns. A `camunda:delegateExpression` task can stay open for a
`@TaskId` method. No list of task definitions says which of the two a task is. A task which two
versions share is registered once. The wiring validation does not run over those models.
The version check reports a task which the application dropped in the meantime. Ending the
boot over a model which nobody can change any more would be the wrong answer to it.
If the extraction refuses a model, it is skipped with one warning which names the version.
Nobody can change a model deployed years ago, the way they could change one which this boot brings.
[Decision 13](./DECISIONS.md#13-the-workflows-of-a-renamed-process-are-served-from-the-models-the-engine-still-holds)
has the reasoning for all of it. `Camunda7DeclaredProcessWiringTest` tests the task kinds and
the skipped version. `Camunda7RenamedProcessIT` is the acceptance test. A workflow is started
under the old id, and the application deploys only the new one. The workflow runs to its
end through the methods of that application, including its open `camunda:delegateExpression`
task.

The engine also STARTS workflows under such an id on its own. The timer of the old model's
latest version keeps firing after the rename, and its signal subscription keeps matching a
broadcast. Those workflows are full VanillaBP workflows too. The aggregate is built through
`@WorkflowStartedByBpms`. A signal start gets the plain signal name, read from the engine's
own copy of the model. The task after the start event is served, and the end reaches
`@WorkflowEnded`. The way back from the engine's definition key is registered as soon as the
core asks for the id's version catalog. The reason: whatever reads that catalog next makes the
engine parse the old definitions. The parse is the moment when the end listener is attached, or
lost for good.

The catalog answers `startEventsOfVersion` with those starts. Nothing wires the declared
id while the application boots. So nobody used to check a `@WorkflowStartedByBpms` method kept for
it, and a typo in its `id` stayed a typo while the old timer fired every night. The
adapter reads the start events of every version the engine holds under the id. It uses the same
walk as for a deployed model. The core names the method on which no held version starts. It
reads and warns, nothing more. Nobody can go back and fix a model deployed years ago.
`Camunda7StartEventsOfHeldVersionsTest` tests what is read from such a model, including the plain
signal name.
[Decision 15](./DECISIONS.md#15-a-check-reads-the-engines-models-without-asking-who-deployed-them)
explains the timing. `Camunda7DeclaredIdRuntimeIT` measures all three notifications against a
running engine under `use-prefix`.

Those workflows end like any other. So the warning about a `@WorkflowEnded` method which this
engine cannot serve is given for a declared id as well, while the id is being wired. No model of
this boot passes such an id, so nothing else would have said it. Where the engine holds no version
under the id, nothing is said at all. No version means no workflow which could end. A
misspelled declared id is for the core's check to name, together with the ids the module really
deploys.

Some deployment checks stay with the model this boot brings, and that is on purpose. Three of them
are: refusing an asynchronous task wired by expression, warning about an expression which reads
what the aggregate does not share, and refusing colliding process ids. They all judge something a
modeller can still change and deploy again. A model the engine already holds is different. What a
workflow running on one can still run into is asked of the version catalog instead.

### A suspended version counts, and how to get past it once

Deleting a process definition removes it from this engine. Then the startup check for old process
versions stops reporting about it, and nothing has to be configured. Suspending it is different.
The definition is still there, and its workflows keep running. The `@WorkflowTask` method they
miss is still missing once somebody resumes it. So the definition query names no
suspension state, and a suspended version is checked like any other one.

The exception is an application which has to be up right now, while nobody can clean up an old
version this minute. Start it with the system property
`vanillabp.ignore-suspended-process-definitions=true`, or with the environment variable
`VANILLABP_IGNORE_SUSPENDED_PROCESS_DEFINITIONS=true`. That takes suspended definitions out of the
check. Where both are set, the property wins, and only the value `true` counts. Then two warnings
appear in the log of every start. One says that the exit was taken and what it costs. The other
names the versions it hid. Neither is remembered anywhere, because they are meant to be in the way
until the switch is gone. The reasoning, including why this is not a configuration property, is
[decision 12](./DECISIONS.md#12-a-suspended-process-definition-counts-and-the-only-way-past-it-is-a-system-property).
`Camunda7SuspendedProcessVersionsTest` tests the switch and both warnings.
`Camunda7OldProcessVersionsIT#aSuspendedVersionIsStillReported` and
`#theEmergencyExitTakesTheSuspendedVersionOut` test the same against a running engine.

Two things nearby stay untouched on purpose. `Camunda7WorkflowViewer` reports the latest version
of a called process, whether it is suspended or not. The version this boot runs on is the
engine's latest one for the same reason. Both show what is there, and neither is the check.

## Camunda's web applications

The optional module `camunda7-adapter-spring-boot-webapps` serves Cockpit, Tasklist and
Admin at `/camunda` against the engines this adapter built. These web applications normally come
with Camunda's own Spring Boot starter, which brings its own engine. But VanillaBP builds and
owns the engines. So the module does two things:

1. **Camunda's engine auto-configuration is switched off** (`camunda.bpm.enabled` defaults
   to `false` here). It always builds a process engine. Then an application would
   run two engines on one datasource, and the job executor of the second would acquire the
   jobs of the first. Setting the property to `true` fails the start with a message which says
   this.
2. **VanillaBP's engines are registered with the runtime container.** The web applications look
   for engines there, not in the Spring context. When the application stops, the engines are
   removed again.

`Camunda7WebappsBootTest` tests both (`camundasOwnAutoConfigurationIsOff`,
`theEngineIsTheOneVanillaBpBuilt`, `webappsAreServed`).
`Camunda7WebappsRegistrationTest#enginesAreRegisteredAndReleased` tests the removal.
`Camunda7WebappsTwoAdapterIdsTest` tests the migration setup.

The web applications are a servlet application built on Spring. There is no Quarkus
equivalent and none is planned, so this module is Spring Boot only.

## Supported Camunda version

Camunda **7.24** is the final feature release of Camunda 7 (October 2025, LTS, long-term
support). The Camunda 7 community edition is **end-of-life**. No further community releases are
expected. This adapter pins Camunda `7.24.x`.

The pin is fixed, and this adapter has no release lines. The
[Camunda 8 adapter](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter#release-lines)
is different: its artifacts carry the cluster minor in their version. Camunda 8 needs lines,
because a new minor arrives every six months. And the client a build was compiled against is the
lowest cluster version it accepts. Camunda 7 has no next minor. What is still coming are enterprise
environment update releases twice a year until April 2030. And the engine runs embedded, so
the version an application uses is the version it ships. So `renovate.json` holds
`7.24.x`, and anything above it needs a human. That is also why there is nothing to
gate.

The fork adapters for Operaton and CIB seven come as repositories of their own. So they
bring whatever versioning their forks need.

Camunda 7 runs **embedded** inside the application's JVM and normally shares the database
of the business code. So engine queries are immediately consistent, and phase one of every
operation needs exactly that. Phase two still runs after the caller's
commit, through the outbox, the way a remote BPMS works. See
[decision 2](./DECISIONS.md#2-a-workflow-is-progressed-after-the-callers-commit).

## Quarkus (JVM mode only!)

VanillaBP and the adapter are both Quarkus extensions, so you must add both
explicitly:

```xml
<dependency>
  <groupId>io.vanillabp</groupId>
  <artifactId>vanillabp-quarkus-integration</artifactId>
</dependency>
<dependency>
  <groupId>org.camunda.community.vanillabp</groupId>
  <artifactId>camunda7-adapter-quarkus</artifactId>
</dependency>
```

The extension wires the **plain Camunda 7 engine**. It uses
`JakartaTransactionProcessEngineConfiguration`, which ships with the engine, on the application's
Agroal datasource with the Narayana transaction manager. Camunda's own Quarkus extension is not
used, because it is locked to older Quarkus releases. Engine commands join the caller's JTA
transaction, so the in-transaction guarantee holds like on Spring Boot. Schema
operations run in their own JTA transaction, because Agroal has no deferred enlistment.

**The Quarkus extension is JVM-mode only. Native images are not supported.** The
engine stack (MyBatis, JUEL, scripting, creating delegates by reflection) uses a lot of
reflection. Camunda never supported native images, and neither do the forks.

Configuration keys are IDENTICAL to the Spring Boot module (`database-schema-update`,
`history-time-to-live`, `data-source-name`). `data-source-name` names a
Quarkus datasource declared under `quarkus.datasource.<name>.*`. On Spring Boot it
names a `DataSource` bean. In both cases the application or the runtime provides the
datasource. VanillaBP never builds a pool:

```yaml
quarkus:
  datasource:            # the application's default datasource (aggregates + engine)
    db-kind: postgresql
    ...
    legacy:              # a second, named datasource for the OLD engine
      db-kind: postgresql
      ...
vanillabp:
  adapters:
    c7:
      type: camunda7     # runs on the default datasource (in-transaction guarantee)
    c7-legacy:
      type: camunda7
      data-source-name: legacy   # its own schema, and its own transaction: tasks are
                                 # delivered at least once there, see the caveat above
```

An unknown `data-source-name` fails the boot with a guiding message, and so do two adapter ids
which share one datasource (`Camunda7UnknownDataSourceNameTest`,
`Camunda7SameDataSourceValidationTest`). `Camunda7TwoEnginesTest` runs the two engines
side by side. Native mode has no test, because there is nothing to test: the extension is
not registered for it.

## Viewing workflows

The embedded engine answers `ProcessService#getProcessDefinitions`, `#getBpmnXml` and
`#getWorkflowHistory`. It uses `RepositoryService` (every deployed version including its BPMN XML)
and `HistoryService` (instance timeline, incidents). Both are cheap local queries. There is
no eventual-consistency lag and no application-version boundary.

- The workflow is addressed by **business key** (aggregate ID) plus the scope its workflow
  module is deployed in. Under `by-adapter` that is a tenant named after the module. Under the
  other two modes there is no tenant at all. The adapter-native process definition id is Camunda's
  own (`MyProcess:1:8a9c…`), so the exact version an instance runs on is reported.
- `getProcessDefinitions` also reports the definitions which the process' **call activities**
  called. That is the version the called instance runs or ran on. It is read from the history,
  and at history level `none` from the called instances which still run. Where calls of one call
  activity ran on different versions, the most recent call counts. A call activity which has
  not called anything yet reports the version it would call next. That is the latest deployed
  version of the called process id in the same tenant. Call activities which address their
  process by expression are skipped, because it is only known at runtime.
  `Camunda7ViewerOfACalledProcessTest` tests both cases.
- The history context of an executed call activity is the **called process instance id**. A
  context which does not belong to the workflow is rejected and logged. That holds at history
  level `none` as well. There the running called instance is checked against the root of the
  workflow.
- Camunda's detailed activity types are mapped onto the SPI's `WorkflowElementType`.
  `error` carries the message of an OPEN incident of that activity.
- **History level matters:** with history level `none` no element history exists. Then the
  adapter reports the definition and a `null` element history, and it does not fail. Ended
  workflows stay viewable until the `history-time-to-live` cleanup removes them. After that the
  core raises the guiding `WorkflowNotFoundException`.
- Because history is queried, this adapter also reports ENDED workflows as `COMPLETED` to
  VanillaBP's BPMS election (not as "unknown"). This makes viewing ended workflows work. It also
  keeps a re-dispatched start from starting a second instance of a workflow which already ran to
  its end. For a re-dispatched start, only the workflows started since the start was planned
  count. See "What a retried start counts" above.

`Camunda7ViewerApiIT` tests the read path against the engine
(`endedWorkflowsStayViewable`, `processDefinitionsIncludeCalledProcesses`,
`historyReflectsExecutionAndOffersSecondaryContext`, `unknownSubjectsRaiseGuidingErrors`).
`Camunda7ViewerApiTest` tests the same on Quarkus.
`Camunda7WorkflowVisibilityTest#embeddedEngineReportsNoVisibilityDelay` tests that there is no lag.

## What this adapter says about a value type

The platform refuses to start a workflow whose values may not arrive as what they were. It asks
every adapter of that workflow what its BPMS does with a type
(`MigratableProcessService#whatThisBpmsDoesWith`). `Camunda7ValueTypes` is this adapter's answer.

Camunda 7 keeps a value in a variable of its own type where it HAS a type for it: the texts, the
boolean, the numbers of the JDK (Java Development Kit), a date and a byte array. A number with
arbitrary precision is not among them. So a `BigDecimal` is written in the serialization format
configured for the workflow, and an expression reads back what that format answers. That is
reported as changed in both directions, and the outbound and the inbound sentence say different
things. Outbound, the model reads what the format produced. Inbound, the number arrives as the
declared type, without the scale it was written with.

Anything else is answered with "cannot say", which never ends a startup. What a format really
costs is measured, not guessed, by `Camunda7SerializationRoundTrip`. The deployment reports the
measurement.

## Decision log

Decisions which several places in this repository rely on live in [`DECISIONS.md`](./DECISIONS.md).
It is the one thing the code may cite. A citation reads `see decision 3 in the repository's
DECISIONS.md`. Numbers are never reused. An overturned entry stays and names its successor. So
a citation written today still resolves in a year.

## Known deviations

This section lists what this adapter does not deliver. The wiki's
[Deviations](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Deviations)
page repeats each in one sentence. An engine on its own datasource is NOT one of them. It is the
documented mode described under [Transaction caveat](#behaviour) above.

### External tasks

A service task wired by `camunda:topic` is not served. The adapter delivers tasks through
the engine's own execution (`camunda:expression`/`camunda:delegateExpression`, see
[Task processing](#task-processing-execution-model)). The external-task API is a
second delivery mechanism with its own lock, retry and completion model. VanillaBP does not
need it for asynchronous work. A `@TaskId` method leaves the task open, and
`ProcessService#completeTask` completes it later. Such a task is wired by
`camunda:delegateExpression`. For an engine which is only reachable over its REST API, a separate
adapter `camunda7-external` is planned.

Deploying such a task is refused with a guiding message. The exception: the application says that
something else serves it, for example a worker which polls the topic. It does so with
`implemented-externally=true` for the task, named by its element id or by its topic. Then the task
passes, and nothing of this adapter subscribes to the topic. If such a task shows up in a version
the engine ALREADY holds, the adapter gives a warning which names the version instead. That model
is only read for the startup check about older versions, and nobody can change it any more. See
[decision 15](./DECISIONS.md#15-a-check-reads-the-engines-models-without-asking-who-deployed-them).

## Known issues

- **`camunda-bpm-spring-boot-starter:7.24.0` is incompatible with the Spring Boot 4.1
  baseline.** VanillaBP Version 2 builds on Spring Boot 4.1.0. The Camunda 7.24
  Spring Boot starter targets Spring Boot **3.5.5** (`version.spring-boot` in
  `org.camunda.bpm:camunda-parent:7.24.0`). Its auto-configuration is compiled against
  Spring Boot 3.x APIs which moved or were removed in Spring Boot 4. So the
  `spring-boot` module wires the embedded engine itself (see
  [Embedded-engine wiring](#embedded-engine-wiring)) with
  `org.camunda.bpm:camunda-engine-spring-6` and does **not** use the starter.

## Building

First install the prerequisites into the local Maven repository, in this build order:
`spi-for-java` → `adapter-platform-integration` → this repository. Then:

```bash
mvn spotless:apply
mvn install
```

The engine runs embedded on an in-memory database, so the tests need neither Docker nor a network.
What a pull request needs beyond a green build is in [`CONTRIBUTING.md`](./CONTRIBUTING.md).

A build also runs once a night, without anybody pushing. The workflow *Nightly against the platform
snapshot* (`.github/workflows/nightly-platform-snapshot.yaml`) builds what a pull request builds,
with `--update-snapshots`, against whatever the platform published since yesterday. A red night
opens an issue labelled `nightly-platform-snapshot`. Without it, a platform snapshot can break this
repository, and nobody sees it until the next pull request. On the 26th of September 2026 the
platform published at 12:19, and the last build here had run at 10:15. The break showed up a day and
a half later inside somebody's pull request. The night writes down which platform snapshot it
resolved, with the timestamp and the build number. So a red night tells whether the platform broke
or this repository did.

Every push to `main` publishes a snapshot to the GitHub Packages registry of this repository,
`https://maven.pkg.github.com/camunda-community-hub/vanillabp-camunda7-adapter`. A release works
like for every Camunda Community Hub extension: publish a GitHub Release whose tag is the version, for
example `2.0.0`. The workflow `.github/workflows/deploy.yaml` then runs
`community-action-maven-release`. It deploys through the profiles of `community-hub-release-parent`
to Camunda's Artifactory and to Maven Central, and it commits the next development version to `main`.
Version 1 is maintained on the branch `maintenance/1.x` and releases from there the same way.

## Test coverage

`mvn install` builds one aggregated JaCoCo report per platform. Use `install`, not
`install verify`. `install` already runs every phase `verify` has. Naming both runs two
lifecycles per module and reports every compiler warning twice.

1. **Spring Boot** (core + Spring Boot integration) - into `test-coverage-report/spring-boot/report`
2. **Quarkus** (core + Quarkus extension) - into `test-coverage-report/quarkus/report`

On every push to the default branch, the *Publish to GitHub Packages* workflow publishes both to
GitHub Pages. Click the [platform's badge](#documentation-and-supported-platforms) to open
its report.

The build breaks below the line. `test-coverage-report/coverage-gate` is the last module of the
reactor. It reads both reports and fails whenever a platform is below its threshold in the root POM
(`coverage.threshold.spring-boot`, `coverage.threshold.quarkus`). The threshold is in percent of
covered instructions, the number the badges above show. Both properties hold 85, the same number
every VanillaBP repository gates on. That is not the target. The rule is 90 per platform, so a
report between 85 and 90 passes the build and still names a gap. The gate marks where the gap has
grown too big to carry. That is why it is never edited to make a build pass. It also compares every
module which produces a `jacoco.exec` against the two aggregates. So a module added to the build,
but not to its report, cannot go unnoticed. `CoverageGateTest` does both of those measurements.
`TestClassConventionsTest` next to it keeps every test class on the output
suppression which the printed lines below depend on.

`TestClassConventionsTest` also reads the main sources of this repository. It looks for a guiding
message whose sentence fell apart: a run of spaces between two words, or two words which a line
continuation glued into one.

The gate reports what it measured on every run, green ones included. This is the one place in
VanillaBP where a passing test prints. The angle brackets stand for the numbers of the run:

```
coverage gate | Spring Boot: <percent> % instructions (<missed> of <total> missed) | at the rule of 90 %
coverage gate | Quarkus: <percent> % instructions (<missed> of <total> missed) | <gap> points below the rule of 90 %, build breaks below 85 %
```

A build which stops at `package` never reaches the phase which writes the reports. The gate then
prints a line per platform which says that the coverage was not checked. Those two tests are
reported as skipped. They do not fail over a file the run could not have written.

Both platforms run the documented features end to end against a real embedded engine: Spring Boot in
`integration-tests`, Quarkus in `quarkus/integration-tests`. This duplication is on purpose. The
adapter core is platform-neutral. But a correct core says nothing about whether a platform's glue
ever calls it. So a core line which a platform never reaches names a feature that platform never runs.

The two platforms still reach different numbers. One suite can produce some things the other
cannot. The startup check for old process versions needs several boots against one database, each
with a different model. A Quarkus prod-mode test boots its application once per test class. The
class comment of the Quarkus suite lists that case and the three other cases it does not repeat on
purpose. Everything else is within a point or two of the Spring Boot numbers.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
