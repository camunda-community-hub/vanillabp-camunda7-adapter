# Decision log

Decisions this repository's code points at. A number is handed out once and never reused or
renumbered, so a citation stays resolvable; a decision which gets overturned keeps its entry,
marked as superseded and naming the entry which replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`, and it names an entry
of THIS repository only. A decision which the platform shares has its own entry in
`adapter-platform-integration`, written from that side; a pointer into another repository is the
fragile kind this log exists to avoid.

Links below point into this repository's [`README.md`](./README.md), which carries the detail an
entry deliberately leaves out.

### 1. The workflow aggregate is shared as process variables - the version named for the removal superseded by decision 25

*Superseded by decision 25: no version is named for the removal of the migration fallback.*

Camunda 7 runs embedded, so the EL resolver could read the aggregate live - and that is
exactly what makes a model portable in one direction only: `${riskAcceptable}` would work
here and fail on every remote BPMS. The values an aggregate shares are therefore written as
process variables at every point this adapter talks to the engine, and the engine evaluates
its expressions against them. Reading an attribute through the EL resolver survives as a
migration fallback for workflows started before, reported once per name and removed in 2.1.
See [Sharing the workflow aggregate](./README.md#sharing-the-workflow-aggregate).

### 2. A workflow is progressed after the caller's commit

Every operation which moves a process forward is scheduled through the phase-two outbox,
the way a remote BPMS works. Sharing the caller's transaction was possible and is not
enough: an
engine command which loses a concurrency conflict cannot be repeated inside that
transaction, because it leaves the transaction rollback-only, and repeating just the engine
part would advance the process while the application rolls back.

What phase one still does is ask - an embedded engine answers for free and in the same
transaction, so a gone task or an unknown workflow is reported where the application called.
What phase two does is idempotent, because the outbox dispatches at-least-once. A test which
called VanillaBP has to wait for the engine to catch up rather than read its state in the
next line.

### 3. Workflow modules are kept apart by scoping the identifiers

Camunda 7 has tenants, but a workflow module may also prefix its identifiers instead, and
then there is no tenant to ask. The engine is therefore always addressed with the SCOPED
identifiers - process ids, decision ids, message and signal names, error codes and task
definitions - while the core's registries stay keyed by the plain ones, and a delivery coming
back from the engine is translated before the core sees it. The mode is configured per
workflow module, which is why no code may assume either shape.
See [Keeping workflow modules apart](./README.md#keeping-workflow-modules-apart).

### 4. A class opens its fields one by one, not as a whole

The process service, the deployment service and the engine holders of this adapter hold dozens
of fields, most of them collaborators nobody outside the class needs. Which of them a caller
may read belongs to the surface of the class, so an accessor is declared per field, and
`@Getter` on the class is refused even where an IDE offers it: it would publish the current
field list and then keep publishing whatever field a later change adds.
`@SuppressWarnings("LombokGetterMayBeUsed")` on such a class is what keeps that offer from
coming back.

### 5. The adapter changes the BPMN it deploys, and only in ways the model's author can predict

An embedded engine offers no other seam. What a remote BPMS gets for free from its own protocol
this adapter has to put into the model before it is deployed, so `prepareBpmn` and `wireBpmn`
add: the `asyncBefore`/`asyncAfter` flags which make a service-like task a transaction boundary,
except for a compensation handler, where the engine ignores them (decision 35), built-in task
listeners for the user-task events, execution listeners for the workflow starts the engine
initiates and for the end of a workflow, the business key handed into a call activity which runs
on the SAME workflow aggregate together with the note saying so (decision 22), and the scoped
identifiers of decision 3.

Each of those is bounded by a rule which keeps the deployed model predictable. A listener is
added only where a handler exists, the business key is not injected where the called process has
an aggregate of its own or where the application modelled a `camunda:in businessKey` itself, and
the scoping rewrite runs once per FILE rather than once per process, because all processes of one
file share a model. What the adapter adds is listed in
[What the adapter changes in the BPMN it deploys](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Home#what-the-adapter-changes-in-the-bpmn-it-deploys)
in the wiki.

### 6. A task handler runs inside the engine's own job transaction - the delivery record part superseded by decision 39

*Superseded by decision 39: a delivery is recorded in both datasource modes, and the modes differ
in the deduplication rather than in the record.*

Camunda 7 delivers a task inside the transaction of the job it is executing, so this adapter runs
the handler there rather than opening one of its own. That is what makes the three outcomes exact:
a `TaskException` throws a `BpmnError` with the aggregate COMMITTED, a handler which keeps the task
open leaves the activity open, and any other exception rolls the job transaction back and lets the
engine decrement its retries and deliver again.

The consequence is that the engine's retry IS the recovery here, which is why this adapter
contributes nothing to the delivery log of the platform: a redelivery proves that nothing was
committed. An application which gives the engine a datasource of its own loses that proof, and
that limit is documented rather than papered over.

That limit is now answered rather than only named, and the answer follows the datasource mode. An
engine on a datasource of its own runs its job
transaction on a resource the application's persistence cannot join, so VanillaBP opens the
transaction the handler and the workflow aggregate run in, that one commits before the job does, and
a job the engine hands out afterwards is a repeated delivery of committed work. Such an adapter id
therefore answers `deliversTasksAtLeastOnce()` with `true` and names each delivery by the id of the
job at hand, which the engine keeps across its retries; on the application's datasource nothing of
that happens, because there is nothing a record could add. A user-task notification stays unnamed in
both modes: one transaction creates every user task the token reaches, so the job would name several
notifications, and what is unique per task is generated while the task is created and does not
survive the rollback which produces the repetition.

### 7. `table-prefix` says the tables are already there

Camunda's own schema management ignores the prefix and creates unprefixed `ACT_*` tables. That is
not a guess, the engine says so itself in `ProcessEngineConfigurationImpl#setDatabaseTablePrefix`,
and its shipped DDL names every table literally. So `table-prefix` in this adapter means that
somebody else created the prefixed tables, and two guiding failures keep the misunderstanding out:
a prefix together with a schema-creating `database-schema-update`, and a prefix without the
tables. Both are checked before the engine is built, which also keeps stray unprefixed tables out
of a shared database.

