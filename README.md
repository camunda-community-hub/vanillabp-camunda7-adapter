![Header](./readme/vanillabp-headline.png)

# VanillaBP adapter for Camunda 7

[![](https://img.shields.io/badge/Lifecycle-Incubating-blue)](https://github.com/Camunda-Community-Hub/community/blob/main/extension-lifecycle.md#incubating-)
[![Apache License V.2](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

This is the [VanillaBP](https://www.vanillabp.io) Version 2 adapter for
[Camunda 7](https://camunda.com/), the embedded workflow engine.

Developers who want to **use** this adapter should refer to the
[Wiki](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki); the VanillaBP concepts it builds
on are documented in the [VanillaBP Wiki](https://github.com/vanillabp/adapter-platform-integration/wiki). This
`README.md` is aimed at contributors.

> **Status: Version 2, in development.** BPMN deployment, starting workflows (including
> the starts the engine performs on its own), task processing (`@WorkflowTask`),
> completing/canceling asynchronous tasks, user tasks, message correlation, signals, the
> end-of-workflow notification, the aggregate sync (`@SyncWithBPMS`, `aggregateChanged`),
> the viewer/history API, process versions and the BPMS-election awareness probes are
> implemented, all of it on the embedded engine and in the caller's transaction. What this
> adapter does NOT deliver is listed under [Known deviations](#known-deviations).

## Documentation and supported platforms

This adapter runs on both platforms VanillaBP supports:

1. **Spring Boot**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fcamunda7-adapter%2Fspring-boot-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/camunda7-adapter/spring-boot-report)
2. **Quarkus**<br>[![Coverage](https://img.shields.io/badge/dynamic/regex?url=https%3A%2F%2Fvanillabp.github.io%2Fcamunda7-adapter%2Fquarkus-report%2Findex.html&search=Total.*%3F.([0-9]%2B)[^0-9]*%3F%25&replace=%241%25&flags=m&label=Coverage&color=green&cacheSeconds=60)](https://vanillabp.github.io/camunda7-adapter/quarkus-report)

Coverage is measured separately per platform - a platform's tests never cover the other
platform's code. Click a badge to open the respective report.

## Coordinates

The adapter is built on top of `adapter-platform-integration` (the platform-neutral
migration adapter plus the Spring Boot integration). The mechanisms this adapter plugs into are
drawn there rather than here, in the section of `migration-adapter/README.md` which describes each
of them; [`diagrams/README.md`](https://github.com/vanillabp/adapter-platform-integration/blob/main/diagrams)
lists them. Camunda 7 appears in most of the pictures as one branch beside Camunda 8 and the
Process-Engine-API, which is the comparison this repository cannot draw on its own.

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

The adapter is a VanillaBP adapter of type `camunda7`. Configure an adapter instance by
giving it an id and pointing its `type` at `camunda7`:

```yaml
vanillabp:
  adapters:
    c7:
      type: camunda7
  prioritized-adapters:
    - c7
```

The same type may be configured under several ids (e.g. two Camunda 7 engines side by
side during a migration) - **each id gets its OWN embedded engine** (named
`vanillabp-camunda7-<id>`). Since two embedded engines must never share one database
schema (they would be the same engine state), every additional id needs either a
datasource of its own or a
[table prefix of its own](#two-engines-on-one-database-table-prefix) - the boot fails with
a guiding message otherwise. `Camunda7AdapterBootTest#twoAdapterIdsOfTypeCamunda7YieldPerIdEnginesAndBeans`
and `#twoAdapterIdsSharingTheSameDataSourceFailWithGuidingMessage` hold both halves on Spring
Boot, `Camunda7SameDataSourceValidationTest` on Quarkus, and `Camunda7InstanceIdentityTest`
the comparison itself.

Per-adapter-id settings (all optional, at the canonical location
`vanillabp.adapters.<id>.*`):

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
`defaultCandidate = false` so it stays out of by-type injection and Spring Boot's
default-datasource auto-configuration stays active (the standard pattern for
additional application datasources):

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
  (`<bpmn:process isExecutable="true">`; a file may contain several) of each workflow
  module and deploys them as a single Camunda deployment. Where the deployment lands is
  decided by the name-clash-avoidance mode, see
  [Keeping workflow modules apart](#keeping-workflow-modules-apart). Duplicate filtering
  is enabled, so unchanged models are not redeployed on every boot.
- **Starting a workflow (two-phase).** The workflow-aggregate ID becomes the Camunda
  **business key**, and the tenant is whatever the
  [name-clash-avoidance mode](#keeping-workflow-modules-apart) says. Phase one asks the
  engine, phase two creates the instance after the caller's commit, dispatched by the
  phase-two outbox and skipping an instance which is already there (see
  [decision 2](./DECISIONS.md#2-a-workflow-is-progressed-after-the-callers-commit)). An application
  using this adapter therefore needs a phase-two outbox, which the VanillaBP platform
  integration provides for JPA/JDBC and MongoDB setups.
- **Asynchronous continuations (job executor).** Each engine runs an idiomatic
  `SpringJobExecutor` on a managed thread pool (thread names contain the adapter id); on
  Quarkus the engine brings its own pool. Activation is deferred: the executor starts when
  the deployment pipeline starts workflow processing (after the application is ready) and
  stops on graceful shutdown once the last workflow module stopped - before the engine
  closes. A job which is due now is picked up right after the commit which wrote it,
  because the engine hints its own executor for those. A job due LATER waits for the next
  acquisition cycle unless the adapter id asks to wait for the due date instead, see
  [An idle engine lets go of its database](#an-idle-engine-lets-go-of-its-database).
- **Adapter ids with an OWN (named) datasource.** An engine on a named datasource
  cannot join the caller's transaction at all - its commands commit on an
  adapter-internal transaction manager bound to that datasource. Outbound nothing
  changes for them: every progressing operation runs after the caller's commit anyway.
  Inbound both halves of the delivery contract change. The handler cannot run in the
  engine's job transaction, because the application's persistence has no part in it, so
  VanillaBP opens the transaction the workflow aggregate is saved in and that one
  commits first. A job which fails afterwards is handed out again with the handler's
  work committed, which is the at-least-once window every remote BPMS has. So such an
  adapter id answers `deliversTasksAtLeastOnce()` with `true` and names each delivery by
  the id of the job at hand, and the core answers the repetition from its delivery log
  instead of running the `@WorkflowTask` method a second time (see
  [decision 6](./DECISIONS.md#6-a-task-handler-runs-inside-the-engines-own-job-transaction)).

The four bullets have their tests: `Camunda7StartWorkflowIT` for the deployment and the
two-phase start, `Camunda7JobExecutorLifecycleIT` and its Quarkus twin for the executor
which runs only while processing is started, and `Camunda7RepeatedDeliveryIT` for the
difference the datasource makes
(`repeatedDeliveryOnAnOwnDataSourceIsAnsweredFromTheRecord` against
`repeatedDeliveryOnTheSharedDataSourceRunsTheHandlerAgain`). What the startup says about a
missing delivery log in each mode is `Camunda7MissingDeliveryLogIT`.

### An idle engine lets go of its database

The Camunda 7 job executor does not wait, it polls. Every 5 seconds, widening to 60 while
nothing happens, and every one of those cycles is a database command. An application which
spends most of its life waiting for a timer pays for that, and on a database billed by
active use it is a bill for doing nothing.

One key per adapter id changes it:

```yaml
vanillabp:
  adapters:
    c7:
      sleep-until-something-is-due: true
```

A cycle which found nothing then asks the engine for the one job with the earliest due date
still ahead, and waits until exactly that moment. No job at all means it waits until somebody
wakes it, and every engine command asks its own commit to do that waking. So starting a
workflow, correlating a message, completing a task, sending a signal and a continuation the
engine wrote for itself all shorten the wait, and none of them is named anywhere in the
adapter.

Careful with what this is. It is not the immediate wake-up version 1 sold as the feature,
because the engine has always hinted its own executor after a commit which wrote a job due
now. And it is not a longer polling interval, which would make a timer late without making
the polling stop.

One thing is left for the operator. Where the key is on, the adapter asks the engine to
acquire jobs by due date, since waking for the earliest due job and then acquiring in an
unrelated order picks the wrong job as soon as more are due than one cycle takes. That order
wants a database index on the due date of `ACT_RU_JOB`, which Camunda
[documents](https://docs.camunda.org/manual/7.24/user-guide/process-engine/the-job-executor/)
and which only the operator can create in their database. The startup message asks for it.
The engine's `jobExecutorPreferTimerJobs` is left as it is, because a preference between
kinds of job is a different question from when to wake up.

The engine's metrics reporter goes quiet as well. It owns a timer of its own and writes its
counters through a database command every 900 seconds, which would wake this engine four
times an hour. Where the key is on, that writing stops and the counters are still kept in
memory. An application which needs them written sets `db-metrics-reporting: true`, and the
startup message says what that costs.

VanillaBP's own machinery keeps polling, though: the phase-two outbox asks its store every
`vanillabp.outbox.poll-interval`, 10 seconds by default, and the retention cleanup of the
task delivery log runs once an hour. Anybody who switches this on and watches their database
sees those two, so the startup message names them rather than leaving somebody to look for
them in the engine.

What a lost wake-up can cost at worst, on one node, is the acquisition cycle which is
already running. A wake-up which arrives after a cycle read the database is kept and ends
that cycle's wait, so it is never paid for with the wait itself. Camunda's own loop drops
such a wake-up and pays one idle interval for it; here the same loss would have been the
whole wait, up to a year where no job is due at all, which is why this adapter closes that
window. The reasoning is [decision 23](./DECISIONS.md#23-a-wake-up-which-arrives-while-the-next-wait-is-decided-still-ends-that-wait).

A second application writing jobs into the same engine is not covered. Its commit runs in
another process and reaches nothing here, so a node waiting on a due date computed before
that write learns about the job when its own question next runs. Two applications against one
engine is not a setup this adapter supports.

The reasoning is [decision 18](./DECISIONS.md#18-an-idle-engine-is-told-when-to-wake-up-instead-of-asking-whether-it-is-time-yet).
`Camunda7DueDateSleepTest` holds the waiting rule against a real engine and counts the
connections an idle one takes, `Camunda7WakeupInTheGapTest` holds that a job written while
the next wait is being decided still runs, and `Camunda7SleepingEngineIT` with its Quarkus
twin holds that a booted application on either platform takes none while nothing is due.

### Embedded-engine wiring

The `spring-boot` module builds the engine(s) itself from
`org.camunda.bpm.engine.spring.SpringProcessEngineConfiguration` (shipped by
`org.camunda.bpm:camunda-engine-spring-6`, whose Spring dependencies are `provided`, so
the application's Spring Boot 4.1 / Spring Framework 7 is used). The
`camunda-bpm-spring-boot-starter` is deliberately **not** used (see
[Known issues](#known-issues)). Each configured adapter id's engine:

- uses the application's `DataSource` (engine tables `ACT_*` live next to the
  aggregates) and the application's `PlatformTransactionManager` (engine commands join
  the caller's transaction — this in-transaction guarantee is the whole point of the
  C7 adapter) — UNLESS `vanillabp.adapters.<id>.data-source-name` references an
  application-provided `DataSource` bean of that name: then the engine runs on that
  bean's datasource with an adapter-internal transaction manager (see the transaction
  caveat above; the adapter never builds a pool — a missing bean fails the boot
  listing the available `DataSource` beans),
- creates/upgrades its schema on boot (`database-schema-update`, default `true`),
- runs asynchronous continuations on a `SpringJobExecutor` backed by a dedicated
  managed thread pool, activated only while workflow processing is started, and
- applies a default history time-to-live (`history-time-to-live`, default `P180D`;
  Camunda 7.24 rejects deployments of processes without one; a process may still
  override it via `camunda:historyTimeToLive`).

A `DataSource` and a `PlatformTransactionManager` must be present (a Camunda 7
application always needs a database) unless every configured id brings its own
datasource. `Camunda7AdapterBootTest#configuredAdapterWithoutDataSourceFailsWithGuidingMessage`
is that message, and `Camunda7UnknownDataSourceNameTest` the one for a `data-source-name`
naming nothing.

### What an extension may add to the engine

An extension of VanillaBP - the Business Cockpit above all - needs hooks inside the engine: a
BPMN parse listener to attach its own task listeners, and a way to learn that a workflow started
or ended. Version 1 took both from Camunda's Spring Boot starter, which this adapter does not
use. `Camunda7EngineCustomizer` (module `core`, package `io.vanillabp.camunda7.engine`) is what
replaces it:

|              Method               |                                What it contributes                                |
|-----------------------------------|-----------------------------------------------------------------------------------|
| `parseListenersBefore(adapterId)` | parse listeners running before VanillaBP's own — the model as its author wrote it |
| `parseListenersAfter(adapterId)`  | parse listeners running after VanillaBP's own — the usual case                    |
| `historyEventHandler(adapterId)`  | a handler for the engine's history events, installed next to the engine's own     |
| `customize(adapterId, config)`    | the engine configuration itself, for what the three above do not cover            |

Every method has a default, so an extension implements what it needs. A customizer is asked once
per configured adapter id — two ids are two engines. On Spring Boot it is a bean of that type, on
Quarkus a CDI bean; both engine holders collect them while they build their engine.

Two properties are worth knowing. A built-in task listener attached by a parse listener
contributed "after" runs AFTER the ones VanillaBP attaches, which is the order an extension
tracking user tasks depends on. And the history event handler is installed as a
`CompositeDbHistoryEventHandler`, so the engine writes its history exactly as before and the
contributed handler sees every event as well. `Camunda7EngineCustomizerIT` holds both on Spring
Boot, `Camunda7EngineCustomizerTest` on Quarkus.

### What an extension may ask this adapter

Camunda 7 runs embedded. An extension of this adapter runs in the same JVM, on the same
execution tree and against the same process definitions, so every fact it needs is a fact this
adapter already looked up. Where it had to read the engine's internals a second time, the two
readings drifted apart on the next engine release. The package
`io.vanillabp.camunda7.api` (module `core`) is what it asks instead. This is API of this
repository, like `Camunda7EngineCustomizer`, and it is not part of the VanillaBP adapter SPI:
none of it is a mechanism another BPMS shares.

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
Spring Boot it is named `Camunda7_EngineFacts_<id>`, on Quarkus it is an entry of the produced
`List<Camunda7EngineFacts>`, and `adapterId()` is what tells two engines apart. Through it an
extension reaches `taskRegistry().resolve(tenantId, processDefinitionKey)`, which is the way
back from what the engine reports to the workflow module and the plain BPMN process id, and
`definitionOf(processDefinitionId)`, which answers the deployed version out of the cache a
running workflow already paid for. How an operator reads that version is
`DeployedProcessVersion.displayVersion()`, written by the platform so that every BPMS spells one
deployment the same way.

Two sentences are worth reading on the types themselves before using them. The registry fills up
while the deployment pipeline runs, so it is complete for a workflow module once `wireBpmn` ran
for it and complete for the application once the pipeline finished; asking earlier asks a
registry which is still being filled. And a form key which is an expression resolves to its own
text, `${formOf(task)}` staying `${formOf(task)}`, never to the value the engine computes from
it: the computed value differs per workflow instance, so one task would otherwise reach a
consumer under as many identities as it has instances.

`joinsTheApplicationTransaction()` deserves its own paragraph, because it was answered wrongly
once. It is true while the engine runs on the application's own data source, and false once the
adapter id was given one of its own through `vanillabp.adapters.<id>.data-source-name`. That
holds on Quarkus as well, where the engine is built on the container's transaction manager: a
JTA transaction around two independent data sources is still two commits, and this adapter
already treats such an engine as separate everywhere else. `Camunda7EngineFactsOnQuarkusTest`
pins it on that platform, `Camunda7EngineFactsIT` and `Camunda7AdapterBootTest` on Spring Boot.

### The multi-instance levels this adapter reports

`Camunda7MultiInstances` walks the execution tree upwards and collects every multi-instance
activity on the way, outermost first. The walk takes the BPMN model of EVERY execution it stands
on rather than the one it started in, which is what lets it leave the process the task is modelled
in: an execution of the calling process has to be looked up in the calling model, and looking it up
in the model of the called process loses the level. Version 1 took the model once and lost it
there, and so did this version until the walk was given a model per execution.

The step into a calling process is taken where the called process continues the caller's workflow
aggregate, which the deployment writes onto the call activity as a `camunda:property` named
`vanillabp:sameWorkflowAggregate` (decision 22 in [`DECISIONS.md`](./DECISIONS.md)). A process with
an aggregate of its own is a business case of its own and hears nothing about the iteration which
called it.

A call activity which names the process to call in an expression has nothing to carry that note,
because the model does not say which process will be called. The walk asks the core for it while
the workflow runs, through `Camunda7TaskRegistry`, which is where the start listener of a called
process asks the same question. So the overloads of `Camunda7MultiInstances` taking the registry
cross such a call activity and the ones without it end there. Where the model spells the called
process out, the answer written while it was deployed stands and nobody asks again, which is what
the workflows standing in an older version of a process need.
`Camunda7MultiInstanceByExpressionIT` runs one model naming the same called process both ways
against the engine, and `Camunda7MultiInstancesAcrossCallActivitiesTest` holds the shapes.

One level cannot be answered at all, and the deployment says so rather than letting a handler find
out. The item of an iteration is the variable named by `camunda:elementVariable`, so a
multi-instance element whose model names none, a cardinality-based one above all, has no item to
report. The index and the total are there either way.

`Camunda7MultiInstanceItems` joins the two halves of that while `wireBpmn` runs. Per task, this
adapter walks the chain of multi-instance elements ENCLOSING it and keeps the ones which name no
`camunda:elementVariable`. The core answers `WorkflowTaskWiring#multiInstanceElementNames` for every
task wired here, which is the element ids the methods serving it declare `@MultiInstanceElement`
for. Where the two meet, the boot ends with a message naming the task, the element, the attribute
and the two ways out.

The chain and not the whole process, because a handler is handed the item of the rounds its own
element runs in and of nothing else: an element in another branch never reaches it, whatever that
element names, so refusing over such a pair would end the boot of a model which is right. This
adapter read the process until wave 118 and Camunda 8 read the chain from the start.

Neither half alone would do. An element which iterates a number of times is a model somebody meant
to write, and so is a handler which reads the index and the total only; refusing either would end
the boot of an application that does nothing wrong. Only the pairing is a defect, and it used to
show up as a `null` parameter with nothing saying why.

An element id this model does not know is no finding. The chain crosses a call activity, so a task
of a called process asks for an element of its caller, and this model is the wrong place to look for
it. The core is asked by the task definition and by the element id, because a method may name either
of the two.

The same question is asked about a version the engine still HOLDS, and there the answer travels
instead of ending anything. Nobody can redraw such a model, so the adapter puts the chain of every
task it reads, outermost first, into `BpmnTaskSpec#multiInstanceElementsWithoutAnItem`, and the core
holds it against the methods which still serve that version. A modelled listener carries its chain
too: a listener method reads its item out of the same iteration a task's method does. An adapter
which does not read the shape answers `null` there and the core asks nothing;
`Camunda7ItemsOfHeldVersionsTest` reads a held version with an item, one without and one whose
listener sits inside a round that names none.

### Two engines on one database: `table-prefix`

`vanillabp.adapters.<id>.table-prefix` sets Camunda's `databaseTablePrefix`, which is how
two adapter ids become two engines on ONE datasource - the side-by-side migration setup on
a single database. Every statement the engine issues at runtime goes through MyBatis,
which prepends the prefix, and that part works. Creating the tables is the part Camunda
leaves out, and it says so in its own API,
`ProcessEngineConfigurationImpl#setDatabaseTablePrefix`:

> the prefix is not respected by automatic database schema management. If you use
> `DB_SCHEMA_UPDATE_CREATE_DROP` or `DB_SCHEMA_UPDATE_TRUE`, activiti will create the
> database tables using the default names, regardless of the prefix configured here.

No database behaves differently here: the schema management executes the DDL files shipped
with the engine (`org/camunda/bpm/engine/db/create/activiti.<database>.create.*.sql`)
statement by statement, and those statements name the tables verbatim in every dialect.
`Camunda7TablePrefixEngineBehaviourTest` in the core module holds the record - with a
prefix and `database-schema-update: true`, the engine creates a full set of unprefixed
`ACT_*` tables and then dies on its first query against the prefixed `ACT_GE_PROPERTY`,
which is what Camunda does here.

A prefixed adapter id therefore means: its tables are there already.

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

`Camunda7TablePrefixSchema` asks about that before the engine is built, so a wrong
configuration costs neither a MyBatis stack trace nor a set of stray tables in the shared
database (`Camunda7TablePrefixSchemaTest`, which walks the creating values, the missing
tables and the prefix naming a schema). A prefix together with a creating `database-schema-update` ends the boot, and so
does a prefix whose tables are missing; both messages name the prefix, the datasource, the
missing tables and the two ways on. `Camunda7TablePrefixIT` runs the working setup: two
adapter ids on one H2 database, one of them prefixed, both deploying the workflow module
and starting workflows which stay in their own engine.

**Why the adapter does not create the tables.** It could transform Camunda's statements,
and the integration test's `PrefixedEngineSchema` does - after two attempts which looked
right and were not. Renaming every `ACT_` renames the columns `ACT_ID_` and
`ACT_INST_ID_` along, and the first query fails on a column which is not there; taking
"ends with an underscore" for a column leaves the index `ACT_IDX_EVENT_SUBSCR_CONFIG_`
unrenamed, where it collides with the unprefixed engine of the same database. Neither
mistake shows up before something runs. Carrying that rename for seven engine components,
six dialects and every engine upgrade would make VanillaBP the owner of a schema whose
version bookkeeping (`ACT_GE_SCHEMA_LOG`) stays Camunda's regardless. So the rename
belongs to whoever owns the schema, applied with Liquibase or Flyway the way the wiki's
[Creating the engine tables yourself](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Configuration#creating-the-engine-tables-yourself)
describes - and where nobody wants to own it, `data-source-name` gives the adapter id a
database of its own, where the engine creates and upgrades its schema as usual.

## Task processing (execution model)

`@WorkflowTask` methods are wired to BPMN tasks by expression: implement a service
task as *Expression* `${myTaskDefinition}` (or *Delegate expression*) - the
expression text names the method's task definition (defaulting to the method
name). At deployment the adapter validates that every BPMN task of a process has a
`@WorkflowTask` method, with guiding messages, and forces `asyncBefore`/`asyncAfter`
onto service-like tasks:
every task runs in its own job transaction, aligning the embedded engine with
remote BPMS.

A business rule task calling a DECISION (`camunda:decisionRef`) is the one task the
wiring leaves alone: the engine evaluates it against a decision table this module
deployed, so there is no `@WorkflowTask` method to ask for. A business rule task wired by
an expression stays an ordinary VanillaBP task. What the decision produced reaches a
following task like any other variable, through the task's input mapping and a
`@TaskParam` parameter - `Camunda7TaskProcessingIT#aDecisionTableOfTheModuleIsDeployedAndEvaluated`
runs both rules of a table end to end.

The other direction is validated as well, and no adapter has to remember it: a
`@WorkflowTask` method which matches no task of any BPMN process of its workflow module
ends the boot naming the method and the fix. The core runs that check itself
(`WorkflowTaskWiring.validateNoUnwiredWorkflowTaskMethods`) once every adapter of the
module finished deploying - this adapter forgot to call it for a year, which is why the
duty moved (story 158). `Camunda7TaskWiringValidationIT` holds both directions.

Handlers run INSIDE the engine's job transaction (Spring-managed respectively JTA
on Quarkus, with the CDI request context activated): the workflow aggregate is
loaded by the business key, the method invoked with bound parameters and the
aggregate saved - business changes and engine state commit or roll back together.
Outcomes:

- normal return - the task completes;
- `TaskException` - the task completes with a BPMN error (error-boundary
  routing); the aggregate changes COMMIT (V1 contract - do not add your own
  `@Transactional`);
- any other exception - the job transaction rolls back and the job executor
  retries (finally: incident);
- methods declaring `@TaskId` leave the task open (asynchronous completion via
  `ProcessService#completeTask`) - such tasks have to be wired by
  *Delegate expression* (an *Expression* task completes when the expression
  returns and could never stay open).

`Camunda7TaskProcessingIT` runs every one of those outcomes against the embedded engine
(`taskExceptionRoutesErrorBoundaryAndCommits`, `technicalExceptionRollsBackAndRetries`,
`asyncTaskStaysOpen`, `asyncBeforeAndAfterForcedOntoServiceTasks`), and
`Camunda7WorkflowLifecycleTest` runs the same flows on Quarkus.

**The application does not start on that last defect.** While wiring,
the adapter asks the core whether the method serving a task completes
asynchronously (`WorkflowTaskWiring#workflowTaskCompletesAsynchronously`,
answered by the `WorkflowTaskRegistry` from the method's `@TaskId` parameter) and
aborts the deployment for every task wired by *Expression* whose method wants to
keep it open. `Camunda7TaskELResolver` keeps the same guard for a model that
reached the engine another way, and both report the identical message, which lives
once in `Camunda7TaskConnectable#asynchronousTaskWiredByExpression`
(`Camunda7TaskWiringValidationIT#asynchronousTaskWiredByExpressionAbortsBoot`). The reverse
case is deliberately silent: *Delegate expression* serves a method without
`@TaskId` just as well, because the behavior leaves the activity when the handler
returns.

The question is asked with the task definition and with the element id, the way the wiring
validation matches a method. A method names either of the two. With one key only, an application
wiring by `@WorkflowTask(id = ...)` walks past the check and meets the defect on a live workflow
instead (`Camunda7AsynchronousTaskWiringTest`). A modelled listener is the exception and is asked
by its task definition alone: an element can carry a task and a listener at once, so the element id
would answer for the task's method there and refuse a model which is right.

**Completing/canceling async tasks (`ProcessService#completeTask`/`#cancelTask`):**
the `@TaskId` value is the parked execution's ID; completing signals
that execution, canceling signals it with the adapter's cancel marker and the
behavior propagates the BPMN error (error-boundary routing). Both are two-phase:
phase one asks whether the task is still there, so a caller learns about a gone task
where it called, and phase two signals the execution after the commit, dispatched by
the outbox. A rollback therefore leaves the task open.
`awarenessOfTask` locates a task by its execution ID plus a business-key check
and a SCOPE check (see below). `@TaskEvent CANCELED` IS delivered on Camunda 7: an END
execution listener attached at parse time invokes handlers subscribing to
lifecycle events when the open task's activity is canceled (interrupting
boundary event, instance termination), within the cancellation's transaction.

**User tasks:** the user task's `camunda:formKey` is the task
definition; a matching `@WorkflowTask` method is an OPTIONAL notification handler
invoked on the engine's global CREATE and DELETE task-listener events (CREATED /
CANCELED via `@TaskEvent`, the task's ID via `@TaskId`) - attached as BUILT-IN
listeners at parse time, so they run before modeller-defined ones. The handler
never completes the task: `ProcessService#completeUserTask` maps to
`TaskService.complete`, `#cancelUserTask` to `TaskService.handleBpmnError`
(error-boundary routing), two-phase like every other progressing operation: phase one
checks the task is still there, phase two acts after the commit. `awarenessOfUserTask`
locates a task by its task ID plus a business-key check and the same scope check.

**What the awareness probes answer for:** the election
contract of `MigratableProcessService` says an adapter answers only for the scope
it is ASKED about, and a Camunda 7 business key is the workflow-aggregate id,
which is unique per aggregate type and not across an engine. The probes therefore
narrow every query by the `WorkflowScope` the core hands them: the process
definition keys its BPMN processes are known by (`processDefinitionKeyIn`,
secondary processes of the same `@WorkflowService` included) plus the tenant its
workflow module runs in (`tenantIdIn`, or `withoutTenantId` where the mode uses
none). That holds for running instances and for the history query behind a
`COMPLETED`, the two task probes verify the instance the same way in addition to
the business key, and `awarenessOfWorkflowForRedispatch` inherits it through the
SPI default. So a workflow of another workflow module, of another tenant or of a
process this application never wired is `UNKNOWN_TO_BPMS`, and the election
continues to the BPMS which really holds it. `Camunda7AwarenessScopeTest` holds every one
of those narrowings (`aForeignWorkflowIsNotClaimed`, `theTenantIsPartOfTheScope`,
`anotherModuleOfTheSameAdapterIsNotClaimed`, `aSecondaryProcessIsClaimed`,
`theHistoryIsScopedAsWell`), and `Camunda7AwarenessTest` what an unreachable engine
answers.

The write behind `aggregateChanged` answers for the same scope. It is
the half where getting it wrong costs more than a wrong answer: in Camunda 7 a
variable write is what makes the engine re-evaluate conditional events, and the
push writes a technical marker even for an aggregate which shares nothing, so an
unscoped write would ADVANCE a workflow of another module, of another adapter id
during a migration, or of another application on that database. The global-scope
branch therefore narrows by definition key and tenant like the probes do, two
instances within one scope end the operation with a message naming them instead of
a `singleResult()` stack trace, and "the workflow is gone" - the tolerated case of
an at-least-once phase two - is judged within the scope as well. The branch writing
into the scope of a parked task needs no comparison: it is addressed by an
execution id the engine handed out, which names exactly one execution. The scoped push is
`Camunda7AwarenessScopeTest#aPushReachesTheOwnWorkflowOnly`,
`#aPushInAMigrationStaysWithItsAdapterId` and `#aPushOfAnEndedWorkflowIsTolerated`.

**Message correlation:** `correlateMessage` is two-phase like every other
progressing operation (tenant = workflow module, business key = aggregate ID). Phase
one asks whether a subscription is waiting and fails the caller's transaction where
none is, so "nothing matched" stays a synchronous answer; phase two correlates after
the commit, through the outbox, tolerating a subscription which is gone by then. A
rollback therefore leaves the instance waiting. A correlation id matches via the V1
local-variable convention `<primary bpmnProcessId>-<messageName>` at the receiving
scope. `startWorkflowByMessage` uses `correlateStartMessage()` and is two-phase the
same way, with an already-started pre-check. No variables are ever set - the payload
doctrine. `Camunda7TaskProcessingIT#correlateMessageResumesProcess`,
`#correlateMessageWithCorrelationId`, `#rolledBackCorrelationLeavesInstanceWaiting` and
`#startWorkflowByMessageStartsInstance` hold this.

BPMN expressions like gateway conditions or multi-instance collections
(`${riskAcceptable}`, `${items}`) resolve against the workflow aggregate
identified by the business key (getter, boolean getter or field - Spring beans
remain resolvable on Spring Boot). External tasks (`camunda:topic`) are not
supported yet.

### Listeners somebody modelled

A `camunda:executionListener` is a place where the engine lets the application in. VanillaBP 1 let
such a listener be served by a `@WorkflowTask` method and said nothing about it, and this version
serves it again where the application asks for it.

`vanillabp.adapters.<id>.allow-listeners` is the switch, default `false`, resolvable at three levels
with the most specific configured value winning in both directions:

```
vanillabp.adapters.<id>.allow-listeners
vanillabp.workflow-modules.<m>.adapters.<id>.allow-listeners
vanillabp.workflow-modules.<m>.workflows.<w>.adapters.<id>.allow-listeners
```

There is no TASK level. That level is keyed by the task DEFINITION, and whether a listener becomes a
task at all is what this key decides, so at the moment the key is read there is no task definition to
key a level by. A value set at task level earns one guiding warning naming the three levels which
work, and the boot goes on. The Camunda 8 adapter reads the same key at the same three levels.

`Camunda7Listeners#listenersOf` reads the BPMN of a process, and `collectModelledListeners` keeps
exactly one group of what it finds: a listener written as `camunda:expression` or
`camunda:delegateExpression` whose unwrapped expression text is a task definition a `@WorkflowTask`
method names. `delegateExpression="${archiveOrder}"` is served where
`@WorkflowTask(taskDefinition = "archiveOrder")` exists, and only then.

That second half is what keeps the feature from taking something away, and it was found by a test of
this repository: `repeated-delivery.bpmn` carries
`camunda:executionListener delegateExpression="${failTheJobOnce}"` and the name belongs to a Spring
bean implementing the engine's own `ExecutionListener`. With Spring or CDI a delegate expression is
resolved against the application context, so that is an ordinary Camunda 7 model, older than VanillaBP
and none of its business. A `camunda:class` and a `camunda:script` listener are the same case without
an expression at all. None of them is refused or reported: whatever resolves them keeps resolving them.

Only the task-definition route counts, and `workflowTaskHandlerExists` is asked with it.
`@WorkflowTask(id = ...)` names the ELEMENT, and one element may carry a task and a listener at once -
`RD_Task` above carries both - so the element id cannot say which of them a method means.

A `camunda:taskListener` is not read at all, because VanillaBP notifies the `@WorkflowTask` method of a
user task itself, on creation and on cancellation.

The listeners VanillaBP attaches are none of these and nothing a modeller writes can switch them off.
The cancellation listener, the user-task listeners, the listener of a start event the engine fires on
its own and the one reporting a workflow's end are attached to the element the engine PARSED, and an
extension reaches the engine the same way, through `Camunda7EngineCustomizer` (see
[decision 14](./DECISIONS.md#14-an-extension-reaches-the-engine-through-a-customizer-not-through-the-engine)).
The collection reads the BPMN, so it sees what a modeller wrote and nothing else, and the separation
needs no prefix anybody has to keep up to date.

Where the switch is off and a model carries a served listener, `collectModelledListeners` ends the
boot. That is deliberately not left to the core's wiring validation: a connector asks VanillaBP to
leave an element alone, so the validation finds a task nothing serves and ends the boot by itself,
while a listener asks VanillaBP to serve something and without the key there is no task spec and
nothing for the validation to miss. What would happen instead is the engine evaluating the listener's
expression on its own, so a workflow reaching the element either fails on a name nothing resolves or
runs a method the application never meant for that element. The message names every listener of the
process with its element, its event and its expression, the three levels and the cost.

Where the switch is on, the listener is a task like any other one:

- it becomes a `BpmnTaskSpec`, so `validateTaskWiring` asks for a `@WorkflowTask` method and ends the
  boot where none exists, and `validateNoUnwiredWorkflowTaskMethods` reports a method which matches no
  listener of any wired process. Version 1 wired its listeners privately and had neither direction;
- `Camunda7TaskELResolver` resolves the listener's own expression. A `camunda:delegateExpression`
  yields a `Camunda7ListenerNotification`, because the engine expects a listener object there and an
  activity behavior would try to leave the element; a `camunda:expression` invokes the handler while
  the expression evaluates and answers `null`, and the listener is done when the handler returns;
- what the workflow aggregate shares is written onto the execution inside the engine's own
  transaction, exactly as for a task, whatever the listener and whatever its event. On Camunda 8
  that holds for an execution listener on `end` alone: a `start` execution listener and a task
  listener write nothing into the process instance there, so a module which runs on both engines
  behaves differently at those two placements.

The event is part of a listener's identity: one method serves one event of one element. `@TaskEvent`
receives `CREATED` when that listener fires, whichever event the modeller picked for it, because a
method without that parameter subscribes to `CREATED` alone and any other value would leave such a
method silently uncalled.

`CANCELED` is the second event a listener knows. A modelled listener fires at its own moment and at no
other, so an element taken away by an interrupting boundary event or by a terminating end event never
reaches a `start` listener and the method learns nothing. `Camunda7TaskCancellationListener` therefore
serves the listeners of an element as well as its task, and `Camunda7AsyncBpmnParseListener#parseProcess`
attaches it to the elements which carry one. The process is asked once, after the engine parsed its
whole scope, rather than per element type: a listener may sit wherever a modeller can select something,
and an element the engine has no activity for, a sequence flow being the case a modeller reaches, is
never canceled and is skipped with a debug line.

A listener the modeller put on `end` gets nothing of VanillaBP's own. This engine fires an END
execution listener when the element is canceled too, which is how the cancellation listener hears a
cancellation at all, so such a method already hears the moment and a second report would be the same
moment twice. `Camunda7Listeners#isACancellation` is that rule, and
`Camunda7TaskRegistry#registerListenerNeedingACancellation` is what the wiring writes down for the
parse listener to read.

An element which already carries the cancellation listener is left alone. Every service-like activity
does, because the transaction boundaries put it there, and a second copy would report one cancellation
twice.

Two shapes end the boot besides the missing key, each with a message naming the listener and the way
out. `refuseListenersSharingATaskDefinition` answers two served listeners of one element under ONE
expression: one method would serve both events and nothing it could ask would say which one it is in,
which is the case version 1 decided with a `findFirst`. Two listeners of one element under DIFFERENT
expressions are served, one method each. And a method declaring `@TaskId` is refused while the process
is wired: a listener is notified and done, so such a task can never stay open and the id would complete
nothing.
A method throwing `TaskException` is answered at runtime rather than at boot, because no signature
shows it: `Camunda7ListenerNotification` names the cause instead of letting the engine surface an
incident which says nothing, since the engine is inside a transition of its own and has no token to
route.

Every boot of a workflow module whose listeners are served writes one framed WARN naming the key, the
module, every served listener with its element, its event and its expression, what it costs, how a
cancellation reaches the method and the way back. Nothing silences it, see
[decision 20](./DECISIONS.md#20-a-listener-somebody-modelled-is-a-task-and-only-where-the-application-asked-for-it).
Where the switch is on and no model of the module carries a listener, the boot writes one line instead
of the frame.

### What names a delivery here, and what names an activation

A delivery identity is answered where the engine has a datasource of its own, an activation identity
always. The two look like one value under two names and are opposite contracts: a delivery identity
has to stay EQUAL while the engine repeats itself, an activation identity has to DIFFER between two
activations of one element.

On the application's datasource there is nothing to name. A redelivery proves that nothing was
committed, because the handler runs inside the engine's own job transaction, so no processed
delivery exists to remember. On an engine datasource of its own the two commits are separate, and
the delivery is named by the id of the job executing it: the engine keeps that id while it
decrements the job's retries, and the next activation of the element runs on a job of its own. Which
is exactly what the core needs to tell a repetition from new work.

A user-task notification is named in neither mode. A user task gets no job for itself - one
transaction creates every user task the token reaches - so the job at hand would name several
notifications and the second would read as a repetition of the first. What is unique per task, the
task id and the activity instance, is generated while the task is created, so the rollback which
produces the repetition produces a new value as well. A handler notified about a user task therefore
runs again after a crash, which is what it did before an own datasource could be configured.

The activation is answered by the engine in both modes:
`DelegateExecution#getActivityInstanceId()` reads `<element-id>:<instance-id>`, so the second
element of a multi-instance activity and the next iteration of a loop each get their own. The core
puts it into the idempotency key of a message correlation planned while the handler runs, which is
what keeps three siblings of one workflow aggregate from sharing a key.

Two more values travel with every delivery, and VanillaBP writes both into the delivery record: the
element id a modeller wrote on the BPMN element, and the id the engine gave the running instance.
Neither steers anything here. They are what somebody reading the record addresses the task by
outside VanillaBP, an operator searching Cockpit or an extension linking into the model. The
adapter has both at hand in every mode. The element id was read out of the model while wiring, and
the execution and the user task each name their process instance.
`Camunda7RepeatedDeliveryIT#theRecordNamesTheElementAndTheWorkflow` reads both back out of the
table.

The element id is not the task definition. A task definition here is the expression text of a
service task and the form key of a user task, so somebody looking for the element needs the
element id.

The engine datasource mode has a picture of its own, [Camunda 7 on an engine datasource of its
own](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/README.md#deliveries-vanillabp-already-processed-taskdeliverylog-spi),
which puts the two commits on a time line and shows the job the engine repeats being answered from
the record. It sits next to the core's delivery log, because that mode is what makes a repetition
visible to the core at all.

`Camunda7RepeatedDeliveryIT#deliveryRepetitionIsAnsweredFromTheDatasourceMode` holds the
delivery half in both modes. The user-task half is an assumption rather than something a
test holds: it follows from a user task getting no job of its own and from the task id
being generated while the task is created, and a Camunda 7 which reused a task id across a
rollback would disprove it.

## Outbound operations: one handler per operation

Everything this adapter sends to the engine is a `PhaseOperationHandler`, contributed
per operation in `Camunda7ProcessService.phaseOperations()`: `phaseOne` asks inside the
caller's transaction, `phaseTwo` acts after the commit. The operation itself - its
persisted name, what deduplicates it, which engine serves it, how a failure is worded -
belongs to VanillaBP's `PhaseOperation`, so an operation added later costs this adapter
one entry in that map and nothing else.

The embedded engine can answer phase one for free and in the same transaction, which is
why this adapter asks more there than a remote one can: a task which is gone, a message
nothing waits for, a correlation id no execution expects, a message start event which is
not deployed - all of them fail where the application made the call.
`Camunda7PhaseOneChecksTest` holds that.

A check which finds the task gone throws `io.vanillabp.spi.process.TaskNotFoundException`,
the type the SPI documents for a task no BPMS knows any more, and the type the platform
raises when its own probe found out. Which of the two answers is decided by whether a
delivery record exists, which only the own-datasource mode writes, and an application
cannot see that - so it must not decide what an application can catch.
`Camunda7RepeatedDeliveryIT#aStaleCompletionRaisesTheGuidingException` drives it against
an engine on its own datasource, the one setup where the record exists.

Every phase two is idempotent, because the outbox dispatches at-least-once: a start
skips an instance which already carries the business key, completing or cancelling
checks the task, and correlating checks the subscription. Each check happens BEFORE the
write, because a failing engine command marks the dispatcher's transaction rollback-only
and would take the whole dispatch with it.
`Camunda7TaskProcessingIT#awarenessAndPhaseTwoEdgeCases` walks the repeated dispatches.

### When a failed phase two is repeated, and when it is not

A phase two which failed is attempted again, because most engine failures pass: a locked
row, or the optimistic locking this adapter went two-phase for in the first place.
`Camunda7ProcessService.isPhaseTwoFailureRepeatable` answers `false` for three of them, and
the core then blocks the outbox entry instead of walking it up to
`vanillabp.outbox.block-after-attempts`.

|   what the cause chain carries   |                        why repeating cannot fix it                         |
|----------------------------------|----------------------------------------------------------------------------|
| `BadUserRequestException`        | the engine rejected the request itself, say a task id which does not exist |
| `ELException` and its subclasses | the model asked the values for something they have not got                 |
| `Camunda7RefusedStart`           | the start named a process this engine does not hold                        |

The second row is the expression language, and its subclasses are the ones for an unknown
method and for an unknown property. Camunda ships that language shaded, so the exception
is recognised by the name of the class rather than by an import: the package it sits in
belongs to the engine's own implementation, and a fork of the engine shades it somewhere
else.

Calling every expression failure permanent is a judgement. A method a flattened value has
not got never turns up, but a navigation into an attribute which is null today could read
a value tomorrow. Both count as permanent here, because an expression which fails while an
instance is created loses the instance: nothing in the engine shows it and nobody in the
application is waiting for it, so hours of attempts help no one.
`AbstractNestedExpressionsIT#aConditionalStartEventWhichThrowsBlocksTheStartAfterOneAttempt`
runs that case, the conditional start event of an event subprocess.

The third row is a start of a process nothing deployed. The engine answers it with a
`NullValueException`, which is a `ProcessEngineException` like a database which was
briefly away, so the start of a committed aggregate was attempted until its attempts ran
out and the workflow never came into being. The exception says nothing about the
operation, and for an operation on a workflow which exists the same answer is the
at-least-once residual the outbox is right to repeat. So the start marks its own refusal:
`startProcessInstance` wraps what the engine threw into a `Camunda7RefusedStart`, and only
that wrapper is permanent. `Camunda7RefusedStartTest` measures the engine's answer against
a real engine and holds the verdict.

The same class holds the other half of that measurement, the one there is nothing to do
about. A model whose only start event is a timer or a message is refused by a Camunda 8
cluster and simply STARTED by Camunda 7, at that event. An application which moves such a
workflow between the two therefore meets a running workflow here and a blocked outbox
entry there.

Everything else is repeatable, which is the way the platform asks an adapter to err.

## Decision tables

The `.dmn` files of a workflow module are deployed by the boot, in the SAME Camunda
deployment as its BPMN files: same tenant, same duplicate filtering, one version step for
process and decision together. A business rule task binding its decision to the deployment
(`camunda:decisionRefBinding="deployment"`) therefore finds it, and so does the default
`latest` binding.

Under `use-prefix` the decision ids are rewritten like the process ids, and the
`camunda:decisionRef` of the business rule tasks is rewritten with them, so both sides name
the same string (`Camunda7ScopingTest#aBusinessRuleTaskFindsItsRenamedDecision`). A
`decisionRef` given as an expression gets the prefix written in front of it, the same as a
call activity's `calledElement`: Camunda 7 reads both attributes as one expression, so
`${whichDecision}` reaches the engine as `loan-approval__${whichDecision}` and the prefixed
id of whatever the expression yields is looked up.
`Camunda7DecisionByExpressionTest` runs such a model against an engine, with and without the
prefix. What this mode cannot do is follow a reference to a decision the module does not
deploy: that one is renamed here and not in the engine. Deploy the decision with the module,
or keep the tenant isolation of `by-adapter`.

## Keeping workflow modules apart

The [name-clash-avoidance mode](https://github.com/vanillabp/adapter-platform-integration/wiki/Workflow-modules#how-name-clashes-are-avoided)
decides how a workflow module's identifiers are scoped. `by-adapter` deploys into a
Camunda tenant named after the workflow module (`tenant-id` overrides the name, for the whole
adapter or for one workflow module), which is the Version-1 behaviour; `use-prefix` deploys
without a tenant and the adapter rewrites process ids, `camunda:calledElement` and
`camunda:decisionRef` references, message and signal names, escalation and error codes;
`none` scopes nothing.

Two decisions worth recording:

- **The default is `by-adapter`, the tenant per workflow module.** It is what version 1
  deployed, so an application upgrading without touching its configuration finds its
  running workflows again, and it costs this engine nothing (a tenant id is an attribute
  of the deployment, see below). The default stood at `none` between 2026-08-11 and
  2026-08-22, which broke exactly that upgrade path. Where an application
  chooses `none`, the adapter WARNs per workflow module and names all three ways of
  keeping modules apart, until `accept-unscoped-identifiers` acknowledges that the
  identifiers are unique. The acknowledgement is a statement about the application, not a
  log level, which is why it is not simply a logger configuration.
- **A BPMN error code belongs to one workflow module, and so does its catcher.** The code a
  `TaskException` raises is composed from the module of the process whose task raised it
  (`Camunda7WorkflowTaskBehavior`), and the codes in a model are rewritten with the module
  whose file declares them. Both sides of a throw and its catcher are therefore the same
  module for every call activity this adapter can see: a static `camunda:calledElement` gets
  this module's prefix under `use-prefix`, and under `by-adapter` the engine looks for the
  called process in the tenant of the CALLING instance. One attribute leaves the module,
  `camunda:calledElementTenantId`, and a model which writes it hears about it while the
  application starts: the error the called process raises carries the other module's prefix,
  the boundary event waits for this one's, and the called workflow fails with an incident.
  The warning is the whole answer - the code is not composed from the caller's module
  instead, because that would be a second rule for one identifier and both processes would
  carry a name neither asked for. `Camunda7CrossModuleCallActivityTest` holds the message,
  and a called element given as an expression is the application's own string, which no
  deployment can resolve.
- **Task definitions are NOT prefixed**, unlike on Camunda 8. A Camunda 7 task definition
  is the expression text of the task (`camunda:expression`/`camunda:delegateExpression`)
  respectively the `camunda:formKey`, and it is resolved WITHIN the process by VanillaBP's
  EL resolver. Nothing subscribes to it engine-wide, so there is nothing to clash with.

`Camunda7ScopingTest` holds what each mode rewrites (`usePrefixRewritesEngineWideIdentifiers`,
`usePrefixKeepsTaskDefinitions`, `otherModesDoNotTouchTheModel`,
`callActivityByExpressionStaysEvaluable`), `Camunda7DeploymentServiceTest#defaultsToByAdapter`
and `#unscopedIdentifiersAreReported` the default and the WARN, and
`Camunda7NameClashAvoidanceIT` a workflow running end to end with prefixed identifiers.

### Whether the tenant separates two workflow modules

Two workflow modules of one application using the same BPMN process id is the one name clash
which loses a model, and the core refuses it. Under `by-adapter` nothing is prefixed, so the
core holds two equal process ids and cannot tell whether they collide: what keeps the modules
apart there is the engine, and the mechanism is the adapter's. So it asks
(`ownIsolationSeparatesWorkflowModules`), and this adapter answers by resolving the tenant of
each of the two modules exactly as the deployment resolves the one it deploys under, then
comparing the names.

Two things follow from answering about the engine rather than about a property. A tenant name
for the whole adapter (`vanillabp.adapters.<id>.tenant-id`) puts every module into ONE tenant,
so two modules sharing a process id really do collide and the boot of the second one ends,
which no check could do while the core had to judge two plain ids on its own. And no tenant is
a scope like any other: a module under `use-prefix` or `none` reaches the engine without one, two such modules
are not separated by the engine, and one of them against a tenanted module is. The name is
resolvable per workflow module for this reason and for the message's sake, see decision 19 in
the repository's DECISIONS.md.

The name is resolved per workflow module, the module's own section first
(`vanillabp.workflow-modules.<module>.adapters.<id>.tenant-id`) and the adapter's after it. There
is no name per workflow, because a tenant id is an attribute of the deployment and this adapter
makes one deployment per workflow module. A name the mode would ignore still ends the boot, and
the message quotes the key which set it rather than the adapter's in every case.

A tenant id is an ATTRIBUTE of the deployment and of the process definitions, instances
and tasks below it: any name is accepted, no tenant has to exist and none is created.
Registered tenants (`ACT_ID_TENANT`, written by `IdentityService#newTenant`) exist for
tenant memberships and the authorizations built on them, and most applications have none.
Where an application registers tenants but not the one VanillaBP deploys into, the adapter
WARNs, because the deployment works while nobody is authorized for those workflows
(`Camunda7TenantCheckTest#unregisteredTenantIsReported`, with
`#noRegisteredTenantsStaySilent` for the ordinary application).

Without a tenant the engine cannot answer which workflow module a running instance belongs
to. The adapter resolves it from the process definition key it registered while wiring,
which keeps everything working that depends on it, including the live evaluation of
workflow-aggregate attributes in BPMN expressions.

### What the boot asks about the names

Scoping keeps the workflow modules of THIS application apart. It says nothing about a name
another application deployed into the same engine years ago, and the engine then decides on its
own which side a start or a message reaches. So the boot asks, once per workflow module and
right after the deployment returned, and the core words what comes back as a WARN.

Three questions, and each of them answers for what it can:

- What the engine already holds. A process definition key takes a batch filter, so every BPMN
  process of the module is asked about in one statement; a decision id is asked about one by one,
  because its query has no such filter. A suspended definition counts, and the tenant is part of
  the question wherever the mode deploys into one.
- What the models of this deployment declare. The message names, signal names, error codes and
  escalation codes are read while the module is scoped anyway, plus the ids of the decisions it
  brings, so two workflow modules of one application ending up under one name are named. This
  costs no query at all.
- What a version the engine still holds declares. The check about older versions reads those
  models anyway, so the same names are read out of them, and a name a module deployed years ago
  is held against what another module deploys today.

Telling a foreign holder from this application's own earlier deployment is what the Camunda
deployment NAME is for: every VanillaBP generation deploys a workflow module under the module's
own id, version 1 included, so a deployment carrying that name is our own history and says
nothing. A definition deployed under another name is reported, and the deployment source then
says how sure the adapter is: a source of `camunda7:<adapter id>` means another adapter id or
another workflow module, which may be this very application, and the warning says so; any other
source was written by something else and the finding is certainly foreign. A second application
which deploys a workflow module of the same id stays invisible, because no column of the
engine's deployment table names an application.

Message names, signal names, error codes and escalation codes are in no index the engine could
be asked, so nothing is said about what it holds of them. Task definitions are not a question
here at all, for the reason above: they are process-local. A finding never fails a deployment,
every query is swallowed if the engine does not answer, and no property switches any of this on
or off (see decision 17 in the repository's DECISIONS.md).

## Sharing the workflow aggregate

This adapter shares like every other BPMS: the values of the workflow
aggregate are written as Camunda process variables, and the engine evaluates its
expressions against them. Being embedded is no reason to deviate - a model reading
something else works here and breaks on every remote BPMS, which is what `@SyncWithBPMS`
exists to prevent. The adapter's default is therefore `AggregateSyncMode.FULL`, and an
application which minimizes annotates. VanillaBP never reads the variables back; the
aggregate stays the source of truth.

They are written at every point the adapter talks to the engine on the application's
behalf: starting a workflow (also by message), completing a `@WorkflowTask` method
(including the BPMN-error path), completing or cancelling an asynchronous task, completing
or cancelling a user task, correlating a message, and `aggregateChanged`. The task
completion is the demanding one: a gateway right behind a service task
decides on what that task just computed, so the values are written INSIDE the engine's
transaction, right after the handler returned and before the activity is left. A broadcast
signal writes nothing, since it reaches workflows of other aggregates.

Sharing everything is where an application lands by doing nothing, so VanillaBP does not let
it pass quietly. Take an aggregate which carries neither `@SyncWithBPMS` nor
`@NoSyncWithBPMS`, anywhere in what it reaches: its workflow refuses to start, and the
message names the aggregate and the attributes which travel. An aggregate holding nothing but
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
or at the application does not apply, it is answered with a message saying where it belongs.
The test applications of this repository take the permission: their aggregates hold test
data, so sharing all of it is the truth. The reason stands here because the YAML formatter of
the build drops comments written inside a mapping.

A value the engine has a variable type for becomes a scalar variable: a short, an integer,
a long, a double, a boolean, a string, a date and bytes, plus a `Character`, which is
written as a string. Everything else keeps its class in an object variable of the format
the application configures (`vanillabp.adapters.<id>.serialization-format`, overridable per
workflow module and per workflow) - a nested value, which is what keeps
`${order.customer.name}` working because the engine deserializes before EL navigates, and a
number this engine has no type for: a `BigDecimal`, a `BigInteger` or a `Float`. Those are
not widened to a double, so a model reads the value the application holds, the way version 1
did (see decision 16 in the repository's DECISIONS.md). Without a format the engine falls back to Java serialization,
which the adapter warns about once: a blob in Cockpit, and the engine's database holding
serialized instances of the application's classes. And where the configured format cannot
carry a type unchanged, the deployment says so per attribute the models read, measured
through the engine's own serializer rather than claimed from a list
(`Camunda7SerializationRoundTrip`, `Camunda7LossyFormatCheckIT`).

A format needs a dataformat plugin (camunda-xstream, SPIN), and a plugin reaches an
embedded engine this adapter builds under `vanillabp.adapters.<id>.engine-plugins`: a named
section per plugin carrying `plugin-class` and its `properties`, which Camunda's
`PropertyHelper` applies - the code which reads the `<property>` elements of a
`bpm-platform.xml`, so the plugin's types are converted as documented. That is the one place
which reads the same on both platforms, and it is per adapter id, which a side-by-side
migration needs. A plugin needing more than a constructor without arguments is contributed
as a `ProcessEnginePlugin` bean instead; those apply to every engine this adapter builds.

The one place variables ARE read is `@TaskParam`, which takes the value from the task's
input mapping, a hand-over the model asks for on purpose.

`aggregateChanged(aggregate)` writes the shared values with `setVariables` at the process
instance, `aggregateChanged(aggregate, taskId)` with `setVariablesLocal` at the execution
of the scope the task RUNS in, which the adapter resolves by walking around two scopes:

- the scope Camunda gives an activity of its own where the model asks for one (a task with
  a boundary event attached, one instance of a multi-instance activity), because variables
  written there serve that activity's boundary events and vanish when it ends, and
- the multi-instance BODY, whose variables all instances would share.

This is what makes conditional events usable on Camunda 7: the engine evaluates the
condition of a waiting conditional event when a variable of its scope or of a parent scope
changes, and nothing else. Writing at the scope the task runs in is therefore what reaches
an event subprocess with a conditional start event sitting in that same scope. Where the
application shares nothing at all, a push would carry no values and thus be no change, so
the adapter writes the technical variable `vanillabpAggregateChanged` holding the time of
the push.

The EL resolver serves the WIRED TASKS. It still answers attribute names as well, but only
as the **migration fallback** described above, and only where the engine has no variable of that
name: workflows started with an older version carry none, and version 1 also resolved
attributes without a getter or through an `isX()` returning a non-boolean. Each such read
is logged once with the way out. The fallback will be removed together with the SPI methods
behind it (`workflowAggregateHasProperty`, `resolveWorkflowAggregateProperty`); no version is
named for that, so ask the VanillaBP team if you need a date.

While the application starts, `wireBpmn` reports the expressions whose PATH stops short of
a value the engine holds, naming the element, the expression, the segment which stops it,
what the engine will do with the null and the fix. It reads the whole path
(`order.customer.address.city`), not only its first name, because the shared values are a
structure and the sync model meets an expression at every segment. What stops a path is one
of three things: the segment is a readable attribute which is not shared, the type before it
has no such attribute at all, or the value before it reaches the engine as one value, a
number or a text, and carries nothing below it. An enum arrives as its name, so it counts
as a text.

The message differs per PLACEMENT rather than per severity, because the same null costs
very different things: a sequence flow with a default flow continues quietly, one without
raises an incident, a timer or a multi-instance collection refuses the null and says what
it wanted, and a conditional event answers false and waits for good with no incident and
no log line. A path read by several elements is reported once, with the placement which
fails most quietly. The migration fallback is promised only where it exists, which is a
top-level name; a reported path is told that nothing answers it.

Where the declared types cannot decide, the check says nothing at all: a `Map`, an
interface, an abstract type, a collection which does not say what its elements are. Nor
does it judge a method call, so the path ends before `getTotal()` and before an indexed
access. It is a WARN and never a failed deployment: the check reads expressions, and one it
misreads must not keep an application from starting.

Sharing is held by `Camunda7AggregateSyncIT`
(`theGatewayBehindATaskReadsWhatTheTaskComputed`, `nestedValuesBecomeObjectVariables`,
`unannotatedAggregateSharesEverything`) and `Camunda7VariablesTest` for the conversion,
`Camunda7AggregateChangedIT` for the two scopes and the conditional event
(`aConditionalEventWaitsForThePush`, `aTaskScopeIsSkipped`), `Camunda7EnginePluginsTest`
for the plugin section, `Camunda7ExpressionIdentifiersTest` for the paths and placements
the startup reads, and `Camunda7UnsharedExpressionCheckIT` for what a boot actually says
about the models of the expression suite.

## What a `@TaskParam` reads here

The value is the engine's answer, and the conversion into the type the handler declared is
the platform's. `Camunda7WorkflowTaskBehavior#getTaskParameter` returns
`execution.getVariableLocal(name)`, which is the deserialized value of the task's local
variable, and it converts nothing. What a parameter may be declared as, and which pairs are
refused, is documented once, with the conversion, in the
[migration adapter](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/README.md),
section "What a `@TaskParam` may be declared as".

Three things about that value are Camunda 7's own.

An input mapping writes the value a SECOND time. The engine evaluates the expression and
stores the result as a local variable of the task's execution, and that write uses the
engine's `defaultSerializationFormat`, which this adapter fills from the adapter level only
(`Camunda7EngineHolder`, and `Camunda7QuarkusEngineHolder` on Quarkus). The aggregate push
takes its format from the level resolved for the workflow, so an application which configures
`serialization-format` for one workflow module has the two disagree. That half is read off
the code and not measured: the runs which measured this configured the format for the adapter,
where the two agree.

What a parameter reads without an input mapping is decided by the model. Camunda 7 reads the
execution's own scope, so a task standing straight in the process sees a process variable
while the same task on a branch of a parallel gateway or inside an embedded subprocess sees
`null`. Camunda 8 answers this the other way round and resolves a job's variables up the
scope hierarchy, so a model ported between the two changes what its handler gets. Declare
the input mapping rather than relying on either.

The serialization format decides the scale of a decimal, not its value. Without a format the
engine writes Java serialization and a `BigDecimal` of `120.50` comes back as `120.50`; with
`application/json` it comes back as `120.5`. Both are the same number, so both convert into
a `Double` and both are refused by an `int`. `AbstractParamTypesIT` runs the same cells in
both worlds, with `Camunda7ParamTypesIT` and `Camunda7ParamTypesJsonIT` naming the one cell
which differs.

## Signals

`ProcessService.sendSignal(name)` broadcasts through `RuntimeService.createSignalEvent`
inside the caller's transaction, so a rollback takes the broadcast with it. The signal is
scoped like every other identifier of the workflow module: sent for the module's tenant,
or tenant-free where identifiers are prefixed. An engine on its own datasource cannot join
that transaction and broadcasts after the commit through the outbox, like a remote BPMS.
`Camunda7SendSignalIT#broadcastContinuesTheWaitingWorkflow` and
`#rollbackTakesTheBroadcastWithIt` hold both halves.

## The start of a workflow, and workflows which ended

The adapter attaches an execution listener to EVERY start event a process itself holds,
the plain and the message one included. It reports the start to the core, and the core
decides what that start is. The listener runs inside the engine's own transaction, so the
workflow aggregate and the process instance commit together and a failure rolls both back
for the engine to retry.

A workflow is named by its workflow aggregate, and that name is the instance's business
key. Where the instance already carries a key whose workflow aggregate exists, the workflow
is already ours and nothing is built - that is the application's own start. Where it
carries no key, somebody started it past VanillaBP: the application's
`@WorkflowStartedByBpms` method builds the aggregate and names it, the adapter writes that
name into the business key, and one INFO line says so. Where it carries a key no workflow
aggregate has, the start is refused, because VanillaBP names a workflow and nobody else.

A called process brings no key and is nobody's foreign start. This engine hands a called
process no business key, so the deployment writes
`camunda:in businessKey="#{execution.processBusinessKey}"` onto a call activity whose called
process works on the aggregate of its caller. A call activity which names the process to
call in an expression has nothing to write it onto, because the model does not say which
process will be called. The listener answers it instead. It finds the call activity which
started the instance in the execution tree and asks the core whether the two processes work on
one workflow aggregate. Where they do, the called process goes by the caller's name. Where they
do not, nothing is inherited and the called process gets the aggregate of its own it is entitled
to. The same model therefore reaches the same aggregate here as on a BPMS which copies the
caller's values by itself. `Camunda7CallByExpressionIT` runs both ways of naming the called process against the
engine, and `Camunda7CalledProcessStartTest` holds the cases the listener tells apart.

An event subprocess is left out of this. Its start event fires inside a workflow which is
already running and already has its aggregate, so nothing is started there. Only
the start events the process itself holds count, in the deployment which tells the core
about them and in the parse listener which attaches the execution listener. The engine draws
the same line while it parses: a start event whose scope is no process definition becomes a
scope start event. `Camunda7EventSubprocessStartsNoWorkflowTest` holds both places, and it
lets an event subprocess take a running workflow over to show that nothing else changed.

The kind of the start event decides none of this, and it cannot: anybody with access to
the engine can start any of these processes, with a key or without one, so a timer start
event says nothing about who started this instance. The state of the workflow says it. A
process whose engine fires a start event by itself - a timer, a signal, a condition - and
which has no `@WorkflowStartedByBpms` method ends the boot; a process with only plain or
message start events needs none until somebody starts it past VanillaBP, and that start is
refused with the method to write in its message. The reasoning is
[decision 28](./DECISIONS.md#28-the-business-key-is-the-name-vanillabp-gave-the-workflow-and-only-that),
which supersedes
[decision 24](./DECISIONS.md#24-a-start-is-the-applications-own-where-the-id-already-has-an-aggregate---superseded-by-decision-28),
and `Camunda7ForeignStartIT` walks every case against the engine.

What it costs is one execution listener per start event in the parsed process definition
and one load of the workflow aggregate per start of a workflow. The listener runs in the
engine's own transaction, so that load hits the persistence context the start already uses,
and the first task of the workflow reads the same aggregate a moment later anyway.

There is no number to measure here, and that is worth saying because a remote BPMS has one.
An adapter which writes the listener INTO the model before deploying it makes every start
event of every deployed process grow, and somebody can count the bytes. This adapter
attaches the listener to the element the engine PARSED, so the deployed bytes stay the
modeller's own, the engine creates no job for it, and no query answers differently because
of it. What a start does becomes visible in the workflow aggregate and nowhere else, which
`Camunda7ForeignStartIT` reads. A gap with a reason is not a gap, and this paragraph is the
reason there is no cost test beside that one.

Where a workflow service declares a `@WorkflowEnded` method, the adapter attaches an END
execution listener to the PROCESS scope, again inside the engine's transaction. Camunda 7
tells the two kinds apart: an execution carrying a delete reason was canceled or deleted
(`CANCELED`), everything else ran to its end (`COMPLETED`, with the id of the element it
ended at). Processes without such a method get no listener.

Two paths a modeller would call a cancelation are not one here. A terminate end event and
an interrupting event subprocess both end the instance WITHOUT a delete reason, so the
application hears `COMPLETED`. The id reported with it is the element the instance ended
at, which is the terminate end event in the first case and the event subprocess in the
second, so an id arriving there is not always an end event. Camunda 8 answers the same
about both paths, which is why the SPI promises no mapping of modelled paths at all.
`Camunda7WorkflowEndKindIT` holds both against the embedded engine.

Both listeners follow the datasource mode like the task delivery does. An engine on a
datasource of its own runs its transaction where the application's persistence cannot join,
so VanillaBP opens the transaction the aggregate is written in and the two commit one after
the other. A start whose engine transaction fails afterwards is retried and builds the
aggregate again, an end notification whose transaction fails is delivered again - which is
the at-least-once shape of that mode, and the reason a `@WorkflowEnded` method has to
tolerate a repetition there.

`Camunda7BpmsInitiatedStartIT#timerStartCreatesTheAggregate` holds the start,
`Camunda7WorkflowEndedTest#workflowEndedIsReported` and
`Camunda7DeploymentServiceTest#servedOrUnusedWorkflowEndStaysSilent` the end and the
processes which get no listener.

## Versions of a process

The engine counts a process definition's version upwards per BPMN process id and a running
instance stays on the version it was started with. The adapter reports that version with
every task, user-task event, engine-performed start and workflow end, resolved ONCE per
process definition id and then answered from memory: tasks are delivered inside the
engine's transaction, so a repository query per execution would be paid by every workflow.

A version boundary may also name the model's `camunda:versionTag`. Placing a tag in the
deployment order needs the engine's definition query, which the adapter runs once per
process while the application starts (right after the deployment, so a tag deployed by this
very start is included) and again only for a version it has never seen, which is what a
rolling deployment produces. What the deployment itself reported costs no query at all: the
deploy command names the version the engine assigned to every model, tag included.
`Camunda7ProcessVersionIT#theVersionDecidesWhichMethodRuns` holds the routing and
`Camunda7StartupQuestionCostTest` the number of questions a start asks.

The models of those versions are read for more than the tasks they carry. A workflow on an older
version loses an update exactly as one on the newest model does: two tokens in one workflow are
two branches writing one workflow aggregate, and without a version attribute on that aggregate
one of the two writes disappears without an error. So the adapter also answers which elements of
a held version can put a second token into a workflow, through the walk it reports for the model
it just deployed, and the core warns once per BPMN process naming the version they came from.
The case worth the read is a parallel gateway the newest model dropped: the workflows which
still carry it were started before that change, and they are the ones which run longest.
`Camunda7ConcurrentTokensTest` holds the constructs and the reading of a held version; the core
asks only about a version workflows really run on.

Compensation is that same finding drawn differently, and it is reported with a shape of its own.
`Camunda7ConcurrentTokens#compensationOf` reads every compensation throw event of a process
together with the handlers it starts, and the adapter reports the ones which start more than one:
from that event the workflow holds a token per handler, and a reader has to see which event
starts which handlers rather than a flat list of ids. A throw event which undoes a single
activity is left out.

This engine starts the handlers one after the other and not next to each other, which was
measured before the message was written: `Camunda7CompensationTokensTest` runs a real engine and
records that both compensating executions exist while only one handler is inside its delegate,
that the `asyncBefore` this adapter sets on every service task never turns a handler into a job,
and that the order the handlers run in is not stable. Why the report is made all the same is in
[decision 29](./DECISIONS.md#29-compensation-is-reported-although-this-engine-starts-the-handlers-one-after-the-other).
A version the engine still holds carries its compensation as plain element ids
among the others, because the shaped message belongs to the model somebody can still redraw.

### A compensation runs in one transaction

The same measurement answers a second question, and the answer is a warning of its own. Every
service-like task of a model gets `asyncBefore` here and therefore a job and a transaction of its
own, and a compensation handler is the one place where the engine does not follow: it starts the
handler outside the normal flow, where no job is created. All the handlers of a throw event
therefore run in the transaction of that event.

Measured on 2026-10-01 against the pinned engine 7.24.0, with a throw event compensating two
finished service tasks. Both handlers ran in the same command context, the engine's own unit of
work, and one commit covered the two of them. The two activities they compensated ran in a
transaction each, so the flags do work where the engine makes a job of an activity. And a handler
which threw sent the other one back to work: the engine rolled the whole compensation back,
counted one retry off the single job and ran both handlers again on the next attempt.

So the deployment warns once per compensation throw event, naming the event and the handlers it
starts, and asks for a handler which may run twice and for work the engine cannot roll back to stay
out of it. A warning and not a refusal: the model is right, and no flag of this adapter changes what
the engine does. `Camunda7CompensationTransactionReportTest` holds the message,
`Camunda7CompensationTokensTest` the measurement, and the
[Deviations page](https://github.com/vanillabp/camunda7-adapter/wiki/Deviations#a-compensation-runs-in-one-transaction)
lists it among the gaps.

### A process id which is only declared

A workflow module may name a BPMN process id no file of it carries any more, which is how a
renamed process keeps being served: the new id is the primary one and the old id is declared as
a secondary process. Nothing is deployed under the old id, so the adapter never sees it while
wiring, and only the core knows it was declared at all. The core therefore asks after the module
was deployed (`AdapterDeploymentService#processVersionCatalogOf`), and this adapter answers with
the catalog it answers everything else with, which costs one definition query for that id.

That reaches the check: the versions the engine still holds under the old id are read like the
older versions of any process, and the workflows running on them are counted.

The runtime is the second half, and it needs more than a catalog. The engine evaluates the
expressions of the model a workflow was STARTED with, and `Camunda7TaskRegistry` holds the
connectables behind them per process this adapter WIRED, so nothing was wired for the old id and
a workflow on it used to reach its next task, find nothing and end in an incident. It is wired
now: `startWorkflowProcessing` asks the core which ids the module declares without a model
(`WorkflowTaskWiring#taskWiringOfProcessesNobodyDeployed`) and reads the model of every
version the engine holds under each of them, straight out of the engine's own repository, through
the same extraction a deployed model goes through. The connectables come from those models
because the type of a task lives there and nowhere else: a `camunda:expression` task completes
when its handler returns, while a `camunda:delegateExpression` task can stay open for a
`@TaskId` method, and no list of task definitions says which of the two a task is. A task two
versions share is registered once, and the wiring validation is not run over those models -
a task the application dropped in the meantime is what the version check reports, and ending the
boot over a model nobody can change any more would be the wrong answer to it.
A model the extraction refuses is skipped with one warning naming the version, since nobody can
change a model deployed years ago the way they could change one this boot brings.
[Decision 13](./DECISIONS.md#13-the-workflows-of-a-renamed-process-are-served-from-the-models-the-engine-still-holds)
carries the reasoning for all of it, `Camunda7DeclaredProcessWiringTest` holds the task kinds and
the skipped version. `Camunda7RenamedProcessIT` is the acceptance test: a workflow started
under the old id, an application which deploys only the new one, and the workflow running to its
end through the methods of that application, its open `camunda:delegateExpression` task
included.

The engine also STARTS workflows under such an id on its own: the timer of the old model's
latest version keeps firing after the rename, and its signal subscription keeps matching a
broadcast. Those workflows are full VanillaBP workflows too - the aggregate is built through
`@WorkflowStartedByBpms` (a signal start is told the plain signal name, read from the engine's
own copy of the model), the task after the start event is served, and the end reaches
`@WorkflowEnded`. The way back from the engine's definition key is registered the moment the
core asks for the id's version catalog, because whatever reads that catalog next makes the
engine parse the old definitions, and the parse is when the end listener is attached or lost
for good.

Those starts are what the catalog answers `startEventsOfVersion` with. Nothing wires the declared
id while the application boots, so a `@WorkflowStartedByBpms` method kept for it used to be
judged by nobody, and a typo in its `id` stayed one while the old timer fired every night. The
adapter reads the start events of every version the engine holds under the id, through the same
walk a deployed model goes through, and the core names the method no held version starts on. It
reads and warns, nothing more: a model deployed years ago is not one anybody can go back and fix.
`Camunda7StartEventsOfHeldVersionsTest` holds what is read out of such a model, the plain signal
name included.
[Decision 15](./DECISIONS.md#15-a-check-reads-the-engines-models-without-asking-who-deployed-them)
carries the timing, `Camunda7DeclaredIdRuntimeIT` measures all three notifications against a
running engine under `use-prefix`.

Those workflows end like any other, so the warning about a `@WorkflowEnded` method this engine
cannot serve is given for a declared id as well, while the id is being wired. No model of this
boot passes by such an id, so nothing else would have said it. Where the engine holds no version
under the id nothing is said at all: no version means no workflow which could end, and a
misspelled declared id is the core's check to name, together with the ids the module really
deploys.

The deployment checks which stay with the model this boot brings stay there on purpose. Refusing
an asynchronous task wired by expression, warning about an expression which reads what the
aggregate does not share and refusing colliding process ids all judge something a modeller can
still change and deploy again. A model the engine already holds is not that, and what a workflow
running on one can still walk into is asked of the version catalog instead.

### A suspended version counts, and how to get past it once

Deleting a process definition removes it from this engine, so the startup check for old process
versions stops reporting about it without anything being configured. Suspending it does not: the
definition is still there, its workflows keep running, and the `@WorkflowTask` method they are
missing is still missing once somebody resumes it. The definition query therefore names no
suspension state and a suspended version is checked like any other one.

The exception is an application which has to be up right now while an old version is nobody's job
to clean up at this minute. Starting it with the system property
`vanillabp.ignore-suspended-process-definitions=true`, or with the environment variable
`VANILLABP_IGNORE_SUSPENDED_PROCESS_DEFINITIONS=true`, takes suspended definitions out of the
check; where both are set the property wins, and only the value `true` counts. Two warnings then
stand in the log of every start, one saying the exit was taken and what it costs, one naming the
versions it hid, and neither is remembered anywhere, because they are supposed to be in the way
until the switch is gone. The reasoning, including why this is not a configuration property, is
[decision 12](./DECISIONS.md#12-a-suspended-process-definition-counts-and-the-only-way-past-it-is-a-system-property).
`Camunda7SuspendedProcessVersionsTest` holds the switch and both warnings,
`Camunda7OldProcessVersionsIT#aSuspendedVersionIsStillReported` and
`#theEmergencyExitTakesTheSuspendedVersionOut` the same against a running engine.

Two things nearby are deliberately untouched. `Camunda7WorkflowViewer` reports the latest version
of a called process whether or not it is suspended, and the version this boot runs on is the
engine's latest one for the same reason: both of them show what is there, and neither is the check.

## Camunda's web applications

The optional module `camunda7-adapter-spring-boot-webapps` serves Cockpit, Tasklist and
Admin at `/camunda` against the engines this adapter built. They normally arrive with
Camunda's own Spring Boot starter, which brings an engine along, and VanillaBP builds and
owns the engines, so the module does two things:

1. **Camunda's engine auto-configuration is switched off** (`camunda.bpm.enabled` defaults
   to `false` here). It builds a process engine unconditionally, so an application would
   run two engines on one datasource and the second one's job executor would acquire the
   jobs of the first. Setting the property to `true` fails the start with a message saying
   this.
2. **VanillaBP's engines are registered with the runtime container**, because that is where
   the web applications look for engines rather than in the Spring context. When the
   application stops they are removed again.

`Camunda7WebappsBootTest` holds both (`camundasOwnAutoConfigurationIsOff`,
`theEngineIsTheOneVanillaBpBuilt`, `webappsAreServed`),
`Camunda7WebappsRegistrationTest#enginesAreRegisteredAndReleased` the removal, and
`Camunda7WebappsTwoAdapterIdsTest` the migration setup.

The web applications are a servlet application built on Spring. There is no Quarkus
equivalent and none is planned, so this module is Spring Boot only.

## Supported Camunda version

Camunda **7.24** is the final feature release of Camunda 7 (October 2025, LTS). The
Camunda 7 community edition is **end-of-life** — no further community releases are
expected. This adapter pins Camunda `7.24.x`.

The pin is fixed and this adapter has no release lines, unlike the
[Camunda 8 adapter](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter#release-lines),
whose artifacts carry the cluster minor in their version. Camunda 8 needs lines because a
new minor arrives every six months and the client a build was compiled against is the lowest
cluster version it accepts. Camunda 7 has no next minor: what is still coming are enterprise
environment update releases twice a year until April 2030, and the engine runs embedded, so
the version an application uses is the version it ships. `renovate.json` therefore holds
`7.24.x` and anything above it needs a human, which is also the reason there is nothing to
gate.

The fork adapters for Operaton and CIB seven arrive as repositories of their own, so they
bring whatever versioning their forks need.

Camunda 7 runs **embedded** inside the application's JVM and normally shares the database
of the business code. Engine queries are therefore immediately consistent, which is what
phase one of every operation asks. What phase two does still happens after the caller's
commit, through the outbox, the way a remote BPMS works - see
[decision 2](./DECISIONS.md#2-a-workflow-is-progressed-after-the-callers-commit).

## Quarkus (JVM mode only!)

Both VanillaBP and the adapter are Quarkus extensions, so both must be added
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

The extension wires the **plain Camunda 7 engine** via the engine-shipped
`JakartaTransactionProcessEngineConfiguration` on the application's Agroal datasource
with the Narayana transaction manager — Camunda's own Quarkus extension is not used
(version-locked to older Quarkus releases). Engine commands join the caller's JTA
transaction, so the in-transaction guarantee holds like on Spring Boot; schema
operations run in their own JTA transaction (Agroal has no deferred enlistment).

**The Quarkus extension is JVM-mode only — native images are not supported** (the
engine stack — MyBatis, JUEL, scripting, reflective delegate instantiation — is
reflection-heavy; Camunda never supported native images and neither do the forks).

Configuration keys are IDENTICAL to the Spring Boot module (`database-schema-update`,
`history-time-to-live`, `data-source-name`) — `data-source-name` references a named
Quarkus datasource declared under `quarkus.datasource.<name>.*` (on Spring Boot it
references a `DataSource` bean of that name; in both cases the datasource is
application-/runtime-provided, VanillaBP never builds a pool):

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

An unknown `data-source-name` and two adapter ids sharing one datasource fail the
boot with guiding messages (`Camunda7UnknownDataSourceNameTest`,
`Camunda7SameDataSourceValidationTest`), and `Camunda7TwoEnginesTest` runs the two engines
side by side. Native mode has no test because there is nothing to test: the extension is
not registered for it.

## Viewing workflows

`ProcessService#getProcessDefinitions`, `#getBpmnXml` and `#getWorkflowHistory` are answered
from the embedded engine: `RepositoryService` (every deployed version incl. its BPMN XML) and
`HistoryService` (instance timeline, incidents). Both are cheap local queries - there is
neither an eventual-consistency lag nor an application-version boundary.

- The workflow is addressed by **business key** (aggregate ID) plus the scope its workflow
  module is deployed in - a tenant named after the module under `by-adapter`, no tenant at all
  under the other two modes. The adapter-native process definition id is Camunda's own
  (`MyProcess:1:8a9c…`), so the exact version an instance runs on is reported.
- `getProcessDefinitions` additionally reports the definitions the process' **call activities**
  would call next (latest deployed version of the called process id in the same tenant);
  call activities addressing their process by expression are skipped (only known at runtime).
- The history context of an executed call activity is the **called process instance id**; a
  context not belonging to the workflow is rejected and logged.
- Camunda's fine-grained activity types are mapped onto the SPI's `WorkflowElementType`;
  `error` carries the message of an OPEN incident of that activity.
- **History level matters:** with history level `none` no element history exists - the adapter
  then reports the definition and a `null` element history instead of failing. Ended workflows
  stay viewable until `history-time-to-live` cleanup removes them; afterwards the core raises
  the guiding `WorkflowNotFoundException`.
- Because history is queried, this adapter also reports ENDED workflows as `COMPLETED` to
  VanillaBP's BPMS election (instead of "unknown") - which is what makes viewing ended
  workflows work and keeps a re-dispatched start from starting a second instance of a workflow
  which already ran to its end.

`Camunda7ViewerApiIT` holds the read path against the engine
(`endedWorkflowsStayViewable`, `processDefinitionsIncludeCalledProcesses`,
`historyReflectsExecutionAndOffersSecondaryContext`, `unknownSubjectsRaiseGuidingErrors`),
`Camunda7ViewerApiTest` the same on Quarkus, and
`Camunda7WorkflowVisibilityTest#embeddedEngineReportsNoVisibilityDelay` the absent lag.

## What this adapter says about a value type

The platform refuses to start a workflow whose values may not arrive as what they were, and it asks
every adapter of that workflow what its BPMS does with a type
(`MigratableProcessService#whatThisBpmsDoesWith`). `Camunda7ValueTypes` is this adapter's answer.

Camunda 7 keeps a value in a variable of its own type where it HAS a type for it: the texts, the
boolean, the numbers of the JDK, a date and a byte array. An arbitrary-precision number is not among
them, so a `BigDecimal` is written in the serialization format configured for the workflow, and what
an expression reads back is that format's answer. That is reported as changed in both directions,
with the outbound and the inbound sentence saying different things: outbound the model reads what
the format produced, inbound the number arrives as the declared type without the scale it was
written with.

Anything else is answered with "cannot say", which never ends a startup. What a format really costs
is measured rather than guessed, by `Camunda7SerializationRoundTrip`, and the deployment reports the
measurement.

## Decision log

Decisions several places in this repository rely on live in [`DECISIONS.md`](./DECISIONS.md), the
one thing the code is allowed to cite. A citation reads `see decision 3 in the repository's
DECISIONS.md`, numbers are never reused, and an overturned entry stays and names its successor, so
a citation written today still resolves in a year.

## Known deviations

What this adapter does not deliver, mirrored in one sentence each on the wiki's
[Deviations](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Deviations)
page. An engine on its own datasource is NOT one of them: it is the documented mode
described under [Transaction caveat](#behaviour) above.

### External tasks

A service task wired by `camunda:topic` is not served. The adapter delivers tasks through
the engine's own execution (`camunda:expression`/`camunda:delegateExpression`, see
[Task processing](#task-processing-execution-model)), and the external-task API is a
second delivery mechanism with its own lock, retry and completion model. Nobody asked for
it yet, so there is no timeline.

Deploying such a task is refused with a guiding message. Meeting one in a version the engine
ALREADY holds is a warning naming the version instead: that model is only being read, on
behalf of the startup check about older versions, and nobody can change it any more - see
[decision 15](./DECISIONS.md#15-a-check-reads-the-engines-models-without-asking-who-deployed-them).

## Known issues

- **`camunda-bpm-spring-boot-starter:7.24.0` is incompatible with the Spring Boot 4.1
  baseline.** VanillaBP Version 2 builds on Spring Boot 4.1.0, whereas the Camunda 7.24
  Spring Boot starter targets Spring Boot **3.5.5** (`version.spring-boot` in
  `org.camunda.bpm:camunda-parent:7.24.0`). Its auto-configuration is compiled against
  Spring Boot 3.x APIs that moved or were removed in Spring Boot 4. Therefore the
  `spring-boot` module wires the embedded engine itself (see
  [Embedded-engine wiring](#embedded-engine-wiring)) using
  `org.camunda.bpm:camunda-engine-spring-6` and does **not** use the starter.

## Building

Prerequisites installed into the local Maven repository first (build order): `spi-for-java` →
`adapter-platform-integration` → this repository. Then:

```bash
mvn spotless:apply
mvn install
```

The engine runs embedded on an in-memory database, so the tests need neither Docker nor a network.
What a pull request needs beyond a green build is in [`CONTRIBUTING.md`](./CONTRIBUTING.md).

A build also runs once a night, without anybody pushing. The workflow *Nightly against the platform
snapshot* (`.github/workflows/nightly-platform-snapshot.yaml`) builds what a pull request builds,
with `--update-snapshots`, against whatever the platform published since yesterday, and a red night
opens an issue labelled `nightly-platform-snapshot`. Without it a platform snapshot can break this
repository and nobody sees it until the next pull request: on the 26th of September 2026 the
platform published at 12:19, the last build here had run at 10:15, and the break surfaced a day and
a half later inside somebody's pull request. The night writes down which platform snapshot it
resolved, with the timestamp and the build number, so a red night can be read as a break of the
platform or as a break of this repository.

## Test coverage

`mvn install` builds one aggregated JaCoCo report per platform (`install`, not
`install verify`: `install` already runs every phase `verify` has, so naming both walks two
lifecycles per module and reports every compiler warning twice):

1. **Spring Boot** (core + Spring Boot integration) - into `test-coverage-report/spring-boot/report`
2. **Quarkus** (core + Quarkus extension) - into `test-coverage-report/quarkus/report`

Both are published to GitHub Pages by the *Publish to GitHub Packages* workflow on every push to
the default branch. Click the [platform's badge](#documentation-and-supported-platforms) to open
the respective report.

The build breaks below the line: `test-coverage-report/coverage-gate` is the last module of the
reactor, reads both reports and fails whenever a platform is below its threshold in the root POM
(`coverage.threshold.spring-boot`, `coverage.threshold.quarkus`, in percent of covered instructions -
the number the badges above show). Both properties hold 85, the same number every VanillaBP
repository gates on, and that is not the target: the rule is 90 per platform, so a report between
85 and 90 passes the build and still names a gap. The gate is where the gap has grown too big to
carry, which is why it is never edited to make a build pass. It also compares every module
producing a `jacoco.exec` against the two aggregates, so a module added to the build without being
added to its report cannot stay unnoticed. `CoverageGateTest` is where both of those measurements
happen, and `TestClassConventionsTest` next to it is what keeps every test class on the output
suppression the printed lines below depend on.

`TestClassConventionsTest` also reads the main sources of this repository, for a guiding
message whose sentence fell apart: a run of spaces between two words, or two words a line
continuation glued into one.

The gate reports what it measured on every run, green ones included, which is the one place in
VanillaBP where a passing test prints. The angle brackets stand for the numbers of the run:

```
coverage gate | Spring Boot: <percent> % instructions (<missed> of <total> missed) | at the rule of 90 %
coverage gate | Quarkus: <percent> % instructions (<missed> of <total> missed) | <gap> points below the rule of 90 %, build breaks below 85 %
```

A build which stops at `package` never reaches the phase which writes the reports. The gate then
prints a line per platform saying that the coverage was not checked, and those two tests are
reported as skipped, instead of failing over a file the run could not have written.

Both platforms run the documented features end to end against a real embedded engine: Spring Boot in
`integration-tests`, Quarkus in `quarkus/integration-tests`. That duplication is deliberate. The
adapter core is platform-neutral, but a core being correct says nothing about a platform's glue ever
calling it, so a core line a platform never reaches names a feature that platform never runs.

The two platforms still reach different numbers, by what one suite can produce and the other
cannot: the startup check for old process versions needs several boots against one database, each
with a different model, and a Quarkus prod-mode test boots its application once per test class. The Quarkus suite's
class comment lists that and the three other cases it deliberately does not repeat. Everything else
is within a point or two of the Spring Boot numbers.

## Noteworthy & Contributors

[VanillaBP](https://www.github.com/vanillabp/spi-for-java) was developed by [Phactum](https://www.phactum.at) with the
intention of giving back to the community as it has benefited the community in the past.

![Phactum](./readme/phactum.png)

## License

Copyright 2026 Phactum Softwareentwicklung GmbH

Licensed under the Apache License, Version 2.0