Rewriting the shipped DDL in the adapter was tried and rejected: every substitution which looks
right hits the `ACT_ID_` and `ACT_INST_ID_` COLUMNS or leaves an index name behind, and neither
shows up before something runs. See
[Two engines on one database](./README.md#two-engines-on-one-database-table-prefix).

### 8. A probe and a write answer for the scope of the call, never for a business key alone

Aggregate ids are unique per aggregate type, not across an application, so two workflow modules
whose aggregates count from one both hold an id `1`. Every probe therefore filters
server-side by the scoped process definitions of the calling `WorkflowScope` and by the tenant,
for running as well as for historic instances, and the same filter narrows the instance lookup
behind `aggregateChanged`.

The write matters more than the answer. Setting a variable in Camunda 7 makes conditional events
re-evaluate, and the technical marker is written even where the aggregate shares nothing, so an
unfiltered lookup would not merely misreport a foreign workflow, it would advance it. Two
instances inside the adapter's own scope are a broken assumption and end with a message naming
the aggregate, the scope and both instance ids. The branch which was given a task id keeps no
filter, because an execution id names exactly one execution.

### 9. A nested shared value travels in the engine's own serialization format

Writing shared values as a JSON string was the first choice and was dropped: a dot-notated
expression has to navigate the value, which it can only do when the engine holds it as an object
variable. So `serialization-format` is resolved per workflow, workflow module and adapter, and the
adapter additionally honours the engine's `defaultSerializationFormat`.

That needs a data format in an embedded engine, which needs a process engine plugin, which this
adapter refused to accept before. Every `ProcessEnginePlugin` bean of the application is applied
now, and `engine-plugins` configures one by class name with its properties handed to Camunda's own
`PropertyHelper`, so the values convert exactly as they would in a `bpm-platform.xml`. Without a
format the adapter warns once that Java serialization applies.

### 10. A start asks the engine for numbers, and asks as many of them on the last day as on the first

The questions this adapter answers while an application boots read from tables which grow for as
long as it is in production: how many workflows still run on an old version of a process, how many
of them the configured scope will never reach, which versions the engine holds and what their
models look like. A start of ten seconds must not become a start of two minutes because the
application did its job for two years, and the platform states the rule for every adapter as
decision 19 of its own DECISIONS.md.

For Camunda 7 that means two things. A question about a quantity is a `count()` and the engine
answers it from an index; fetching the executions and counting the list is the same answer at a
price which rises every year. And a definition query is asked once for the whole process:
`fetchDeployedVersions` reads every version anyway, so it keeps the definition ids it saw, and the
questions which follow are answered from them rather than each asking again.

What does grow is the number of versions the engine holds, one per deployment which changed a
model, and the questions about older versions grow with it. That is deliberate: those questions are
what the check is for, and `outfaded-versions` is how an operator says which of them have stopped
being interesting. `Camunda7StartupQuestionCostTest` counts what a start asks.

### 11. What this adapter does per operation is a handler, not a pair of methods

VanillaBP's adapter SPI used to ask for two methods per outbound operation, and this
adapter had eighteen of them: nine phase-one checks and nine phase-two actions, most of
them a single line forwarding to a private helper. Adding an operation meant adding two
more, in every adapter, next to the four places the core needed for the same operation.

The SPI now asks for a map instead: one `PhaseOperationHandler` per `PhaseOperation`,
each of them the pair of "ask" and "act" for this engine, and everything else about an
operation belongs to the operation. This adapter answers that map, and the eighteen
methods are gone. What the handlers do is unchanged - the helpers they call are the ones
the methods called - so nothing about the engine, the checks or the idempotency moved
with them.

Two things are worth knowing for whoever adds the next operation here. The map is the
statement about what this adapter serves: an operation missing from it is an operation
this engine has nothing like, and VanillaBP refuses the boot for the ones every adapter
has to serve. And the phase-one half is where this adapter earns its keep, because an
embedded engine answers from the caller's own transaction - see the platform's decision
29 for why the operation itself carries no engine knowledge at all.

### 12. A suspended process definition counts, and the only way past it is a system property

Deleting a process definition really removes it here: the engine drops it from the database and the
definition query stops answering with it, so the startup check for old process versions stops
reporting about it by itself. Suspending is the other thing Camunda 7 offers, and it is not the
same. A suspended definition comes back the moment somebody resumes it, its workflows never went
away, and a `@WorkflowTask` method missing for one of their tasks is still missing afterwards. A
check which went quiet because somebody suspended a definition would have dropped the finding
without anything being settled, and the finding would come back as an incident on a live workflow -
which is what the check exists to prevent. So the definition query names no suspension state, and a
suspended version is checked like every other one.

There is one situation that rule cannot cover: an application has to run now, and nobody is in a
position to clean up an old version at this minute. For it there is
`vanillabp.ignore-suspended-process-definitions`, next to it the environment variable
`VANILLABP_IGNORE_SUSPENDED_PROCESS_DEFINITIONS`, and the property wins where both are set. Only
the value `true` counts, in any case; anything else is reported and changes nothing.

It is a system property and not a configuration key, and that is the part most likely to be
"fixed" later by somebody who does not know why. A configuration key lands in an
`application.yaml`, gets committed, and is then set forever without anybody noticing it again.
This switch is meant to be the decision of exactly this start, typed where it can be seen, which is
also why every start says out loud that it was taken and which versions it hid, and why nothing
about having said so is remembered.

The environment variable is an equal way in rather than a convenience. A Quarkus native image reads
neither `JAVA_OPTS` nor `JAVA_TOOL_OPTIONS`, which is where a container usually carries its `-D`
arguments, so without the variable the switch would do nothing at all in a native image and say
nothing about it either. An emergency exit that quietly fails is worse than none.

The name carries no adapter part and no adapter id, because it holds for every VanillaBP adapter
whose BPMS can suspend a definition - today only this one - and somebody searching for a way out in
an emergency should find one switch instead of three. An application running two Camunda 7 adapter
ids cannot take the exit for one of them alone. That is the price, and it is paid on purpose.
`SuspendedProcessDefinitions` is the single place all of this lives.

### 13. The workflows of a renamed process are served from the models the engine still holds

A workflow module may declare a BPMN process id it deploys nothing under, which is how a renamed
process keeps being served. Camunda 7 evaluates the expressions of the model a workflow was
STARTED with, so those workflows need the connectables of a model this application no longer has:
the expression text of every task, the element it is evaluated at, and the way the task is wired.
The engine has that model in its own repository, so the adapter reads it from there, version by
version, through the same extraction a deployed model goes through.

The alternative was to build the connectables from what the application says it serves. The core
names that (`taskWiringOfProcessesNobodyDeployed`), and it is enough on a BPMS where a
subscription is a name. Here it is not, because a connectable is more than a name. A
`camunda:expression` task completes when its handler returns while a `camunda:delegateExpression`
task can stay open for a `@TaskId` method, the difference decides what the EL resolver hands the
engine back, and no list of task definitions says which of the two a task is. Guessing it would
either close a task which has to stay open or keep one open which nobody will complete.

Two things follow from reading the models. Every version the engine holds is wired, because a
workflow may sit on any of them, and a task which several versions share is registered once. And
the wiring validation is NOT run over them: they were deployed by an earlier generation of this
application, a task it dropped in the meantime is what the check for old process versions reports
with the count of the workflows affected, and ending a boot over a model nobody can change any
more would answer that finding with the wrong instrument.

A model the extraction refuses is skipped with a warning rather than allowed to end the boot. The
same extraction refuses an external task and an expression VanillaBP does not understand, and a
model THIS BOOT brings should be refused that way - it can still be fixed. A model deployed years
ago cannot, so the versions which can be wired are wired and the one which cannot is named
together with what a workflow on it walks into.

The definition query this needs is the one the version check runs anyway. `fetchDeployedVersions`
reads every version of the process and now keeps the definition ids by version, so reading the
models costs no query of its own, which is what decision 10 asks for.
`Camunda7DeclaredProcessWiringTest` holds the task kinds and the skipped version,
`Camunda7RenamedProcessIT` the whole thing against a running engine.

### 14. An extension reaches the engine through a customizer, not through the engine

The Business Cockpit of version 1 got its engine hooks from Camunda's Spring Boot starter: an
engine plugin carried its parse listener, and the starter's history eventing told it that a
workflow had started or ended. This adapter builds the plain engine itself - both starters are
version-locked to platform releases which have reached their end - so neither exists any more,
and an extension had nowhere at all to put a listener.

`Camunda7EngineCustomizer` is that place, and it is C7-specific SPI living in this repository
rather than in the VanillaBP core: engine hooks belong to the engine's adapter, and a core which
knew about BPMN parse listeners would be knowing about Camunda 7.

Three things it can contribute, and each shape says something. Parse listeners go BEFORE or AFTER
VanillaBP's own, because that is what decides the order of the listeners they attach to an
element - a built-in task listener contributed "after" runs after the one the adapter attached,
which is the order an extension tracking user tasks needs, and it is a property of Camunda's own
pre/post parse-listener lists rather than of anything this adapter arranges
(`Camunda7EngineCustomizerIT#theParseListenerSeesTheUserTasksAfterVanillaBp` is the pin). A
history event handler is installed as a COMPOSITE next to the engine's own, so the history level
and everything else about history stays as configured and history is still written. And
`customize` hands over the configuration itself, for what these two do not cover.

A customizer is asked once per configured adapter id, with that id. Two ids are two engines, and
an extension registering something per engine has to be able to tell them apart - the same reason
every other per-adapter object of this adapter exists once per id.

### 15. A check reads the engine's models without asking who deployed them

The platform's decision 38 carries the rule: a check against a BPMN model must not depend on
which application version deployed the model it judges, and a check which cannot see every model
that could carry the answer stays silent instead of refusing. On this adapter the rule lands in
three places.

The engine STARTS workflows of a declared-only id on its own. The timer of the old model's
latest version keeps firing after the rename, and its signal subscription keeps matching, so
every listener serving such a start has to find its way back from the engine's definition key to
the workflow module and the plain id. That registration happens the moment the core asks for the
id's version catalog (`processVersionCatalogOf`), not later: the first read of that catalog makes
the engine parse the old definitions, the parse listener decides by exactly this registration
whether an end listener is attached, and a parsed definition stays cached - registering after
the parse loses the end notification for good. The plain signal names of a held model's signal
start events are read from the engine's own copy while its versions are wired, because nobody
registered them at deployment: nothing was deployed.

The refusals of the extraction judge a model being DEPLOYED. A model the engine already holds is
only being read - by the wiring of a declared id (decision 13 skips such a version with one
warning) or by the startup check about older versions, which used to END THE BOOT over an
external task (`camunda:topic`) in a version deployed years ago. Both read paths warn now,
naming the version as a version instead of rendering it into a message slot which said "file",
and the check answers for the rest of the model.

`Camunda7DeclaredIdRuntimeIT` measures the starts and the end against a running engine under
`use-prefix`, `Camunda7HeldModelReadingTest` the reading which never refuses.

### 16. A number the engine has no variable type for keeps its class

Camunda 7 stores a short, an integer, a long, a double, a boolean, a string, a date and bytes as
themselves. A `BigDecimal`, a `BigInteger` and a `Float` fit none of those, and this adapter used
to widen them to a double on the way in. So the value a model read was not the value the
application held: `120.50` renders as `120.5`, a `BigInteger` above 2^53 loses its last digits, a
`Float` of `0.1` renders as `0.10000000149011612`. Version 1 read the live workflow aggregate and
had none of that.

The conversion bought nothing either. EL coerces both operands of a relational operator to
`BigDecimal` as soon as one of them is one, so `${total > 100}` compares numbers against the
object itself. Equality is the one it does not rescue: `${total == 120.50}` coerces the same way
and then calls `equals`, so it is false against the live object and was true against the widened
double. Version 1 answered false as well, because it held the live object too, and a model
comparing a decimal for equality was already wrong before the upgrade.

So those three types travel as object variables, like every other value the engine has no type
for, and in the format decision 9 names. The `Character` conversion stays: EL compares a character
to `'A'` and to `"A"` alike, so writing it as a string loses nothing and buys a readable variable.

The price is that the format then decides what the value reads as, and this adapter reports that
instead of working around it. A startup check writes a sample of the type through the engine's own
serializer for the configured format and warns where what comes back is not what went in, per
attribute the models read. It measures rather than judging from a list of types, because the
dataformat belongs to the application and a warning about something which works is worse than a
missing one. Where the adapter's own configuration names no format it says nothing at all, and it
says nothing where it cannot measure: no sample of the type, no serializer for the format, or an
engine which will refuse the first push loudly by itself.

`Camunda7VariablesTest` holds the rule and `AbstractNestedExpressionsIT` with its two subclasses
holds what each format answers at runtime. The check is held by
`Camunda7SerializationRoundTripTest` and `Camunda7LossyFormatCheckIT`.

### 17. The engine answers for a process id and a decision id, and the deployment stamp is only a hint

The platform's decision 38 carries the rule: a check which cannot see everything that could
carry its answer stays silent, and where the BPMS can be asked, a finding about a model
somebody else deployed is a warning and never the end of a boot. Its decision 40 divides the
work for a name clash, the adapter asking and the core wording the message. What follows for
Camunda 7 is what this engine can be asked and how far its answer carries.

Two identifier kinds are keys of the repository, so both are queryable. Every BPMN process of a
workflow module is asked about in one statement (`processDefinitionKeyIn`), and a decision is
asked about one by one because its query has no batch filter. Neither query names a version:
our own deployment creates the newest version of a key another deployment may have held for
years, so asking for the latest version would find nobody but ourselves. Neither names a
suspension state either, for the reason decision 12 gives. The tenant belongs in both filters,
because a second tenant on one engine is a legitimate arrangement rather than a clash.

A message name, a signal name, an error code and an escalation code are in no index. They live
in the models, and reading one model per version the engine holds is the growth decision 10
forbids a start to have. Where the models are read anyway the question is free, and that is
where it is asked: from what was read out of the models of this deployment, and from the model
of a held version the check of older versions already reads. A task definition is not a
question here at all, because Camunda 7 keeps one process-local: the expression is evaluated
inside the process by VanillaBP's EL resolver and nothing subscribes to it engine-wide, which
is why this adapter does not rewrite one either.

The hard part is not the query, it is telling a foreign holder from this application's own
history. The Camunda deployment is what can be asked about, and the part of its stamp which
carries is the NAME: every VanillaBP generation deploys a workflow module under the module's
own id, version 1 included, which wrote the application name into the source where this adapter
writes the adapter type and the adapter id. So the deployments named after the module being
deployed are one query, and a definition belonging to one of them says nothing. Reading the
source instead would report every definition of an application upgrading from version 1, on
its first start and on every one after it.

A definition under another deployment name is reported, and the source then says how sure the
adapter can be. A source naming a Camunda 7 adapter of VanillaBP is reported without certainty,
because another adapter id is what a migration between two engines looks like and another
workflow module of this application writes its own name as well. Any other source was written
by something else, a modelling tool or an application which deploys its models itself, and that
one is reported as certainly foreign.

What this cannot see is a second application which deploys a workflow module of the same id. It
writes the same deployment name, and no column of the engine's deployment table names an
application. Reading that name as ours is what keeps the check from warning about this
application's own history on every boot, which is the price, and it is paid on purpose. Nothing
of all this is cached and no runtime path reads it.

`Camunda7IdentifiersTheEngineHoldsTest` holds the queries and the stamp against a real engine,
`Camunda7DeclaredIdentifiersTest` what a model declares and what a held version still
declares, and `Camunda7StartupQuestionCostTest` that a boot asks once per workflow module plus
once per decision.

### 18. An idle engine is told when to wake up instead of asking whether it is time yet

The Camunda 7 job executor does not wait, it polls: every 5 seconds, widening to 60 while nothing
happens, and every one of those cycles is a database command. An application which spends most of
its life waiting for a timer pays for that on a database billed by active use. The answer is not a
longer interval, because a longer interval makes a timer late without making the polling stop. The
answer is a question with an exact answer: when is the next job due.

So a cycle which found nothing asks the engine for the one job with the earliest due date later
than the moment that cycle began, and waits until then. Found nothing has to mean all four of
these at once, or the wait would be about load rather than about a due date: every engine handed
out fewer jobs than were asked for, nothing was lost to another node's lock, the acquisition did
not fail, and the threads executing jobs were not full. Anything else keeps the engine's own
backoff, untouched.

Two details of that question are decisions rather than taste. It asks for ONE row, because the
answer needed is a moment and every further row is read by the database and looked at by nobody;
version 1 listed every future job and used the first. And it uses the clock of the cycle which
just ran, not a fresh one. Reading the clock again opens a gap of a few milliseconds in which a
job falls due, appears in neither answer and is never woken for; the acquisition loop subtracts the
same moment from the wait it gets back, so a job which came due meanwhile starts the next cycle at
once. Version 1 also let the ordinary backoff reach its 60 second ceiling before the due-date logic
engaged at all, which spent about two minutes of polling on every quiet period to save one query
per cycle. The query is one indexed row and it replaces the cycle it would otherwise have paid for,
so the staging is gone.

There is no cap on how long this may wait. The due-date query answers the question exactly, and a
job this node writes itself wakes it. The platform's outbox answers the same question with a cap,
because there a second node's write cannot be seen at all; here the cap would only paper over the
case named below, and a reader who thinks it is about timing sets it to seconds and gives the whole
saving back.

The waking hangs off the TRANSACTION, not off a list of methods. Every engine command asks its own
commit to wake the acquisition, and a job can only be written by an engine command, so a workflow
which is started, a message which is correlated, a task which is completed, a signal, a
continuation the engine wrote for itself, a retry it rescheduled and a request to Cockpit or to the
REST API are all covered without any of them being named in the code. Version 1 published an event
from four places in one class, which left a plain save of a workflow aggregate, a signal and an
engine-internal continuation waking nothing.

That hook is the engine's command chain rather than a post-commit hook per platform, and the reason
is that neither platform has one. Spring's and Quarkus' post-commit mechanisms have to be called
from inside the transaction by somebody, and the only place this adapter is inside every
transaction which could have written a job is the engine's own command. Underneath, the engine's
transaction context uses exactly those two mechanisms: on Spring Boot a synchronization registered
with `TransactionSynchronizationManager` acting on `afterCommit`, on Quarkus a JTA synchronization
acting on a completion which committed. What the adapter adds is the one place which registers it.

What the engine already does by itself is worth saying, because it is what makes the due-date wait
the feature and the waking a detail. `JobManager` hints its own executor after a commit which
inserted a job due now, and for a timer it hints only where the due date falls inside the
executor's wait time. A job due LATER is the gap, and without the due-date wait, covering that gap
shortens nothing at all.

What this does not cover is a second application writing jobs into the same engine. That
application's commit runs in its own process and reaches no listener here, so a node waiting on a
due date computed before that write learns about the job when its own question next runs. Two
applications against one engine is not a setup this adapter supports, and this is one of the
reasons.

The engine's metrics reporter is switched off where the feature is on. It owns a timer of its own
and writes its counters through a database command every 900 seconds, which would wake an engine
four times an hour that was meant to stay quiet, and a feature which waits for an hour and is woken
by its own metrics has saved nothing. Only the writing stops; the counters are still kept in
memory. An application which needs the written metrics says so with `db-metrics-reporting`, and the
startup message tells it what that costs.

Jobs are acquired by due date where the feature is on, because waking for the earliest due job and
then acquiring in an unrelated order picks the wrong job as soon as more are due than one cycle
takes. That order wants a database index, which is the operator's work and which the startup
message asks for by name. `jobExecutorPreferTimerJobs` is left as the engine has it: a preference
between kinds of job is a different question, and version 1's README claimed both flags while its
code set neither.

`Camunda7DueDateSleepTest` holds the waiting rule against a real engine, including the count of
connections an idle engine takes, a job due later, a commit which shortens a running wait and an
engine holding no job at all. `Camunda7JobExecutorSleepTest` holds what the two keys decide and
what the startup message says, `Camunda7StartupQuestionCostTest` that the question asks for one
row, and `Camunda7SleepingEngineIT` with its Quarkus twin that a booted application on either
platform takes no connection while nothing is due.

### 19. The tenant answers for two workflow modules, and it is resolvable per module

The core asks this adapter whether its own isolation keeps two workflow modules apart, because it
cannot see the scopes of a BPMS; why the answer ends a boot rather than warning is written in the
platform's own decision log, and this entry says what Camunda 7 answers. On that engine it is one
question: would the two modules be deployed into two different tenants. So
the answer resolves the tenant of each module through the very method the deployment resolves the
one it deploys under, and compares the two names. Reading a property instead would answer about the
configuration rather than about the engine, and those are not the same thing: the mode may drop the
name, and the name may come from a level the reader did not look at.

No tenant counts as a scope here. A workflow module under `use-prefix` or `none` reaches the engine
without one, which means two such modules share every process definition key they both declare,
while one of them against a tenanted module shares none. Answering "separated" for a tenant-free
module would hide a clash the prefix no longer covers, which is what a mixed configuration
produces.

The tenant name became resolvable per workflow module with this, not only per adapter. Three
reasons, and the first is the decisive one: the refusal the core writes tells the developer to give
one of the two modules a scope of its own, and the key it names is the module's. A fix the message
recommends has to work. The second is that the adapter would otherwise answer a question it has no
way of being right about - one name for every module is the only thing an application could say,
and the answer would always be "separated by nothing". The third is that the name belongs to the
level the deployment belongs to: VanillaBP resolves an adapter's properties over the levels
anyway, and a tenant which every module shares is the special case rather than the rule.

There is no tenant per workflow. The mode has one, a tenant cannot: a tenant id is an attribute of
the deployment and this adapter makes one deployment per workflow module, so two workflows of one
module cannot reach the engine in two tenants. A key which looks honored and is ignored is worse
than a key nobody may write, so only the workflow module and the adapter carry one. For the same
reason the check which refuses a tenant the mode would ignore now runs per property key rather than
once per adapter: the developer has to be sent to the line they wrote.

`Camunda7WorkflowModuleIsolationTest` holds the answer per configuration, including the tenant-free
cases and the name set for one module only, `Camunda7CollidingProcessIdsTest` holds the boot which
ends on the second of two modules under one process id with the core's own support in between and
both deployment orders driven, `TenantResolutionTest` the resolution over the levels, and
`Camunda7StartupQuestionCostTest` that answering asks the engine nothing.

### 20. A listener somebody modelled is a task, and only where the application asked for it

Version 1 let a `camunda:executionListener` be served by a `@WorkflowTask` method and announced it
nowhere, because a model with application logic in a listener cannot be moved to another BPMS.
Version 2 deleted the reading side, which made such a listener silently unserved: the engine
evaluated the expression itself, so a workflow reaching the element either failed on a name nothing
resolves or ran a method the application never meant for that element. What was decided is that the
listener is served again, behind a key which is off by default, and that a boot which uses it says
what it costs.

A served listener is a `camunda:executionListener` written as `camunda:expression` or
`camunda:delegateExpression` **whose expression names a `@WorkflowTask` method of this
application**: `delegateExpression="${archiveOrder}"` is served where
`@WorkflowTask(taskDefinition = "archiveOrder")` exists, and only then. That last half is what keeps
the feature from taking something away. On Camunda 7 with Spring or CDI a delegate expression is
resolved against the application context, so `${failTheJobOnce}` naming a bean which implements the
engine's own `ExecutionListener` is an ordinary Camunda 7 model, older than VanillaBP and none of its
business. The same holds for a `camunda:class` and a `camunda:script` listener, which carry no
expression at all. None of those is read, refused or reported: whatever resolves them keeps resolving
them, and the key is not about them.

Only the task-definition route counts. `@WorkflowTask(id = ...)` names the ELEMENT, and one element
may carry a task and a listener at once, so the element id cannot say which of them a method means.

A `camunda:taskListener` is not read at all either. VanillaBP notifies the `@WorkflowTask` method of
a user task itself, on creation and on cancellation, and a second route into the same moment would be
two mechanisms for one thing.

`vanillabp.adapters.<id>.allow-listeners` is the key, a boolean, `false` unless somebody writes it.
It resolves at adapter, workflow-module and workflow level, the most specific configured value
winning in both directions, so a module which serves its listeners can have one workflow which does
not. There is no task level: that level is keyed by a task definition, and whether a listener becomes
a task at all is what this key decides, so at the moment the key is read there is no task definition
to key a level by. A value written there anyway earns one guiding warning naming the three levels
which work, and the boot goes on. The Camunda 8 adapter reads the very same key at the very same
three levels, because two keys about what a model may contain, reaching different levels on different
adapters, would be the worse answer.

Where the key is off and a model carries a served listener, the boot ends here in the adapter, and
that is not how `allow-connectors` works. A connector asks VanillaBP to LEAVE an element alone, so
the core's wiring validation finds a task nothing serves and ends the boot by itself. A listener asks
VanillaBP to SERVE something, so without the key there is no task spec and nothing for the validation
to miss. The message therefore comes from the adapter, and it names the elements, the three levels
and the cost.

Where the key is on, a listener is a task like any other one from there on. `validateTaskWiring` asks
for a `@WorkflowTask` method and ends the boot where none exists, and
`validateNoUnwiredWorkflowTaskMethods` reports a method which matches no listener of any wired
process. Version 1 wired its listeners privately and had neither direction.

The event is part of a listener's identity, because one method serves one event of one element. What
`@TaskEvent` receives is therefore `CREATED` for every listener, which is the only value that works at
all, since a method without the parameter subscribes to `CREATED` alone. What a method hears when the
element is CANCELED is decided by entry 26, which supersedes this paragraph in that one. Two served listeners of one element under ONE expression end the boot naming
both: one method would serve two events and nothing it could ask would say which one it is in, and
that is the case version 1 decided with a `findFirst`. Two listeners of one element under different
expressions are fine, and a method then has to name the task definition, because
`@WorkflowTask(id = ...)` names the element and cannot tell them apart. A method declaring `@TaskId`
ends the boot as well, since a listener is notified and done and the task can never stay open, and a
method throwing `TaskException` is answered with the reason rather than with an incident: a listener
has no token to route.

The default is off because of what serving a listener costs. A listener is where a BPMS lets an
application in at a moment the BPMS owns, and every BPMS draws that moment differently, so the model
stops being portable: another BPMS has no listener at this element and a migration of the model stops
at the method serving it. The Process-Engine-API has no listener concept at all, which is gap 16 and
gap 17 of that adapter's `GAPS.md`. So every boot of a workflow module whose listeners are served
writes one framed WARN naming each served listener, the key which switched it on, what it costs and
the way back, and no key silences it: what it says stays true for as long as the listener is in the
model, and a key turning it off would only make the loss invisible.

See [Listeners somebody modelled](./README.md#listeners-somebody-modelled).

### 21. An extension asks this adapter, not the engine

Camunda 7 runs embedded, so an extension of this adapter runs in the same JVM, on the same
execution tree and against the same process definitions. Every fact it needs is a fact this
adapter already looked up. Where the adapter kept that lookup private, the extension read the
engine's internals a second time, and six mechanics existed twice: the multi-instance walk through
the execution tree, the way back from a process definition key to a workflow module, the tenant of
an adapter id, whether the engine joins the caller's transaction, what a user task is called and
the version of a process definition.

Two of the six had already drifted. The task definition of a user task with an EXPRESSION form key
was the written text on one side and the evaluated value on the other, so one task reached a
consumer under two identities and looked like two tasks. And an engine on a data source of its own
was reported as joining the application's transaction on Quarkus while this adapter answered the
opposite everywhere else.

So the mechanics are published, in `io.vanillabp.camunda7.api`. It is API of this repository, not
of the VanillaBP adapter SPI: none of it is a mechanism another BPMS shares, and a core which knew
about Camunda's execution tree would be knowing about Camunda 7. It sits next to
`Camunda7EngineCustomizer`, which is the other half of the same idea, and it follows the same rule:
one object per configured adapter id, because two ids are two engines.

The transaction answer is the adapter's, and the answer is the data source rather than the
transaction manager. Quarkus builds the engine on the container's transaction manager, which does
enlist both resources, and a JTA transaction around two independent data sources is still two
commits. So an adapter id given its own data source does not join, on either platform, and it is
the same answer which already makes such an id deliver repeatable tasks and name its deliveries.
Reading the data source name and concluding otherwise was reading the right key and drawing the
wrong conclusion.

What is published promises what it says and nothing more: what the adapter REPORTS does not change
here. A user task without a form key still reaches the core with an empty task definition, and the
rule which fills in the element id is published rather than applied.

`Camunda7EngineFactsIT` and `Camunda7AdapterBootTest` hold it on Spring Boot,
`Camunda7EngineFactsOnQuarkusTest` on Quarkus, and the tests in `io.vanillabp.camunda7.api` hold
what each entry point promises.

See [What an extension may ask this adapter](./README.md#what-an-extension-may-ask-this-adapter).

### 22. The multi-instance chain crosses a call activity, and the deployed model says where it may

Camunda 7 keeps the item, the index and the total of an iteration in the execution tree, and that
tree does not end at a process boundary: the execution of a called process points back at the call
activity which started it. So the walk which collects the levels of a task can carry on in the
calling process, and it does. A task of a called process is told the iteration its call activity
sits in, over as many levels as the models nest, and the application models nothing for it.

Whether the walk SHOULD carry on is a question about the workflow aggregate rather than about the
model. A called process which continues the caller's aggregate continues its business case, and
the iteration the caller stands in belongs to that case. A process with an aggregate of its own is
a business case of its own, and what the caller iterates over says nothing about it. Version 1
assumed every call activity was decomposition, which is the assumption this entry drops.

The core answers that question while the application starts, from what its workflow services
declare. The walk needs the answer while a workflow runs, and the core knows only what the
application declares today, so the answer travels in the model which was deployed: a call activity
whose called process continues the caller's aggregate gets a `camunda:property` named
`vanillabp:sameWorkflowAggregate`, written next to the business key of decision 5 and read by
`Camunda7MultiInstances`. Two things follow from that. An older version of a process which the
engine still runs keeps the answer it was deployed with, which is what the workflows still standing
in it need. And a call activity which names the process to call in an expression carries no note,
because the model does not say which process will be called. The core is asked for it while the
workflow runs, through the `Camunda7TaskRegistry` the deployment service hands that one answer to,
which is where the start listener of a called process asks the same question (decisions 32 and 34).
It is asked ONLY there. Where the model spells the called process out, the answer of the deployment
stands, and a workflow standing in an older version keeps the answer that version was deployed
with.

The values themselves are never copied anywhere. The engine holds them in the executions of the
calling process, and a called process which is not told about them can still read what the model
passes it. What ends at the boundary is what this adapter REPORTS, which is the only place where
the difference between two business cases is known.

`Camunda7MultiInstancesAcrossCallActivitiesTest` runs the shapes against an embedded engine, with
every called process in a BPMN file of its own: with the calling and the called process in one
file the walk finds the enclosing element by chance, which is how the missing model of the caller
stayed invisible from version 1 until this entry was written.

See [The iteration a called process runs in](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Configuration#the-iteration-a-called-process-runs-in).

### 23. A wake-up which arrives while the next wait is decided still ends that wait

Camunda's acquisition loop reads its "a job was added" flag into the cycle which just ran, asks
the strategy how long to wait, and clears the flag afterwards. A wake-up arriving between those
two steps sets a flag nobody looks at any more, and one arriving a moment later races the point
where the loop starts listening on its monitor. The engine lives with both and pays an idle
interval for them, five to sixty seconds, which is fair: the next cycle is never far away.

Decision 18 changed what that costs. The wait this adapter decides on reaches to the next due
date, and where the engine holds no job at all it reaches a year. So the same lost wake-up is no
longer a late cycle but a job which never runs, with no incident, no log line and no metric to
show for it. The application it hurts is exactly the one the sleep was built for: one which is
quiet until somebody gives it work.

The answer is not a cap on the wait. A cap trades the whole saving for a probability and leaves
the loss in place, only smaller; and the number somebody picks for it is never questioned again.
The answer is that this loop keeps a wake-up of its own. It is set whenever somebody asks the
executor to wake up, it is cleared where a cycle begins - before any engine is asked for jobs -
and the loop refuses to suspend while it is set. So everything written after a cycle read the
database ends that cycle's wait instead of being swallowed by it, and the worst a wake-up can
cost is the cycle which is already running.

Two smaller things follow. The flag is set BEFORE the executor's own, and read AFTER the loop
announced that it is listening, which is the opposite order: one of the two sides therefore
always sees the other, and there is no window left to make narrow. And a cycle the context
reports as woken keeps the engine's own timing rather than a due date, because the wake-up says a
job was written after the acquisition read the database and the due date it asked for is
therefore older than the job.

Camunda 8 and the Process-Engine-API have no acquisition loop of this kind: their tasks are
pushed or polled per subscription and neither computes a wait from a due date, so neither has the
window this entry closes.

`Camunda7WakeupInTheGapTest` writes the job from inside the window itself and asserts the job
runs, and it asserts what the strategy decides for a cycle somebody woke. It watches the job
through a bean of the engine and not by asking the engine, because every question asked of an
embedded engine is a command whose commit wakes the acquisition - a test polling the engine wakes
the sleep it measures.

### 24. A start is the application's own where the ID already has an aggregate - superseded by decision 28

> Superseded. A workflow started past VanillaBP no longer keeps the business key it was started
> with: the id of a workflow is the id of its workflow aggregate, the application assigns it, and
> a key VanillaBP did not write is refused. Decision 28 says why and what replaced the parts of
> this entry which still hold. The entry stays because code and messages pointed at it.

On Camunda 7 the business key IS the workflow aggregate's ID. `Camunda7ProcessService` writes the ID
into the key when the application starts a workflow, and every other part of this adapter reads the
key back as the ID. A key therefore names an aggregate, and it says nothing about who started the
workflow.

The listener on a timer, signal or conditional start event used to return as soon as the instance
carried a key. Measured against a running engine, that assumption does not hold in three ways: a
process whose only start event is a timer can be started through `startProcessInstanceByKey` with
any key, so can one with a signal start event, and a conditional start the engine performs itself
carries a key whenever the caller of `evaluateStartConditions` named one. In all three cases no
aggregate was built and nothing was logged. The first task of that workflow then failed with "no
workflow aggregate of class ... was found", which blames a deleted aggregate for a workflow which
never had one, and the job retried until it became an incident.

The rule which replaced it is the one question Camunda 7 can answer: a start is the application's
own where the ID already has an aggregate. The key travels to the core as the name the instance
goes by, the core looks for an aggregate of that ID, and the answer says which case it was. An
aggregate which existed belongs to the application's own start, or to a workflow taken over from
version 1, which carries its ID in the business key and nowhere else. An aggregate which was
created belongs to a workflow somebody started past VanillaBP, and it is created under that key so
the workflow keeps the name it was started with. That case is worth one INFO line, once per
workflow: the application would otherwise hear of the workflow only when its first task arrives.

What this costs is one load of the aggregate per BPMS-initiated start, where the early return cost
nothing. A signal broadcast which starts many workflows pays it per workflow. The price buys the
only reliable answer there is, and a start which is followed by a task loads the aggregate a moment
later anyway.

One case is refused rather than repaired: a key which cannot be an ID of that workflow aggregate,
such as a text where the ID attribute is a number or a UUID. VanillaBP would have to give the
workflow an ID of its own and overwrite a key somebody chose, which destroys the name the starter
is working with. Nothing is written, and the message names the instance, the key and the two ways
out. This adapter raises no incident of its own: the engine retries the start and raises one
afterwards, the way it does with any failing start.

Two things stay invisible, with open eyes. A key which happens to look like the ID of an existing
aggregate attaches that workflow to it without a word, and nothing here can catch it, because
catching it needs two values naming the instance and Camunda 7 keeps one. And an application which
deletes an aggregate while its workflow still runs looks like a foreign start at the next
BPMS-initiated start of the same ID.

`Camunda7ForeignStartIT` starts all three kinds from outside, holds the application's own start
against them, and reads the message of the refused one. See
[The start of a workflow](./README.md#the-start-of-a-workflow-and-workflows-which-ended).

### 25. The migration fallback names no version for its removal

Decision 1 ends by saying that version 2.1 removes the live read of the aggregate. Nobody can
hold that date today. So the date is gone from every message, from the README and from both
wikis, and this entry says what stands instead: the fallback stays a fallback, it will be
removed, and whoever needs a date asks the VanillaBP team. Everything else decision 1 says stays
as it is. The values an aggregate shares are written as process variables, the engine evaluates
its expressions against them, and the live read answers only where the engine holds no variable
of the name an expression asks for.

What a reader can watch instead of a version is the fallback itself. It warns once per workflow
module, process and name that it answered an expression by reading the aggregate. It also says,
at most once an hour, how many workflow instances it has served since the application started.
That count falls on its own, because a workflow stops needing the fallback as soon as it reaches
a point where this adapter writes the shared values. A count which keeps growing says something
else: a model reads an attribute which is not shared, and the startup check names those
expressions.

`Camunda7UnsharedExpressionCheckIT` asserts the sentence the startup check prints about the
fallback, so a message which goes back to naming a version fails the build.

### 26. A served listener is told when its element is canceled, through the listener the engine already fires

This supersedes the paragraph of decision 20 about what `@TaskEvent` receives. The rest of that entry
stays as it is.

A listener fires at the moment the modeller picked and at no other. An element taken away by an
interrupting boundary event or by a terminating end event never reaches a `start` listener, so the
method serving it is never told that the work it was waiting for is gone. Version 1 had the same hole
and said nothing about it.

`TaskEvent.Event` is not widened for this. A listener is a construct no BPMS promises the same way:
this engine knows `start`, `end` and `take`, Camunda 8 knows `creating`, `assigning`, `updating`,
`completing` and `canceling` on a task listener, and a common set over the two would be a promise
VanillaBP cannot keep. So a listener knows the two events `TaskEvent.Event` already has. `CREATED` is
the modelled listener firing, whichever moment it is, and the event a method really wants is in the
model: one listener per event, one method per listener. `CANCELED` is the element going away.

The engine already fires an END execution listener when an element is canceled, which is how
`Camunda7TaskCancellationListener` hears a cancellation for a TASK. The same listener therefore serves
the listeners of an element, and `Camunda7AsyncBpmnParseListener#parseProcess` attaches it to the
elements which carry one. The process is asked once, after its whole scope was parsed, rather than
through a `parseXxx` method per element type: a listener may sit wherever a modeller can select
something, and a method per type would be a list which falls behind the next BPMN element the engine
learns. An element the engine has no activity for is skipped, a sequence flow being the case a
modeller reaches, because a sequence flow is never canceled.

A listener the modeller put on `end` gets nothing of VanillaBP's own. Such a method already hears the
cancellation through its own listener, as `CREATED`, and a second report would be the same moment
twice. The Camunda 8 adapter draws the same line for the listener a modeller puts on its own cancel
moment: whoever hears the moment already is not told a second time.

An element which already carries the cancellation listener keeps the one it has. Every service-like
activity does, because the transaction boundaries put it there, and a second copy would report one
cancellation twice.

Nothing is written into the BPMN for this, which is the rule decision 14 states for everything
VanillaBP and its extensions attach here. What the wiring learns is written into
`Camunda7TaskRegistry`, and the parse listener reads it there under the process definition key the
engine reports.

`Camunda7ListenersTest` holds which listener needs a cancellation and which one does not, and
`Camunda7ModelledListenerIT` holds a boundary event taking an element away and the method hearing it.

See [Listeners somebody modelled](./README.md#listeners-somebody-modelled).

### 27. An event subprocess does not start a workflow

A workflow starts when the BPMS creates an instance of a BPMN process. At that moment there is no
workflow aggregate, so VanillaBP asks the application to build one, and every start event the
engine fires by itself needs a `@WorkflowStartedByBpms` method. That is what this adapter means by
a start of a workflow.

The start event of an event subprocess is not one. The engine fires it inside a workflow which is
already running, and that workflow carries the aggregate it was started with. Building one here
would leave one workflow with two aggregates, and the application would hear that a workflow
started, long after it did.

So this adapter counts a start event only where the process itself holds it. Walking up to the
enclosing process is not enough. That walk gives the same answer for a start event at any depth of
a model. Once every start of a workflow had to be served by a method, no model with an event
subprocess booted any more.

The rule covers the start event of a plain embedded subprocess as well. BPMN allows only a none
start event there, and this adapter reports no none start event anyway, so nothing changes for such
a model. One rule for every nesting is shorter than two.

Two places read a start event and both follow the rule. The deployment reads the BPMN model and
tells the core which starts a process has. The parse listener sees the scope the engine parses a
start event in, and the engine draws the same line there: a start event whose scope is no process
definition becomes a scope start event. So the listener which builds an aggregate goes onto the
start events of the process and onto no other.

`Camunda7EventSubprocessStartsNoWorkflowTest` holds both halves and the event subprocess still
taking a running workflow over. Four models of the integration tests carry such an event
subprocess, and the applications around them boot again.
See [The start of a workflow](./README.md#the-start-of-a-workflow-and-workflows-which-ended).

### 28. The business key is the name VanillaBP gave the workflow, and only that

The id of a workflow is the id of its workflow aggregate, the application assigns it in the
`@WorkflowStartedByBpms` method, and nobody else does. On Camunda 7 the BUSINESS KEY is where that
id is kept. The rule itself and what the core does with it are decision 98 of
`adapter-platform-integration`.

This supersedes decision 24 of this repository. Decision 24 said the opposite: a workflow somebody
started past VanillaBP kept the business key it was started with, and the aggregate was built under
that key so the workflow kept the name its starter was working with. That is no longer true. A key
VanillaBP did not write is refused, and the message says that VanillaBP names a workflow.

Decision 24 was right about the one thing Camunda 7 can answer - a start is the application's own
where the id already has an aggregate - and wrong about what to do with a key which names no
aggregate. Taking such a key over made the starter the one who names the workflow, and from then on
there were two parties naming workflows. An id is a value of the workflow aggregate like any other,
and decision 92 of the platform had already handed the aggregate to the application. So the key
follows the id, not the other way round.

The refusal costs nothing anybody wanted. Somebody who starts a VanillaBP process through
`startProcessInstanceByKey` with a key of their own gets an incident with a message telling them to
use `ProcessService` or to leave the key alone, instead of a workflow which quietly carries a name
the application never chose.

The listener sits on every start event now. Decision 5 says this adapter edits the model it deploys
and what bounds each edit. The start listener used to go on timer, signal and conditional start
events. It now goes on every start event the PROCESS itself holds, the plain and the message one
included. That follows from the rule. Anybody with access to the engine can start any of these
processes, so the kind of the start event says nothing about who started this instance. The state
does: a business key whose workflow aggregate exists is a start of the application's own, no key at
all is a start past VanillaBP, and a key nothing carries is refused. One instance with no key is
nobody's foreign start. A call activity which names the process to call in an expression has
nothing to carry the caller's name, so the instance it starts arrives here without one. Where the
called process works on the caller's workflow aggregate, the listener hands the core the caller's
name and writes it into the instance, and no start past VanillaBP is reported. That is decision 32.
A start event of an event subprocess stays out, which is decision 27, because it fires inside a
workflow which already runs.

A process this application does not serve is left alone. An embedded engine holds every definition
deployed against its database, this application's unclaimed processes and another application's
processes included. The listener sits on the start events of all of them, because the parse
listener sees a model and not a claim, so it asks before it reports: a process no workflow service
of this application serves is none of VanillaBP's business and the listener returns. Without that
question the core would be asked to name a workflow it has no workflow service for, and the engine
would retry the start into an incident.

What it costs is one execution listener per start event in the parsed process definition, and one
load of the workflow aggregate per start of a workflow. The listener runs inside the engine's own
transaction, so the load hits the same persistence context the start is already using, and the
first task of the workflow reads the same aggregate a moment later anyway.

Two things stay invisible, with open eyes. A key somebody chose which happens to be the id of an
existing workflow aggregate attaches that instance to it without a word, and nothing on Camunda 7
can catch that: catching it needs two values naming the instance and Camunda 7 keeps one. And an
application which deletes a workflow aggregate while its workflow still runs makes the next start
of that id look like a foreign one. Both were in decision 24 and both still hold.

`Camunda7ForeignStartIT` walks every case against a running engine.
See [The start of a workflow](./README.md#the-start-of-a-workflow-and-workflows-which-ended).

### 29. Compensation is reported although this engine starts the handlers one after the other

The core warns about a BPMN process which can hold more than one token while its workflow aggregate
has no version attribute, and compensation now belongs on that list: a throw event which compensates
two finished activities starts both handlers, and each handler is an ordinary workflow task writing
the same aggregate. This adapter reads its model for it and reports
`CompensationSpec(throwEventId, handlerIds)` for every throw event which starts more than one
handler.

What this engine really does was measured first, because a message has to say what holds.

It was measured on 2026-09-27, against the embedded engine of Camunda 7.24 on H2, with the model
`api/compensation-facts.bpmn` and the test `Camunda7CompensationTokensTest`: two service tasks, a
compensation boundary event and a handler on each, one compensation throw event.

- The engine creates both compensating executions before it signals any of them. While the first
  handler runs, its parent execution has two children, so the workflow holds two tokens.
- It signals them one at a time, and the second handler starts once the first one returned. No two
  handlers were inside their delegate at the same moment, in any of the three setups measured.
- The order is not stable. The engine sorts the compensation subscriptions by their creation time,
  which is a `java.util.Date`, and `Collections.sort` is stable, so two activities whose
  subscriptions were created in the same millisecond are compensated in the order they ran in rather
  than in the reverse one. Both orders were observed in the same test within an hour. This is not a
  deviation we report, it is what the wiki already says: VanillaBP promises nothing about the order
  of compensation.
- The adapter sets `asyncBefore` on every service task, and the flag IS set on the handlers. The
  engine ignores it there: it starts a compensation handler with `executeActivity`, outside the
  normal flow, where no job is created. One job existed at a time, and the whole compensation ran in
  the transaction of the throw event.
- With the job executor running, both handlers ran on one thread.

So two compensation handlers made of service tasks never write the aggregate at the same moment on
this engine.

The report is made anyway. The check asks whether the process can hold more than one token, not
whether two threads are inside a handler. Both compensating executions exist from the moment the
throw event runs. It is the same answer a forking parallel gateway gets here, where the exclusive
jobs of one workflow are serialised by the job executor as well and the adapter reports the gateway
all the same.

And a handler which WAITS keeps its token while the next handler is started. Two compensation
handlers drawn as user tasks are open at the same time, and the application completes them in two
transactions which may overlap. Leaving compensation out because the everyday case is a service task
would make the check quiet about the case which actually loses a write.

The wording of the core's message follows the measurement: it says the handlers can be open at the
same time, not that they run in parallel.

### 30. A call activity leaving the workflow module is reported, and the error code stays as it is

Everything VanillaBP scopes is scoped per workflow module, a BPMN error code among it. The code a
`TaskException` raises is composed from the module of the process whose task raised it, and the
codes written in a model are rewritten with the module whose file declares them. So when a call
activity calls a process of ANOTHER workflow module, the called process raises its code under its
own module's prefix, the error boundary event of the call activity waits for the caller's, and the
error finds no catcher.

Such a call is possible, through exactly one attribute and only on this BPMS.

Under `use-prefix` a static `camunda:calledElement` is rewritten with the CALLING module's prefix
while the model is prepared, so it can only ever name a process of that module. Under `by-adapter`
there is no prefix, and this engine resolves a called element in the tenant of the calling instance,
which is the calling module's tenant. `camunda:calledElementTenantId` overrides that tenant, and
that is the one door out.

A called element given as an expression is the application's own string and can name anything. No
deployment can resolve it, so nothing is said about one.

On Camunda 8 the door does not exist: `zeebe:calledElement` carries a process id and nothing else,
so the cluster always resolves it in the tenant of the calling instance. That adapter says so where
its code is composed, and reports nothing.

What happens about it is a warning from the deployment, per call activity naming a foreign tenant.
It names the call activity, the tenant, why the error finds no catcher, and the two ways out: let
the called process end normally and report its outcome in a variable the caller branches on, or
move that process into this workflow module. `Camunda7CrossModuleCallActivityTest` holds the
message.

A warning and not a refusal, because such a call runs. A called process which raises no BPMN error
is a model somebody may well have meant, and ending the boot over it would take a working
application down for a defect it may not have.

The code is not composed from the CALLER's module instead. That would be a second rule for one
identifier, decided by which call activity a workflow happened to come through, and both processes
would then carry a name neither of them wrote. The developer who modelled the error is the one who
can answer it, and the boot is where they read about it.

### 31. A handler is held against the iterations around its own task, not against the whole process

When a model is deployed, this adapter refuses a `@WorkflowTask` method which reads the item of a
multi-instance element the model hands none over for. It used to read the elements without an item
PROCESS-WIDE and then ask the core, per task, which of them a method wants. Camunda 8 has always
read the chain of iterations enclosing the task instead. The two adapters therefore gave different
answers to one question, and this entry picks one.

The chain wins. A handler is handed the item of the rounds its own element runs in and of nothing
else, so an element in another branch of the same process cannot reach it whatever that element
names. Refusing over such a pair ends the boot of an application which does nothing wrong, and the
message would name an element the developer cannot connect to the task. The process-wide reading
was the wider net, and everything it caught beyond the chain was a false catch.

So `Camunda7MultiInstanceItems.elementsWithoutAnItem` is gone, and
`refuseHandlersWantingAnItemTheModelHasNot` walks the chain of each task through
`elementsWithoutAnItemAround`. That is the same method the version catalog already used for a
version the engine still holds, so a task is now read one way in both directions.

What is not refused any more is a method which declares `@MultiInstanceElement` for an element it
never iterates in. It gets `null` at runtime, and neither adapter says a word about it. That is a
different defect. The element may well name its item, and finding it takes the core comparing what
a method asks for against the chain the adapter read, which is a roadmap row of its own.

The shape of a compensation finding for a held version was the other half of the same question:
whether a version a BPMS only still holds should report its compensation in the shape the deployed
model reports it in, which is "this throw event starts these handlers", rather than flat among the
other elements that can put a second token into a workflow.

It stays flat, in both adapters. The shaped report exists because a developer reads which throw
event starts which handlers and then redraws the model; nobody can redraw a version the BPMS
already holds, and what is left to say about it is that its workflows can hold more than one token,
which the flat list says. Carrying the shape would take a second method on `ProcessVersionCatalog`,
and that method would buy a sentence nobody acts on. Both adapters say so where the flat list is
assembled.

### 32. A called process named by an expression is asked about while it runs

Camunda 7 hands a called process no business key, and the business key is where this adapter keeps
the workflow aggregate's id. Decision 5 closes that gap in the model: the deployment writes
`camunda:in businessKey="#{execution.processBusinessKey}"` onto a call activity whose called
process works on the aggregate of its caller, and nowhere else, because a process with an
aggregate of its own must not be handed the caller's identity.

A call activity which names the process to call in an expression has nothing for that answer to be
written onto. The model does not say which process will be called, so the deployment cannot tell
the two cases apart, and such a call activity used to be left alone. Measured against the engine,
that model did not run. The called process reached the application with no name. The core read that
as a start past VanillaBP and refused it with "no `@WorkflowStartedByBpms` method builds a workflow
aggregate for it", which is the wrong advice: writing that method would build a second workflow
aggregate and the called process would then run beside its caller's business case instead of in it.
The outbox retried the refusal fifty times and the workflow never moved. The same model runs on
Camunda 8, where the aggregate's id is an ordinary process variable the cluster copies into the
called instance, so one model behaved differently on two engines.

The answer now comes while the workflow runs. The start listener of the called process reads the
call activity which started the instance out of the execution tree, asks the core whether caller
and called process work on one workflow aggregate, and writes the caller's name into the instance
where they do. Where they do not, nothing is inherited and the start stays the start it looks like.

Three other ways were open and none of them holds.

Writing the business key unconditionally is the cheap one, and it trades a loud failure for a
quiet wrong answer. A called process with an aggregate of its own would then be handed the
caller's id, and a lookup of that id in the called process' own persistence either finds nothing
and refuses, or finds a row of the same id and attaches the instance to business data it has
nothing to do with. Decision 28 already names that last case as the one thing Camunda 7 cannot
catch; creating it on purpose is another matter.

Re-evaluating the model's own expression inside an injected business-key expression keeps
everything in the model, and it evaluates the application's expression twice, breaks on a
`calledElement` which mixes literal text with an expression, and has to be rewritten again by the
scoping of decision 3. Too much cleverness for one attribute.

Leaving the behaviour alone and writing the difference into both READMEs was the third. It makes
the portability of a model a promise only one engine keeps, and this is a model an application
writes on purpose, so the difference would be the first thing a reader of both adapters finds.

What this costs is one question per start of a called process which arrives without a name, and
the core answers it from a map it filled while the application started. The adapter asks through
`Camunda7TaskRegistry`, which the deployment service hands the core's answer to the way it already
hands over the process versions, so nothing new reaches the listener.

Decision 22 keeps the answer about a shared workflow aggregate in the deployed model, because the
multi-instance walk is reached from an execution and the core was not at hand there. That is why a
statically named call activity carries its note, and the note is what `Camunda7MultiInstances`
reads. The start listener is built with the core and can ask, so the business key crosses a call
activity the model names in an expression. The walk was still ending there when this was decided,
which was a difference between the two mechanisms rather than one between the two engines, and
decision 34 closed it by handing the walk the same registry.

`Camunda7CallByExpressionIT` runs the same called process reached both ways against the engine, and
`Camunda7CalledProcessStartTest` holds the four cases the listener tells apart.

### 33. A decision named by an expression gets the prefix in front of the expression

Under `use-prefix` the decisions of a workflow module are deployed under prefixed ids, and the
`camunda:decisionRef` of the business rule tasks calling them is prefixed with them. A `decisionRef`
written as an expression was left exactly as the application wrote it, because the model does not
say which decision a run will pick. It now gets the prefix written in front of it, the same as the
`camunda:calledElement` of a call activity. Camunda 7 reads the attribute as one expression: the
text in front stays text and the engine looks up the prefixed id of whatever the expression yields.

Measured against the engine before anything was changed. `Camunda7DecisionByExpressionTest` deploys
one model twice, once scoped and once not, with the decision of the module beside it and the parse
listener of this adapter in the engine, so the business rule task runs in a job of its own the way
it does in an application. Without a prefix the run reaches the decision and the task writes its
result. Under `use-prefix` the job failed with "no decision definition deployed with key
'creditRating' and tenant-id 'null': decisionDefinition is null". The engine retries such a job and
leaves an incident, so the workflow stops at that task and the application sees a model which never
gets past it. Nothing warned about it while the module was deployed, because the wiring check leaves
a business rule task with a `decisionRef` alone on purpose: the engine serves it, not the
application.

Reporting the case at deployment instead was the other way, and it was not taken. It would refuse a
model this mode could carry, and the engine carries it: the composed expression is measured above.
It would also make `use-prefix` the one mode in which a decision cannot be picked at runtime, while
Camunda 8 does the same thing with the same model (decision 55 of the Camunda 8 adapter writes the
prefix into the `decisionId` of a `zeebe:calledDecision`). Both adapters now answer the same for the
same file, and that is what a portable model needs.

What the application loses is a reference out of its own module: the expression always resolves
inside the prefix of the module whose model carries the task. That is the limit `use-prefix` already
had for a decision named by a literal, and the README says so in its `Decision tables` section.
A module which has to reach a decision somebody else deployed stays with `by-adapter`.

This follows decision 3, which says the identifiers the engine resolves across definitions are
scoped, and decision 5, which says the adapter only changes a model in ways its author can predict.
The prefix in front of an expression is the same change the author already sees on a call activity.

### 34. The multi-instance chain crosses a call activity named by an expression

Decision 22 keeps the answer about a shared workflow aggregate in the deployed model, and for a
call activity which names the process to call in an expression there was nothing to write it onto,
so the walk ended at such a call activity the way it ends at a foreign aggregate. Decision 32 made
the business key cross it, by asking the core while the workflow runs: the deployment service hands
the one answer to `Camunda7TaskRegistry`, where the start listener of the called process reads it.
The walk could have asked the same way and did not, so one model reported the iteration of its
caller on Camunda 8 and reported none here. `Camunda7MultiInstanceByExpressionIT` measured that
before anything was changed. In one model and one run, the four calls of a call activity named by
an expression reported `nothing` and the one call of a call activity named by its id reported its
level.

This entry closes it. The walk asks the core through the same registry, and it asks only where the
deployment could not answer. Where the model spells the called process out, the note written while
that model was deployed stands and nobody asks again. So a workflow standing in an older version
of a process still gets the answer that version was deployed with, whatever the declarations of the
application say today, which is the part of decision 22 this leaves alone. Both mechanisms read one
method for it, `Camunda7CallActivities.continuesTheCallersWorkflowAggregate` for a pair of running
executions, so the business key and the iteration cannot give one pair of processes two answers.

The caller's chain could have been written into the called instance as a process variable instead,
the way the Camunda 8 adapter writes `vanillabpMiParents` as an input mapping. That needs no core
while the workflow runs and it would answer for every reader of the walk. It also copies the core's
answer into a place an application can read and overwrite, and Camunda 8 pays for that with three
test cases about a model which writes that variable itself. The core's answer is the one source of
this truth, so it is asked rather than copied.

Two readers of the walk still end at such a call activity, because they hold no registry. One is
any caller of `Camunda7MultiInstances.of(execution)` or `of(engine, executionId)` outside this
adapter, where the overloads taking a registry are the ones to use. The other is the Camunda 7
adapter of the business cockpit, which reads the walk for the details of a user task and has the
registry at hand through `Camunda7EngineFacts`. Passing it there is one argument in one call, and it
belongs to that repository rather than to this one.

### 35. A compensation runs in one transaction, and the deployment says so

Decision 5 lets this adapter write `asyncBefore` and `asyncAfter` on every service-like task, which
is what gives each of them a job and therefore a transaction of its own. A compensation handler is
the one place where the engine does not follow. It starts such a handler outside the normal flow,
where no job is created, so all the handlers of a throw event run in the transaction of that event.
The promise stands everywhere else. This is the exception beside it, and the deployment names it.

Measured on 2026-10-01 against the pinned engine 7.24.0 by `Camunda7CompensationTokensTest`, with a
throw event compensating two finished service tasks. Both handlers ran in the same command context,
which is the engine's own unit of work, and one commit covered the two of them. The two activities
they compensated ran in a transaction each, so the flags do work where the engine makes a job of an
activity. And a handler which threw sent the other one back to work: the engine rolled the whole
compensation back, counted one retry off the single job and ran both handlers again on the next
attempt. Decision 29 had read the jobs and the thread on 2026-09-27 and those numbers did not move;
the command context, the commit and the retry are new.

A warning and not a refusal. The model is right and no flag of this adapter changes what the engine
does, so ending the boot would stop an application which did nothing wrong. What a reader can
change is the handler, which is why the message asks for one that may run twice and for work the
engine cannot roll back to stay out of it. It names the throw event and the handlers it starts,
because the cost grows with their number: five handlers and their side effects in one transaction
means the work of the first four is only as safe as the fifth.

Making up for it inside the adapter is not what this decision answers. The flag the adapter writes
at parse time IS the seam this engine offers, and the engine ignores it there, so anything beyond
that would be VanillaBP running the compensation instead of the engine. Whether it should is a
question of its own, it has a roadmap row of its own, and this decision only says what the engine
does and what the deployment says about it.

Camunda 8 hands out a job per handler, so the same model is expected to run in a transaction per
handler there. That is read off the model reading of the Camunda 8 adapter and not measured on a
cluster, and only a run against a cluster would settle it.

### 36. A user task nothing serves is named in a claimed process, and nothing is refused

A user task of this engine runs without a `@WorkflowTask` method. The engine creates the task, it
stands in a task list, somebody finishes it and the workflow moves on. That is why the core hands a
user task over as an OPTIONAL spec, and `validateTaskWiring` filters those out before it asks for a
method. Nobody used to say a word about it, so an application which drew a notification into its
model and forgot the method found out in production, if at all.

From now on the deployment names such a task. Once per BPMN process, at INFO, while the model is
wired, and only for a process a `@WorkflowService` class of this application claims. The message
names each element, the name the modeller wrote on it, its form key or that it carries none, and the
method which would serve it. It ends by saying that a model whose user tasks are worked through a
task list alone needs no change.

This is not the refusal Camunda 8 has. Decision 53 of the Camunda 8 adapter refuses a user task a
job worker serves in a claimed process. The reason it gives is what the shape costs: the cluster
hands out a job, nothing fetches it and the workflow stands at the element with no incident and
nothing in any log. Camunda 7 has no such shape. Every user task here is the engine's own, and the
application is the only thing which can be missing.

So the rule is the same where the two can be the same, and it stops where the engine stops. What is
taken over is the split: a process this application claims is a process it stands in for, and a
process nobody claims is somebody else's model which reached this engine because of the file it sits
in. What is not taken over is the level. A refusal would end the boot of an application whose model
is right, which would be stricter than the core, whose own field says a handler is optional. A WARN
would be the same claim in a quieter voice, on every boot, for a model nobody has to change. The
third option, a report which only speaks where the process is claimed, says the one thing which may
be news without asking anybody to act on it.

Refusing it, the way Camunda 8 refuses its own case, was rejected: it would refuse the plain
Camunda 7 model of version 1 and of every application which works its user tasks through Camunda's
Tasklist. There is nothing to fix in such a model, and a flag to switch the refusal off would be a
flag for the normal case.

A WARN instead of an INFO says that something needs attention. Here the model may be exactly what
the modeller meant, and a warning on every boot for a correct model is how a log teaches people to
skip warnings.

Saying it for an unclaimed process too is what the Camunda 8 adapter does, and it has a reason
there: the workflow of an unclaimed process really does stand at the element. Here nothing stands,
and no method of this application was ever meant to serve those tasks. The core already names the
unclaimed processes of a workflow module once, which is where that belongs.

The check could also sit in the core, which holds `optional` and knows which method serves which
spec, and it could then name the unserved optional specs of a claimed process once for all adapters.
Two things speak against that for this release. The way out differs per BPMS, and the message is
only worth reading where it is concrete: here it is a form key, on the Process-Engine-API an external
form reference, on Camunda 8 a form definition of a Camunda-managed task. The core also sees only
the specs an adapter chose to hand over, and a Process-Engine-API user task without an external form
reference never becomes a spec at all, so the core could not name the case which loses the most. A
report in the core would change the Camunda 8 adapter's boot output as well, which this entry does
not touch.

What this leaves open is the Camunda 8 adapter, which stays silent about the same case for a
Camunda-managed user task whose external form reference no method names. Three adapters then say two
different things about one situation. Closing that is either one more story for that adapter or the
core-side report above, and whoever takes it should start from this entry.

`Camunda7UnservedUserTasksTest` holds the message, the element without a form key, both keys a
method may be wired by, the silence about an unclaimed process and that the line is an INFO.

### 37. The expressions of a model are reported for the model being deployed, not for the versions the engine still holds

The core is told the expressions of a process while that process is wired, so what it hears is the
model this application version brings. The engine keeps the older versions, workflows are still
running on them, and those models are not read for this.

The way to read them is there. `Camunda7ProcessVersions` walks the models the engine holds for
`concurrentTokenElementsOfVersion`, and the same walk would answer this question. So the question
here is what such a message would be worth.

An old model says nothing new. An expression which reads a path reads the same path in every
version which carries it, and the message names the element, the place and the expression. What
differs is that nobody can act on an old model. A model in the engine cannot be edited, the
deployed one is where a developer writes the plain getter the message asks for, and the workflows
on the old version run out on their own. A warning about them would ask for work nobody can do.

There is a second reason, and it is the count. The message ends with how many of the expressions of
this process name a variable and nothing else, which is what tells a developer how far their model
is. Counting the held versions as well would count the same expression once per version, and the
number would stop meaning what it says.

`ConcurrentTokenCheck` asks about held versions for a reason this check has not got. A parallel
gateway the newest model dropped keeps forking every workflow which started before it, so the
finding only exists in the old version. An expression is not like that. It is read where the model
carries it, and the model carrying it is the one being deployed.

Whoever wants the held versions in the message needs a piece of work of its own, and the place to
hook it is `Camunda7ProcessVersions`.

### 38. A user task reports the multi-instance levels a service task in its place reports

Camunda 7 notifies a `@WorkflowTask` method about a user task from a task listener, and a task
listener is handed a `DelegateTask` rather than an execution. The multi-instance walk of decisions
22 and 34 needs an execution, so the context built for a user task answered the SPI default of
`TaskInvocationContext#getMultiInstances`, which is an empty map. Every other kind of task reported
its levels, and the same model reports them on Camunda 8, so one model answered two ways again.

Measured against the pinned engine 7.24.0 before anything was changed. A `DelegateTask` hands out
the execution its task waits on, that execution is an `ExecutionEntity`, and the walk reports the
enclosing multi-instance subprocess as well as a multi-instance user task's own round from it. It
answers while the task is created and while it is cancelled.
`Camunda7MultiInstancesAtAUserTaskTest` holds both.

What the gap cost was worse than an empty map. A named `@MultiInstanceElement("...")` parameter
does not become `null` where the level is missing: the core refuses the call, the task listener
fails, and the engine rolls the transaction which creates the user task back. Measured by
`Camunda7MultiInstanceByExpressionIT#aUserTaskIsToldTheIterationItRunsIn` against the unchanged
adapter, the workflow never got past its start and the outbox kept retrying the refusal,
twenty-five times in that one run. A `@MultiInstanceElement(resolverBean = ...)` parameter is the
silent half of it, because an empty map is a map the resolver can read.

So the user-task context asks the walk the way the service-like context asks it, with the registry
of its engine. The registry is what crosses a call activity naming the called process in an
expression (decision 34), and the user-task context now carries it instead of the adapter id and
the datasource flag it used to be given, which it reads off the registry.

Nothing of decision 22 or 34 changes. The walk is the same walk, the question about the workflow
aggregate is the same question, and the third reader of it now holds the registry which the other
reader in this repository already held.

### 39. A delivery is recorded in both datasource modes, and only the deduplication differs

Story 879 of the platform gave a delivery which names no id a record of its own. Such a record
takes no part in the deduplication, and it still answers the two other questions a record answers:
which BPMS holds the task, and which kind of id the id of that task is. A caller who completes a
task which is gone is then told which method to use, instead of a list of guesses. So this adapter
writes a record on the application's datasource as well as on an engine datasource of its own, and
it writes one for a user-task notification in both modes too.

The deduplication is what decision 6 says it is. On the application's datasource nothing is
deduplicated, because the handler runs in the engine's job transaction and a job the engine hands
out again proves that nothing was committed. A user-task notification is not deduplicated either,
not even on an engine with a datasource of its own, because no delivery of a user task is named: one
transaction creates every user task the token reaches, and the id of such a task comes into being
while it is created.

The reasoning of decision 6 holds as it stands. What was too wide is the sentence drawn from it: it
said that nothing is written down where the truth is that nothing is deduplicated.

`Camunda7UserTaskDeliveryRecordIT` holds what a user-task notification leaves behind, and
`Camunda7RepeatedDeliveryIT` holds the two datasource modes side by side.

### 40. A workflow is served from the model of its own version

Camunda 7 evaluates the expressions of the model a workflow was started with. Two versions of a
process may name different expressions at the same element. For a process the application
deploys, the adapter used to know the tasks of that one model and nothing else. A workflow of an
older version then asked for the old name, the adapter did not know it, and the lookup by element
found the task of the newer version at the same id. That workflow ran a method its own model never named. It did
so when the old method was still there, and it did so when the old method was removed, where the
startup check had just said that the workflow would end in an incident instead.
`Camunda7HandlersOfTheOwnVersionIT` measured it for a task wired by `camunda:expression`, a task
wired by `camunda:delegateExpression` and a listener written as `camunda:expression`. All three ran
the method of the newer version.

So the lookup asks for the tasks of the process definition a workflow runs on. The registry reads
that model the first time a workflow of the definition asks, with the extraction a deployed model
goes through, and keeps the answer. A definition never changes, so the answer never goes stale.
The model comes from the execution itself, out of the engine's deployment cache. The task
listener of a user task and the listener which reports a cancellation ask the same way.

Two other ways were possible. Wiring every version the engine holds while the application starts,
as `wireTheVersionsHeldUnder` does for a declared process id, would register the old names, so the
lookup by name would find them. But it keeps every version in one list, so the lookup by element
can still cross versions, and decision 10 asks a start not to grow with the number of versions.
Putting the version into the key of the registry needs the same model read and ends up as this
decision with more code. Reading per definition is exact, costs one read per version a workflow
really runs on, and also covers a version another node deployed after this one started.

A model the extraction refuses gets no task at all and one warning, the way decision 13 handles a
held version under a declared id. Its workflows then end in an incident at their next task.

What the application sees when a method of an older version is missing does not change: the core's
check of held versions reports it while the application starts, and the workflow ends in an
incident at that task. The incident names the task definition, the element and the version. Only
the method of the newer version no longer runs in its place. A listener whose method is gone is no
VanillaBP listener any more, so the engine evaluates its expression itself and the incident names
the expression.

`Camunda7TasksOfTheOwnVersionTest` holds the lookup, and `Camunda7HandlersOfTheOwnVersionIT` holds
the upgrade against a running engine.
