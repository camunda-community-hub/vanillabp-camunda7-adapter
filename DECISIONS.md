# Decision log

The code of this repository points at the decisions below. A number is handed out once. It is never
reused or renumbered, so a citation always finds its entry. A decision which gets overturned keeps
its entry. The entry is marked as superseded and names the entry which replaced it.

A citation in code reads `see decision 3 in the repository's DECISIONS.md`. It names an entry of
THIS repository only. A decision which the platform shares has its own entry in
`adapter-platform-integration`, written from that side. A pointer into another repository breaks
easily, and this log exists to avoid that kind.

The links below point into this repository's [`README.md`](./README.md). The README has the details
an entry leaves out on purpose.

### 1. The workflow aggregate is shared as process variables

Camunda 7 runs embedded, so the EL resolver (the part of the engine which evaluates expressions)
could read the aggregate live. But exactly that makes a model portable in one direction only:
`${riskAcceptable}` would work here and fail on every remote BPMS. So the adapter writes the values
an aggregate shares as process variables, at every point where it talks to the engine. The engine
evaluates its expressions against those variables.

Reading an attribute through the EL resolver stays as a migration fallback for workflows started
before. It answers only where the engine has no variable with the name an expression asks for. The
fallback will be removed, but no version is named for that, because nobody can promise a date
today. Whoever needs a date asks the VanillaBP team. So no message, README or wiki names a version.

Instead of a version, a reader can watch the fallback itself. It warns once per workflow module,
process and name that it answered an expression by reading the aggregate. It also says, at most once
an hour, how many workflow instances it has served since the application started. That count falls
on its own, because a workflow stops needing the fallback as soon as it reaches a point where this
adapter writes the shared values. A count which keeps growing says something else: a model reads an
attribute which is not shared. The startup check names those expressions.

`Camunda7UnsharedExpressionCheckIT` asserts the sentence the startup check prints about the
fallback. So a message which names a version again fails the build.
See [Sharing the workflow aggregate](./README.md#sharing-the-workflow-aggregate).

### 2. A workflow is progressed after the caller's commit

Every operation which moves a process forward is scheduled through the phase-two outbox, the same
way a remote BPMS works. Sharing the caller's transaction was possible, but it is not enough. An
engine command which loses a concurrency conflict cannot be repeated inside that transaction,
because the conflict marks the transaction rollback-only. And repeating just the engine part would
move the process forward while the application rolls back.

Phase one still asks. An embedded engine answers for free and in the same transaction, so a task
which is gone or a workflow which is unknown is reported where the application called. What phase
two does is idempotent (doing it twice has the same effect as doing it once), because the outbox
delivers at least once. A test which called VanillaBP has to wait for the engine to catch up. It
cannot read the engine's state in the next line.

### 3. Workflow modules are kept apart by scoping the identifiers

Camunda 7 has tenants, but a workflow module may also put a prefix on its identifiers instead. Then
there is no tenant to ask. So the adapter always talks to the engine with the SCOPED identifiers:
process ids, decision ids, message and signal names, error and escalation codes. The core's
registries stay keyed by the plain ones, and the adapter translates a delivery from the engine
before the core sees it. A task definition is not on that list. Camunda 7 keeps it inside the
process, so it stays as the model wrote it (decision 17). The mode is configured per workflow
module, so no code may assume either shape.
See [Keeping workflow modules apart](./README.md#keeping-workflow-modules-apart).

### 4. A class opens its fields one by one, not as a whole

The process service, the deployment service and the engine holders of this adapter have dozens of
fields. Most of them are collaborators which nobody outside the class needs. Which of them a caller
may read is part of the surface of the class. So each field gets its own accessor, and `@Getter` on
the class is refused even where an IDE offers it. It would publish the current field list, and then
also every field a later change adds.
`@SuppressWarnings("LombokGetterMayBeUsed")` on such a class keeps that offer from coming back.

### 5. The adapter changes the BPMN it deploys, and only in ways the model's author can predict

An embedded engine offers no other place to hook in. A remote BPMS gets some things for free from
its own protocol. This adapter has to put them into the model before it is deployed. `prepareBpmn`
and `wireBpmn` write two things into the deployed BPMN. The first is the business key handed into a
call activity which runs on the SAME workflow aggregate, together with the note which says so
(decision 22). The second is the scoped identifiers of decision 3. The parse listener adds the rest
while the engine parses the model, so the deployed XML does not show it:

- the `asyncBefore`/`asyncAfter` flags which make a service-like task a transaction boundary,
  except for a compensation handler, where the engine ignores them (decision 35),
- built-in task listeners for the user-task events,
- execution listeners for the start and for the end of a workflow.

Each of these follows a rule which keeps the deployed model predictable. The end listener is added
only where a `@WorkflowEnded` method exists. The start listener goes onto every start event the
process itself holds and onto no other (decisions 27 and 28). The business key is not injected where
the called process has an aggregate of its own, or where the application modelled a
`camunda:in businessKey` itself. The scoping rewrite runs once per FILE and not once per process,
because all processes of one file share a model.

All of this happens only to a process which a `@WorkflowService` class of this application claims
(decision 43). The parse listener and `wireBpmn` leave a process nobody claims, and a process
somebody else deployed, exactly as modelled, flags included. The wiki lists what the adapter adds in
[What the adapter changes in the BPMN it deploys](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Home#what-the-adapter-changes-in-the-bpmn-it-deploys).

### 6. A task handler runs inside the engine's own job transaction

Camunda 7 delivers a task inside the transaction of the job it is executing. So this adapter runs
the handler in that transaction and does not open one of its own. This is what makes the three
outcomes exact:

- a `TaskException` throws a `BpmnError`, and the aggregate is COMMITTED,
- a handler which keeps the task open leaves the activity open,
- any other exception rolls the job transaction back. The engine then lowers the job's retries and
  delivers again.

So here the engine's retry IS the recovery. On the application's datasource nothing is
deduplicated: a job the engine hands out again proves that nothing was committed. An application
which gives the engine a datasource of its own loses that proof.

On such a datasource the answer is different. An engine on a datasource of its own runs its job
transaction on a resource which the application's persistence cannot join. So VanillaBP opens the
transaction for the handler and the workflow aggregate. That transaction commits before the job
does, and a job the engine hands out after that is a repeated delivery of committed work. Such an
adapter id answers `deliversTasksAtLeastOnce()` with `true`. It names each delivery by the id of the
job at hand, and the engine keeps that id across its retries.

A delivery is recorded in both datasource modes, and only the deduplication differs. Story 879 of
the platform gave a delivery which names no id a record of its own. Such a record takes no part in
the deduplication. It still answers the two other questions a record answers: which BPMS holds the
task, and which kind of id the id of that task is. So a caller who completes a task which is gone is
told which method to use, instead of getting a list of guesses. So this adapter writes a record on
the application's datasource as well as on an engine datasource of its own. It also writes one for a
user-task notification, in both modes.

A user-task notification is not deduplicated in either mode, because no delivery of a user task is
named. One transaction creates every user task the token reaches, so the job id would name several
notifications. And the id which is unique per task is generated while the task is created, so it
does not survive the rollback which causes the repetition.

`Camunda7UserTaskDeliveryRecordIT` holds what a user-task notification leaves behind.
`Camunda7RepeatedDeliveryIT` holds the two datasource modes side by side.

### 7. `table-prefix` says the tables are already there

Camunda's own schema management ignores the prefix and creates `ACT_*` tables without it. That is
not a guess. The engine says so itself in `ProcessEngineConfigurationImpl#setDatabaseTablePrefix`,
and its shipped DDL names every table literally. So `table-prefix` in this adapter means that
somebody else created the prefixed tables. Two failures with a guiding message keep the
misunderstanding out: a prefix together with a `database-schema-update` which creates the schema,
and a prefix without the tables. Both are checked before the engine is built. This also keeps
stray tables without the prefix out of a shared database.

We tried to rewrite the shipped DDL in the adapter and rejected it. Every substitution which looks
right either hits the `ACT_ID_` and `ACT_INST_ID_` COLUMNS or leaves an index name behind. Neither
shows up before something runs. See
[Two engines on one database](./README.md#two-engines-on-one-database-table-prefix).

### 8. A probe and a write answer for the scope of the call, never for a business key alone

Aggregate ids are unique per aggregate type, not across an application. So two workflow modules
whose aggregates count from one both have an id `1`. So every probe filters on the server side by
the scoped process definitions of the calling `WorkflowScope` and by the tenant. It does so for
running and for historic instances. The same filter narrows the instance lookup behind
`aggregateChanged`.

The write matters more than the answer. Setting a variable in Camunda 7 makes conditional events
evaluate again. And the technical marker is written even where the aggregate shares nothing. So an
unfiltered lookup would not just report a foreign workflow wrongly, it would move it forward. Two
instances inside the adapter's own scope break an assumption. They end with a message which names
the aggregate, the scope and both instance ids. The branch which got a task id has no filter,
because an execution id names exactly one execution.

### 9. A nested shared value travels in the engine's own serialization format

Our first choice was to write shared values as a JSON string. We dropped it: an expression with dots
has to walk into the value, and it can only do that when the engine holds the value as an object
variable. So `serialization-format` is resolved per workflow, workflow module and adapter. The
adapter also honours the engine's `defaultSerializationFormat`.

That needs a data format in an embedded engine. A data format needs a process engine plugin, and
this adapter refused plugins before. Now it applies every `ProcessEnginePlugin` bean of the
application. `engine-plugins` configures a plugin by class name. Its properties go to Camunda's own
`PropertyHelper`, so the values convert exactly as they would in a `bpm-platform.xml`. Without a
format the adapter warns once that Java serialization applies.

### 10. A start asks the engine for numbers, and asks as many of them on the last day as on the first

While an application boots, this adapter answers questions from tables which grow for as long as
the application is in production. How many workflows still run on an old version of a process? How
many of them will the configured scope never reach? Which versions does the engine hold, and what do
their models look like? A start of ten seconds must not become a start of two minutes because the
application did its job for two years. The platform states this rule for every adapter as decision
19 of its own DECISIONS.md.

For Camunda 7 that means two things. First, a question about a quantity is a `count()`, and the
engine answers it from an index. Fetching the executions and counting the list gives the same
answer, but at a price which rises every year. Second, the adapter asks a definition query once for
the whole process. `fetchDeployedVersions` reads every version anyway, so it keeps the definition
ids it saw. The questions which follow are answered from those ids and do not ask again.

What does grow is the number of versions the engine holds: one per deployment which changed a
model. The questions about older versions grow with it. We accept that on purpose. Those questions
are what the check is for, and with `outfaded-versions` an operator says which versions are no
longer interesting. `Camunda7StartupQuestionCostTest` counts what a start asks.

### 11. What this adapter does per operation is a handler, not a pair of methods

VanillaBP's adapter SPI (the interface an adapter implements) used to ask for two methods per
outbound operation. This adapter had eighteen of them: nine phase-one checks and nine phase-two
actions. Most of them were a single line which called a private helper. A new operation meant two
more methods in every adapter, next to the four places the core needed for the same operation.

The SPI now asks for a map instead: one `PhaseOperationHandler` per `PhaseOperation`. Each handler
is the pair of "ask" and "act" for this engine. Everything else about an operation belongs to the
operation. This adapter provides that map, and the eighteen methods are gone. What the handlers do
has not changed. They call the same helpers the methods called, so nothing about the engine, the
checks or the idempotency moved with them.

Two things matter for whoever adds the next operation here. First, the map says what this adapter
serves. An operation missing from it is one this engine has nothing like, and VanillaBP refuses to
boot if one is missing which every adapter has to serve. Second, the phase-one half is where this
adapter is most useful, because an embedded engine answers from the caller's own transaction. The
platform's decision 29 says why the operation itself carries no engine knowledge at all.

### 12. A suspended process definition counts, and the only way past it is a system property or its environment variable

Deleting a process definition really removes it here. The engine drops it from the database and the
definition query no longer returns it, so the startup check for old process versions stops reporting
about it by itself. Camunda 7 also offers suspending, and that is not the same. A suspended
definition comes back the moment somebody resumes it. Its workflows never went away, and a
`@WorkflowTask` method missing for one of their tasks is still missing afterwards. If the check went
quiet because somebody suspended a definition, it would drop the finding while nothing is settled.
The finding would come back as an incident on a live workflow, and the check exists to prevent
exactly that. So the definition query names no suspension state, and a suspended version is checked
like every other one.

There is one situation this rule cannot cover: an application has to run now, and nobody can clean
up an old version at this minute. For this case there is `vanillabp.ignore-suspended-process-definitions`,
and next to it the environment variable `VANILLABP_IGNORE_SUSPENDED_PROCESS_DEFINITIONS`. Where
both are set, the property wins. Only the value `true` counts, in any case. Any other value is
reported and changes nothing.

It is a system property and not a configuration key. This is the part most likely to be "fixed"
later by somebody who does not know why. A configuration key lands in an `application.yaml` and gets
committed. Then it stays set forever, and nobody notices it again. This switch is meant to be a
decision for exactly this start, typed where people can see it. That is also why every start says
out loud that the switch was used and which versions it hid. And it is why nothing about this is
remembered.

The environment variable is an equal way in, not just a convenience. A Quarkus native image reads
neither `JAVA_OPTS` nor `JAVA_TOOL_OPTIONS`, and a container usually carries its `-D` arguments
there. Without the variable, the switch would do nothing at all in a native image and also say
nothing about it. An emergency exit which quietly fails is worse than none.

The name has no adapter part and no adapter id. It holds for every VanillaBP adapter whose BPMS can
suspend a definition (today only this one), and somebody who looks for a way out in an emergency
should find one switch, not three. An application which runs two Camunda 7 adapter ids cannot use
the exit for only one of them. That is the price, and we pay it on purpose.
`SuspendedProcessDefinitions` is the single place where all of this lives.

### 13. The workflows of a renamed process are served from the models the engine still holds

A workflow module may declare a BPMN process id under which it deploys nothing. This is how a
renamed process keeps being served. Camunda 7 evaluates the expressions of the model a workflow was
STARTED with. So those workflows need the connectables of a model which this application no longer
has: the expression text of every task, the element it is evaluated at, and the way the task is
wired. The engine has that model in its own repository. So the adapter reads it from there, version
by version, through the same extraction a deployed model goes through.

The alternative was to build the connectables from what the application says it serves. The core
names that (`taskWiringOfProcessesNobodyDeployed`), and it is enough on a BPMS where a subscription
is a name. Here it is not enough, because a connectable is more than a name. A `camunda:expression`
task completes when its handler returns. A `camunda:delegateExpression` task can stay open for a
`@TaskId` method. This difference decides what the EL resolver hands back to the engine, and no list
of task definitions says which of the two a task is. A guess would either close a task which has to
stay open, or keep one open which nobody will complete.

Reading the models has two effects. First, every version the engine holds is wired, because a
workflow may sit on any of them. A task which several versions share is registered once. Second,
the wiring validation does NOT run over these versions. An earlier generation of this application
deployed them. A task the application dropped since then is what the check for old process versions
reports, together with the count of the workflows affected. Ending a boot over a model nobody can
change any more would be the wrong tool for that finding.

If the extraction refuses a model, the adapter skips it with a warning and does not end the boot.
The same extraction refuses an external task and an expression VanillaBP does not understand. A
model which THIS BOOT brings should be refused that way, because it can still be fixed. A model
deployed years ago cannot. So the versions which can be wired are wired. The one which cannot is
named, together with what a workflow on it runs into.

This needs the definition query which the version check runs anyway. `fetchDeployedVersions` reads
every version of the process and now keeps the definition ids by version. So reading the models
costs no query of its own, as decision 10 asks.
`Camunda7DeclaredProcessWiringTest` holds the task kinds and the skipped version.
`Camunda7RenamedProcessIT` holds the whole thing against a running engine.

### 14. An extension reaches the engine through a customizer, not through the engine

The Business Cockpit of version 1 got its engine hooks from Camunda's Spring Boot starter. An
engine plugin carried its parse listener, and the starter's history eventing told it that a
workflow had started or ended. This adapter builds the plain engine itself, because both starters
are locked to platform releases which have reached their end of life. So neither hook exists any
more, and an extension had no place at all to put a listener.

`Camunda7EngineCustomizer` is that place. It is SPI for Camunda 7 only, and it lives in this
repository, not in the VanillaBP core. Engine hooks belong to the engine's adapter. A core which
knew about BPMN parse listeners would know about Camunda 7.

A customizer can contribute three things, and the shape of each one matters. Parse listeners go
BEFORE or AFTER VanillaBP's own. That decides the order of the listeners they attach to an element.
A built-in task listener contributed "after" runs after the one the adapter attached. An extension
which tracks user tasks needs this order. The order comes from Camunda's own pre/post parse-listener
lists, not from anything this adapter arranges
(`Camunda7EngineCustomizerIT#theParseListenerSeesTheUserTasksAfterVanillaBp` pins it). A history
event handler is installed as a COMPOSITE next to the engine's own. So the history level and
everything else about history stays as configured, and history is still written. And `customize`
hands over the configuration itself, for what the other two do not cover.

A customizer is asked once per configured adapter id, with that id. Two ids are two engines. An
extension which registers something per engine has to be able to tell them apart. For the same
reason every other per-adapter object of this adapter exists once per id.

### 15. A check reads the engine's models without asking who deployed them

The platform's decision 38 has the rule. A check against a BPMN model must not depend on which
application version deployed the model it judges. And a check which cannot see every model that
could hold the answer stays silent and does not refuse. In this adapter the rule shows up in three
places.

The engine STARTS workflows of an id which is only declared on its own. The timer of the old model's
latest version keeps firing after the rename, and its signal subscription keeps matching. So every
listener which serves such a start has to find its way back from the engine's definition key to the
workflow module and the plain id. This registration happens the moment the core asks for the id's
version catalog (`processVersionCatalogOf`), not later. The reason: the first read of that catalog
makes the engine parse the old definitions. The parse listener uses exactly this registration to
decide whether to attach an end listener. And a parsed definition stays cached, so registering
after the parse loses the end notification for good. The plain signal names of the signal start
events of a held model are read from the engine's own copy while its versions are wired. Nobody
registered them at deployment, because nothing was deployed.

The refusals of the extraction judge a model which is being DEPLOYED. A model the engine already
holds is only being read. Two paths read it: the wiring of a declared id (decision 13 skips such a
version with one warning) and the startup check about older versions. That check used to END THE
BOOT over an external task (`camunda:topic`) in a version deployed years ago. Both read paths warn
now. They name the version as a version, and no longer put it into a message slot which said "file".
The check answers for the rest of the model.

`Camunda7DeclaredIdRuntimeIT` measures the starts and the end against a running engine under
`use-prefix`. `Camunda7HeldModelReadingTest` measures the reading, which never refuses.

### 16. A number the engine has no variable type for keeps its class

Camunda 7 stores a short, an integer, a long, a double, a boolean, a string, a date and bytes as
themselves. A `BigDecimal`, a `BigInteger` and a `Float` fit none of those. This adapter used to
widen them to a double on the way in. So the value a model read was not the value the application
held: `120.50` shows as `120.5`, a `BigInteger` above 2^53 loses its last digits, and a `Float` of
`0.1` shows as `0.10000000149011612`. Version 1 read the live workflow aggregate and had none of
these problems.

The conversion did not help either. As soon as one operand of a relational operator is a
`BigDecimal`, EL converts both operands to `BigDecimal`. So `${total > 100}` compares numbers
against the object itself. Equality is the one case where this does not help: `${total == 120.50}`
converts the same way and then calls `equals`. So it is false against the live object, and it was
true against the widened double. Version 1 also answered false, because it held the live object
too. A model which compares a decimal for equality was already wrong before the upgrade.

So those three types travel as object variables, like every other value the engine has no type
for, and in the format decision 9 names. The `Character` conversion stays. EL compares a character
to `'A'` and to `"A"` the same way, so writing it as a string loses nothing and gives a readable
variable.

The price is that the format then decides what the value reads as. This adapter reports that and
does not work around it. A startup check writes a sample of the type through the engine's own
serializer for the configured format. Where what comes back is not what went in, it warns, per
attribute the models read. It measures and does not judge from a list of types, because the data
format belongs to the application. A warning about something which works is worse than a missing
one. Where the adapter's own configuration names no format, the check says nothing at all. It also
says nothing where it cannot measure: when there is no sample of the type, no serializer for the
format, or an engine which will loudly refuse the first push by itself.

`Camunda7VariablesTest` holds the rule. `AbstractNestedExpressionsIT` with its two subclasses holds
what each format answers at runtime. `Camunda7SerializationRoundTripTest` and
`Camunda7LossyFormatCheckIT` hold the check.

### 17. The engine answers for a process id and a decision id, and the deployment stamp is only a hint

The platform's decision 38 has the rule. A check which cannot see everything that could hold its
answer stays silent. And where the BPMS can be asked, a finding about a model somebody else
deployed is a warning, never the end of a boot. The platform's decision 40 splits the work for a
name clash: the adapter asks, and the core words the message. This entry says what this engine can
be asked for Camunda 7, and how far its answer goes.

Two kinds of identifier are keys of the repository, so the adapter can query both. It asks about
every BPMN process of a workflow module in one statement (`processDefinitionKeyIn`). It asks about
decisions one by one, because the decision query has no batch filter. Neither query names a
version. Our own deployment creates the newest version of a key which another deployment may have
held for years, so asking for the latest version would find only ourselves. Neither query names a
suspension state either, for the reason decision 12 gives. The tenant belongs in both filters,
because a second tenant on one engine is a valid setup and not a clash.

A message name, a signal name, an error code and an escalation code are in no index. They live in
the models. Reading one model per version the engine holds is the growth which decision 10 forbids
for a start. Where the models are read anyway, the question costs nothing, and that is where the
adapter asks it: from what it read out of the models of this deployment, and from the model of a
held version which the check of older versions already reads. A task definition is not a question
here at all, because Camunda 7 keeps it local to the process. VanillaBP's EL resolver evaluates the
expression inside the process, and nothing subscribes to it across the engine. This is also why
this adapter does not rewrite a task definition.

The hard part is not the query. It is to tell a foreign holder apart from this application's own
history. The adapter can ask about the Camunda deployment, and the part of its stamp which helps is
the NAME. Every VanillaBP generation deploys a workflow module under the module's own id, version 1
included. Version 1 wrote the application name into the source, where this adapter writes the
adapter type and the adapter id. So one query finds the deployments named after the module being
deployed, and a definition which belongs to one of them says nothing. Reading the source instead
would report every definition of an application which upgrades from version 1, on its first start
and on every start after it.

A definition under another deployment name is reported, and the source then says how sure the
adapter can be. If the source names a Camunda 7 adapter of VanillaBP, the report is not certain.
Another adapter id is what a migration between two engines looks like, and another workflow module
of this application also writes its own name. Any other source was written by something else, like
a modelling tool or an application which deploys its models itself. That one is reported as
certainly foreign.

This cannot see a second application which deploys a workflow module with the same id. It writes
the same deployment name, and no column of the engine's deployment table names an application.
Reading that name as ours keeps the check from warning about this application's own history on
every boot. That is the price, and we pay it on purpose. Nothing of all this is cached, and no
runtime path reads it.

`Camunda7IdentifiersTheEngineHoldsTest` holds the queries and the stamp against a real engine.
`Camunda7DeclaredIdentifiersTest` holds what a model declares and what a held version still
declares. `Camunda7StartupQuestionCostTest` holds that a boot asks once per workflow module plus
once per decision.

### 18. An idle engine is told when to wake up instead of asking whether it is time yet

The Camunda 7 job executor does not wait, it polls. It polls every 5 seconds, and up to every 60
seconds while nothing happens. Every one of these cycles is a database command. An application
which spends most of its life waiting for a timer pays for that on a database billed by active use.
A longer interval does not solve this, because it makes a timer late and the polling still does
not stop. The solution is a question with an exact answer: when is the next job due?

So a cycle which found nothing asks the engine for the one job with the earliest due date after the
moment that cycle began. Then it waits until that time. "Found nothing" has to mean all four of the
following at once, or the wait would be about load and not about a due date:

- every engine handed out fewer jobs than were asked for,
- nothing was lost to another node's lock,
- the acquisition did not fail,
- the threads which execute jobs were not full.

In every other case the engine's own backoff stays as it is.

Two details of that question are decisions, not taste. First, it asks for ONE row. The answer it
needs is a moment, and the database would read every further row and nobody would look at it.
Version 1 listed every future job and used the first. Second, it uses the clock of the cycle which
just ran, not a fresh one. Reading the clock again opens a gap of a few milliseconds. A job which
falls due in that gap shows up in neither answer and nothing ever wakes for it. The acquisition loop
subtracts the same moment from the wait it gets back, so a job which came due in the meantime starts
the next cycle at once. Version 1 also let the normal backoff reach its 60 second ceiling before the
due-date logic started at all. That spent about two minutes of polling on every quiet period to save
one query per cycle. The query is one indexed row, and it replaces the cycle it would otherwise
have cost. So these stages are gone.

There is no upper limit on how long this may wait. The due-date query answers the question exactly,
and a job which this node writes itself wakes it. The platform's outbox answers the same question
with a limit, because there it cannot see a second node's write at all. Here the limit would only
hide the case named below. And a reader who thinks the limit is about timing sets it to seconds and
gives the whole saving back.

The waking hangs off the TRANSACTION, not off a list of methods. Every engine command asks its own
commit to wake the acquisition, and only an engine command can write a job. So all of these are
covered, and the code names none of them: a workflow which is started, a message which is
correlated, a task which is completed, a signal, a continuation the engine wrote for itself, a retry
it rescheduled, and a request to Cockpit or to the REST API. Version 1 published an event from four
places in one class. So a plain save of a workflow aggregate, a signal and a continuation inside the
engine woke nothing.

That hook is the engine's command chain, not a post-commit hook per platform, because neither
platform has one. Somebody has to call the post-commit mechanisms of Spring and Quarkus from inside
the transaction. The only place where this adapter is inside every transaction which could have
written a job is the engine's own command. Underneath, the engine's transaction context uses exactly
those two mechanisms. On Spring Boot it is a synchronization registered with
`TransactionSynchronizationManager` which acts on `afterCommit`. On Quarkus it is a JTA
synchronization which acts on a completion which committed. The adapter adds the one place which
registers it.

It helps to say what the engine already does by itself. This is what makes the due-date wait the
feature and the waking a detail. `JobManager` hints its own executor after a commit which inserted a
job due now. For a timer it hints only where the due date falls inside the executor's wait time. A
job due LATER is the gap. Without the due-date wait, covering that gap shortens nothing at all.

This does not cover a second application which writes jobs into the same engine. That
application's commit runs in its own process and reaches no listener here. So a node which waits on
a due date computed before that write learns about the job only when its own question runs next.
This adapter does not support two applications against one engine, and this is one of the reasons.

Where the feature is on, the engine's metrics reporter is switched off. The reporter has a timer of
its own and writes its counters through a database command every 900 seconds. That would wake an
engine four times an hour which was meant to stay quiet. A feature which waits for an hour and is
then woken by its own metrics has saved nothing. Only the writing stops. The counters are still
kept in memory. An application which needs the written metrics says so with `db-metrics-reporting`,
and the startup message tells it what that costs.

Where the feature is on, jobs are acquired by due date. Waking for the earliest due job and then
acquiring in some other order picks the wrong job as soon as more jobs are due than one cycle takes.
That order needs a database index. The index is the operator's work, and the startup message asks
for it by name. `jobExecutorPreferTimerJobs` stays as the engine has it. A preference between kinds
of job is a different question. Version 1's README claimed both flags, while its code set neither.

`Camunda7DueDateSleepTest` holds the waiting rule against a real engine. This includes the count of
connections an idle engine takes, a job due later, a commit which shortens a running wait, and an
engine which holds no job at all. `Camunda7JobExecutorSleepTest` holds what the two keys decide and
what the startup message says. `Camunda7StartupQuestionCostTest` holds that the question asks for
one row. `Camunda7SleepingEngineIT` and its Quarkus twin hold that a booted application on either
platform takes no connection while nothing is due.

### 19. The tenant answers for two workflow modules, and it is resolvable per module

The core asks this adapter whether its own isolation keeps two workflow modules apart, because the
core cannot see the scopes of a BPMS. The platform's own decision log says why the answer ends a
boot instead of warning. This entry says what Camunda 7 answers. On this engine it is one question:
would the two modules be deployed into two different tenants? So the adapter resolves the tenant of
each module through the same method the deployment uses to resolve its tenant, and compares the two
names. Reading a property instead would answer about the configuration and not about the engine.
Those are not the same thing. The mode may drop the name, and the name may come from a level the
reader did not look at.

Having no tenant counts as a scope here. A workflow module under `use-prefix` or `none` reaches the
engine without a tenant. So two such modules share every process definition key they both declare,
while one of them and a module with a tenant share none. Answering "separated" for a module without
a tenant would hide a clash which the prefix no longer covers. A mixed configuration produces
exactly this.

With this entry, the tenant name became resolvable per workflow module, not only per adapter. There
are three reasons, and the first one decides it. The refusal the core writes tells the developer to
give one of the two modules a scope of its own, and the key it names is the module's key. A fix the
message recommends has to work. Second, the adapter would otherwise answer a question it cannot
answer right. An application could only say one name for every module, and the answer would always
be "separated by nothing". Third, the name belongs to the level the deployment belongs to.
VanillaBP resolves an adapter's properties over the levels anyway, and a tenant which every module
shares is the special case, not the rule.

There is no tenant per workflow. The mode has one, a tenant cannot. A tenant id is an attribute of
the deployment, and this adapter makes one deployment per workflow module. So two workflows of one
module cannot reach the engine in two tenants. A key which looks honoured but is ignored is worse
than a key nobody may write. So only the workflow module and the adapter have one. For the same
reason, the check which refuses a tenant the mode would ignore now runs per property key and not
once per adapter. The message has to send the developer to the line they wrote.

`Camunda7WorkflowModuleIsolationTest` holds the answer per configuration. This includes the cases
without a tenant and the name set for one module only. `Camunda7CollidingProcessIdsTest` holds the
boot which ends on the second of two modules under one process id, with the core's own support in
between, and runs both deployment orders. `TenantResolutionTest` holds the resolution over the
levels. `Camunda7StartupQuestionCostTest` holds that the answer asks the engine nothing.

### 20. A listener somebody modelled is a task, and only where the application asked for it

Version 1 let a `@WorkflowTask` method serve a `camunda:executionListener` and said so nowhere. That
matters because a model with application logic in a listener cannot be moved to another BPMS.
Version 2 deleted the reading side, so such a listener was silently not served. The engine
evaluated the expression itself. So a workflow which reached the element either failed on a name
nothing resolves, or it ran a method the application never meant for that element. The decision:
the listener is served again, behind a key which is off by default, and a boot which uses the key
says what it costs.

A served listener is a `camunda:executionListener` written as `camunda:expression` or
`camunda:delegateExpression` **whose expression names a `@WorkflowTask` method of this
application**. For example, `delegateExpression="${archiveOrder}"` is served where
`@WorkflowTask(taskDefinition = "archiveOrder")` exists, and only then. That second half keeps the
feature from taking something away. On Camunda 7 with Spring or CDI, a delegate expression is
resolved against the application context. So `${failTheJobOnce}`, naming a bean which implements
the engine's own `ExecutionListener`, is a normal Camunda 7 model. It is older than VanillaBP and
none of its business. The same holds for a `camunda:class` and a `camunda:script` listener, which
have no expression at all. None of those is read, refused or reported. Whatever resolves them keeps
resolving them, and the key is not about them.

Only the task-definition route counts. `@WorkflowTask(id = ...)` names the ELEMENT, and one element
may carry a task and a listener at once. So the element id cannot say which of the two a method
means.

The adapter does not read a `camunda:taskListener` at all either. VanillaBP itself notifies the
`@WorkflowTask` method of a user task, on creation and on cancellation. A second route into the same
moment would be two mechanisms for one thing.

The key is `vanillabp.adapters.<id>.allow-listeners`, a boolean, and it is `false` unless somebody
writes it. It resolves at adapter, workflow-module and workflow level, and the most specific
configured value wins in both directions. So a module which serves its listeners can have one
workflow which does not. There is no task level. That level is keyed by a task definition, and this
key decides whether a listener becomes a task at all. So when the key is read, there is no task
definition yet to key a level by. A value written there anyway gets one guiding warning which names
the three levels that work, and the boot goes on. The Camunda 8 adapter reads exactly the same key
at exactly the same three levels. Two keys about what a model may contain, which reach different
levels on different adapters, would be the worse answer.

Where the key is off and a model has a served listener, the boot ends here in the adapter. This is
not how `allow-connectors` works. A connector asks VanillaBP to LEAVE an element alone. So the core's
wiring validation finds a task nothing serves and ends the boot by itself. A listener asks VanillaBP
to SERVE something. So without the key there is no task spec, and the validation has nothing to
miss. So the message comes from the adapter, and it names the elements, the three levels and the
cost.

Where the key is on, a listener is a task like any other one from there on. `validateTaskWiring`
asks for a `@WorkflowTask` method and ends the boot where none exists.
`validateNoUnwiredWorkflowTaskMethods` reports a method which matches no listener of any wired
process. Version 1 wired its listeners privately and had neither check.

The event is part of a listener's identity, because one method serves one event of one element. A
listener knows the two events `TaskEvent.Event` already has. `CREATED` is the modelled listener
firing, whatever moment that is. `CANCELED` is the element going away (see below). Two
served listeners of one element under ONE expression end the boot, and the message names both. One
method would serve two events, and nothing it could ask would tell it which one it is in. Version 1
decided this case with a `findFirst`. Two listeners of one element under different expressions are
fine. A method then has to name the task definition, because `@WorkflowTask(id = ...)` names the
element and cannot tell them apart. A method which declares `@TaskId` also ends the boot, because a
listener is notified and done, and the task can never stay open. A method which throws
`TaskException` is answered with the reason and not with an incident: a listener has no token to
route.

**When the element is canceled.** A listener fires at the moment the modeller picked, and at no
other. An element which an interrupting boundary event or a terminating end event takes away never
reaches a `start` listener. So without more, the method which serves it would never be told that the
work it was waiting for is gone. Version 1 had this hole and said nothing about it.

`TaskEvent.Event` does not get new values for this. No two BPMS promise a listener the same way.
This engine knows `start`, `end` and `take`. Camunda 8 knows `creating`, `assigning`, `updating`,
`completing` and `canceling` on a task listener. A common set over the two would be a promise
VanillaBP cannot keep. So the event a method really wants is in the model: one listener per event,
one method per listener.

The engine already fires an END execution listener when an element is canceled. This is how
`Camunda7TaskCancellationListener` hears a cancellation for a TASK. So the same listener serves the
listeners of an element, and `Camunda7AsyncBpmnParseListener#parseProcess` attaches it to the
elements which have one. The adapter looks at the process once, after its whole scope was parsed,
and not through a `parseXxx` method per element type. A listener may sit wherever a modeller can
select something. A method per type would be a list which falls behind the next BPMN element the
engine learns. An element the engine has no activity for is skipped. The case a modeller reaches is
a sequence flow, and a sequence flow is never canceled.

A listener the modeller put on `end` gets nothing from VanillaBP. Such a method already hears the
cancellation through its own listener, as `CREATED`, and a second report would be the same moment
twice. The Camunda 8 adapter draws the same line for the listener a modeller puts on its own cancel
moment: whoever already hears the moment is not told a second time.

An element which already has the cancellation listener keeps the one it has. Every service-like
activity has one, because the transaction boundaries put it there. A second copy would report one
cancellation twice.

Nothing is written into the BPMN for this. Decision 14 states this rule for everything VanillaBP and
its extensions attach here. What the wiring learns is written into `Camunda7TaskRegistry`. The parse
listener reads it there, under the process definition key the engine reports.
`Camunda7ListenersTest` holds which listener needs a cancellation and which one does not.
`Camunda7ModelledListenerIT` holds a boundary event which takes an element away, and the method
which hears it.

The default is off because of what serving a listener costs. A listener is where a BPMS lets an
application in at a moment the BPMS owns. Every BPMS draws that moment differently, so the model is
no longer portable. Another BPMS has no listener at this element, and a migration of the model stops
at the method which serves it. The Process-Engine-API has no listener concept at all. That is gap 16
and gap 17 of that adapter's `GAPS.md`. So every boot of a workflow module whose listeners are
served writes one framed WARN. It names each served listener, the key which switched it on, what it
costs and the way back. No key silences it. What it says stays true as long as the listener is in
the model, and a key which turns it off would only make the loss invisible.

See [Listeners somebody modelled](./README.md#listeners-somebody-modelled).

### 21. An extension asks this adapter, not the engine

Camunda 7 runs embedded. So an extension of this adapter runs in the same JVM, on the same execution
tree and against the same process definitions. This adapter already looked up every fact the
extension needs. Where the adapter kept that lookup private, the extension read the engine's
internals a second time. So six mechanics existed twice:

- the multi-instance walk through the execution tree,
- the way back from a process definition key to a workflow module,
- the tenant of an adapter id,
- whether the engine joins the caller's transaction,
- what a user task is called,
- the version of a process definition.

Two of the six had already drifted apart. The task definition of a user task with an EXPRESSION
form key was the written text on one side and the evaluated value on the other. So one task reached
a consumer under two identities and looked like two tasks. And on Quarkus, an engine on a data
source of its own was reported as joining the application's transaction, while this adapter
answered the opposite everywhere else.

So the mechanics are published, in `io.vanillabp.camunda7.api`. This is API of this repository, not
of the VanillaBP adapter SPI. None of it is a mechanism another BPMS shares, and a core which knew
about Camunda's execution tree would know about Camunda 7. It sits next to
`Camunda7EngineCustomizer`, which is the other half of the same idea, and it follows the same rule:
one object per configured adapter id, because two ids are two engines.

The transaction answer is the adapter's answer, and it depends on the data source, not on the
transaction manager. Quarkus builds the engine on the container's transaction manager, which does
enlist both resources. But a JTA transaction around two independent data sources is still two
commits. So an adapter id with its own data source does not join, on either platform. It is the
same answer which already makes such an id deliver repeatable tasks and name its deliveries. To
read the data source name and conclude the opposite was to read the right key and draw the wrong
conclusion.

What is published promises what it says, and nothing more. What the adapter REPORTS does not change
here. A user task without a form key still reaches the core with an empty task definition. The
rule which fills in the element id is published, not applied.

`Camunda7EngineFactsIT` and `Camunda7AdapterBootTest` hold it on Spring Boot, and
`Camunda7EngineFactsOnQuarkusTest` holds it on Quarkus. The tests in `io.vanillabp.camunda7.api`
hold what each entry point promises.

See [What an extension may ask this adapter](./README.md#what-an-extension-may-ask-this-adapter).

### 22. The multi-instance chain crosses a call activity, and the deployed model says where it may

Camunda 7 keeps the item, the index and the total of an iteration in the execution tree. That tree
does not end at a process boundary: the execution of a called process points back at the call
activity which started it. So the walk which collects the levels of a task can go on in the calling
process, and it does. A task of a called process is told the iteration its call activity sits in,
over as many levels as the models nest, and the application models nothing for it.

Whether the walk SHOULD go on is a question about the workflow aggregate, not about the model. A
called process which continues the caller's aggregate continues its business case, and the
iteration the caller stands in belongs to that case. A process with an aggregate of its own is a
business case of its own, and what the caller iterates over says nothing about it. Version 1
assumed every call activity was decomposition. This entry drops that assumption.

The core answers that question while the application starts, from what its workflow services
declare. The walk needs the answer while a workflow runs, and the core only knows what the
application declares today. So the answer travels in the deployed model. A call activity whose
called process continues the caller's aggregate gets a `camunda:property` named
`vanillabp:sameWorkflowAggregate`. It is written next to the business key of decision 5 and read by
`Camunda7MultiInstances`. This has two effects. First, an older version of a process which the
engine still runs keeps the answer it was deployed with, and the workflows still standing in it
need exactly that. Second, a call activity which names the process to call in an expression has no
note, because the model does not say which process will be called. For that case the core is asked
while the workflow runs. It is asked through the `Camunda7TaskRegistry`, to which the deployment
service hands that one answer. The start listener of a called process asks the same question there
(decisions 32 and 34). It is asked ONLY there. Where the model spells out the called process, the
answer of the deployment stands. A workflow standing in an older version keeps the answer that
version was deployed with.

The values themselves are never copied anywhere. The engine holds them in the executions of the
calling process. A called process which is not told about them can still read what the model passes
it. What ends at the boundary is what this adapter REPORTS. Only there is the difference between two
business cases known.

`Camunda7MultiInstancesAcrossCallActivitiesTest` runs the shapes against an embedded engine, with
every called process in a BPMN file of its own. With the calling and the called process in one
file, the walk finds the enclosing element by chance. That is how the missing model of the caller
stayed hidden from version 1 until this entry was written.

See [The iteration a called process runs in](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Configuration#the-iteration-a-called-process-runs-in).

### 23. A wake-up which arrives while the next wait is decided still ends that wait

Camunda's acquisition loop reads its "a job was added" flag into the cycle which just ran. Then it
asks the strategy how long to wait, and clears the flag after that. A wake-up which arrives between
those two steps sets a flag nobody looks at any more. A wake-up which arrives a moment later races
the point where the loop starts to listen on its monitor. The engine lives with both and pays an
idle interval for them, five to sixty seconds. That is fair, because the next cycle is never far
away.

Decision 18 changed what that costs. The wait this adapter decides on reaches to the next due date.
Where the engine holds no job at all, it reaches a year. So the same lost wake-up is no longer a late
cycle. It is a job which never runs, with no incident, no log line and no metric to show it. It
hurts exactly the application the sleep was built for: one which is quiet until somebody gives it
work.

A limit on the wait does not solve this. A limit trades the whole saving for a probability and
leaves the loss in place, only smaller. And nobody ever questions again the number somebody picked
for it. The solution is that this loop keeps a wake-up flag of its own. It is set whenever somebody
asks the executor to wake up. It is cleared where a cycle begins, before any engine is asked for
jobs. And the loop refuses to suspend while it is set. So everything written after a cycle read the
database ends that cycle's wait, and the wait does not swallow it. At worst a wake-up costs the
cycle which is already running.

Two smaller things follow. First, the flag is set BEFORE the executor's own flag, and read AFTER the
loop announced that it is listening. That is the opposite order, so one of the two sides always
sees the other, and there is no window left to make narrow. Second, a cycle which the context
reports as woken keeps the engine's own timing and does not use a due date. The wake-up says that a
job was written after the acquisition read the database, so the due date it asked for is older
than the job.

Camunda 8 and the Process-Engine-API have no acquisition loop of this kind. Their tasks are pushed
or polled per subscription, and neither computes a wait from a due date. So neither has the window
this entry closes.

`Camunda7WakeupInTheGapTest` writes the job from inside the window itself and asserts that the job
runs. It also asserts what the strategy decides for a cycle somebody woke. It watches the job
through a bean of the engine and does not ask the engine. Every question to an embedded engine is a
command, and its commit wakes the acquisition. So a test which polls the engine wakes the sleep it
measures.

### 24. A start is the application's own where the ID already has an aggregate - superseded by decision 28

*Superseded by decision 28: a workflow started past VanillaBP no longer keeps the business key it
was started with. The id of a workflow is the id of its workflow aggregate, the application assigns
it, and a key VanillaBP did not write is refused. Decision 28 says why and what replaced the parts of
this entry which still hold. The entry stays because code and messages pointed at it.*

On Camunda 7 the business key IS the workflow aggregate's ID. When the application starts a
workflow, `Camunda7ProcessService` writes the ID into the key. Every other part of this adapter
reads the key back as the ID. So a key names an aggregate, and it says nothing about who started the
workflow.

The listener on a timer, signal or conditional start event used to return as soon as the instance
had a key. Measured against a running engine, that assumption fails in three ways. A process whose
only start event is a timer can be started through `startProcessInstanceByKey` with any key. So can
a process with a signal start event. And a conditional start the engine performs itself has a key
whenever the caller of `evaluateStartConditions` named one. In all three cases no aggregate was
built and nothing was logged. The first task of that workflow then failed with "no workflow
aggregate of class ... was found". That message blames a deleted aggregate for a workflow which
never had one. The job retried until it became an incident.

The rule which replaced it is the one question Camunda 7 can answer: a start is the application's
own where the ID already has an aggregate. The key travels to the core as the name of the instance.
The core looks for an aggregate with that ID, and the answer says which case it was. An aggregate
which existed belongs to the application's own start, or to a workflow taken over from version 1,
which has its ID in the business key and nowhere else. An aggregate which was created belongs to a
workflow somebody started past VanillaBP. It is created under that key, so the workflow keeps the
name it was started with. That case is worth one INFO line, once per workflow. Otherwise the
application would only hear of the workflow when its first task arrives.

This costs one load of the aggregate per start the BPMS initiates, where the early return cost
nothing. A signal broadcast which starts many workflows pays it per workflow. For that price we get
the only reliable answer there is. And a start which is followed by a task loads the aggregate a
moment later anyway.

One case is refused and not repaired: a key which cannot be an ID of that workflow aggregate, such
as a text where the ID attribute is a number or a UUID. VanillaBP would have to give the workflow an
ID of its own and overwrite a key somebody chose. This destroys the name the starter works with.
Nothing is written, and the message names the instance, the key and the two ways out. This adapter
raises no incident of its own. The engine retries the start and then raises one, as it does with
any start which fails.

Two things stay invisible, and we know it. First, a key which happens to look like the ID of an
existing aggregate attaches that workflow to it without a word. Nothing here can catch it, because
catching it needs two values which name the instance, and Camunda 7 keeps one. Second, an
application which deletes an aggregate while its workflow still runs looks like a foreign start at
the next start of the same ID which the BPMS initiates.

`Camunda7ForeignStartIT` starts all three kinds from outside, holds the application's own start
against them, and reads the message of the refused one. See
[The start of a workflow](./README.md#the-start-of-a-workflow-and-workflows-which-ended).

### 25. The migration fallback names no version for its removal

*Merged into decision 1 on 2026-10-12, before the 2.0 release.* It said that no version is named
for the removal of the migration fallback, and how a reader watches the fallback instead.

### 26. A served listener is told when its element is canceled, through the listener the engine already fires

*Merged into decision 20 on 2026-10-12, before the 2.0 release.* It said how a served listener
hears `CANCELED`: through the END execution listener the engine already fires when an element is
canceled.

### 27. An event subprocess does not start a workflow

A workflow starts when the BPMS creates an instance of a BPMN process. At that moment there is no
workflow aggregate, so VanillaBP asks the application to build one. Every start event the engine
fires by itself needs a `@WorkflowStartedByBpms` method. That is what this adapter means by a start
of a workflow.

The start event of an event subprocess is not such a start. The engine fires it inside a workflow
which is already running, and that workflow has the aggregate it was started with. Building one
here would give one workflow two aggregates. And the application would hear that a workflow
started, long after it did.

So this adapter counts a start event only where the process itself holds it. Walking up to the
enclosing process is not enough. That walk gives the same answer for a start event at any depth of a
model. Once every start of a workflow had to be served by a method, no model with an event
subprocess booted any more.

The rule also covers the start event of a plain embedded subprocess. BPMN allows only a none start
event there, and this adapter does not report a none start event anyway. So nothing changes for
such a model. One rule for every nesting is shorter than two.

Two places read a start event, and both follow the rule. The deployment reads the BPMN model and
tells the core which starts a process has. The parse listener sees the scope in which the engine
parses a start event, and the engine draws the same line there: a start event whose scope is not a
process definition becomes a scope start event. So the listener which builds an aggregate goes onto
the start events of the process and onto no other.

`Camunda7EventSubprocessStartsNoWorkflowTest` holds both halves, and that the event subprocess still
takes over a running workflow. Four models of the integration tests have such an event subprocess,
and the applications around them boot again.
See [The start of a workflow](./README.md#the-start-of-a-workflow-and-workflows-which-ended).

### 28. The business key is the name VanillaBP gave the workflow, and only that

The id of a workflow is the id of its workflow aggregate. The application assigns it in the
`@WorkflowStartedByBpms` method, and nobody else does. On Camunda 7 that id is kept in the BUSINESS
KEY. The rule itself, and what the core does with it, is decision 69 of
`adapter-platform-integration`.

This supersedes decision 24 of this repository. Decision 24 said the opposite: a workflow somebody
started past VanillaBP kept the business key it was started with. The aggregate was built under that
key, so the workflow kept the name its starter worked with. That is no longer true. A key VanillaBP
did not write is refused, and the message says that VanillaBP names a workflow.

Decision 24 was right about the one thing Camunda 7 can answer: a start is the application's own
where the id already has an aggregate. It was wrong about what to do with a key which names no
aggregate. Taking over such a key made the starter the one who names the workflow. From then on two
parties named workflows. An id is a value of the workflow aggregate like any other, and decision 69
of the platform had already handed the aggregate to the application. So the key follows the id, not
the other way round.

The refusal costs nothing anybody wanted. Somebody who starts a VanillaBP process through
`startProcessInstanceByKey` with a key of their own gets an incident. Its message tells them to use
`ProcessService` or to leave the key alone. Without the refusal, they would get a workflow which
quietly carries a name the application never chose.

The listener now sits on every start event. Decision 5 says that this adapter edits the model it
deploys, and what limits each edit. The start listener used to go on timer, signal and conditional
start events. Now it goes on every start event the PROCESS itself holds, including the plain one and
the message one. This follows from the rule. Anybody with access to the engine can start any of
these processes, so the kind of start event says nothing about who started this instance. The state
does:

- a business key whose workflow aggregate exists is a start of the application's own,
- no key at all is a start past VanillaBP,
- a key nothing carries is refused.

One instance with no key is nobody's foreign start. A call activity which names the process to call
in an expression has nothing to carry the caller's name. So the instance it starts arrives here
without one. Where the called process works on the caller's workflow aggregate, the listener hands
the core the caller's name and writes it into the instance, and no start past VanillaBP is reported.
That is decision 32. A start event of an event subprocess stays out, as decision 27 says, because it
fires inside a workflow which already runs.

A process this application does not serve is left alone. An embedded engine holds every definition
deployed against its database. That includes the processes this application does not claim and the
processes of another application. Since decision 43 the parse listener puts the start listener on
the start events of a claimed process only. The listener still asks before it reports. A process
which no workflow service of this application serves is none of VanillaBP's business, and the
listener returns. Without that question, the core would be asked to name a workflow it has no
workflow service for, and the engine would retry the start until it becomes an incident.

The cost is one execution listener per start event in the parsed process definition, and one load
of the workflow aggregate per start of a workflow. The listener runs inside the engine's own
transaction. So the load hits the same persistence context the start already uses, and the first
task of the workflow reads the same aggregate a moment later anyway.

Two things stay invisible, and we know it. First, a key somebody chose which happens to be the id of
an existing workflow aggregate attaches that instance to it without a word. Nothing on Camunda 7 can
catch that: catching it needs two values which name the instance, and Camunda 7 keeps one. Second,
an application which deletes a workflow aggregate while its workflow still runs makes the next start
of that id look like a foreign one. Both were in decision 24, and both still hold.

`Camunda7ForeignStartIT` walks every case against a running engine.
See [The start of a workflow](./README.md#the-start-of-a-workflow-and-workflows-which-ended).

### 29. Compensation is reported although this engine starts the handlers one after the other

The core warns about a BPMN process which can hold more than one token while its workflow aggregate
has no version attribute. Compensation now belongs on that list. A throw event which compensates two
finished activities starts both handlers, and each handler is a normal workflow task which writes
the same aggregate. This adapter reads its model for this. For every throw event which starts more
than one handler, it reports `CompensationSpec(throwEventId, handlerIds)`.

We first measured what this engine really does, because a message has to say what is true.

It was measured on 2026-09-27, against the embedded engine of Camunda 7.24 on H2, with the model
`api/compensation-facts.bpmn` and the test `Camunda7CompensationTokensTest`. The model has two
service tasks, a compensation boundary event and a handler on each, and one compensation throw
event.

- The engine creates both compensating executions before it signals any of them. While the first
  handler runs, its parent execution has two children, so the workflow holds two tokens.
- It signals them one at a time. The second handler starts once the first one returned. No two
  handlers were inside their delegate at the same moment, in any of the three setups measured.
- The order is not stable. The engine sorts the compensation subscriptions by their creation time,
  which is a `java.util.Date`, and `Collections.sort` is stable. So two activities whose
  subscriptions were created in the same millisecond are compensated in the order they ran in, not
  in the reverse order. Both orders showed up in the same test within an hour. This is not a
  deviation we report. The wiki already says it: VanillaBP promises nothing about the order of
  compensation.
- The adapter sets `asyncBefore` on every service task, and the flag IS set on the handlers. The
  engine ignores it there. It starts a compensation handler with `executeActivity`, outside the
  normal flow, where no job is created. One job existed at a time, and the whole compensation ran in
  the transaction of the throw event.
- With the job executor running, both handlers ran on one thread.

So on this engine, two compensation handlers made of service tasks never write the aggregate at the
same moment.

The adapter reports it anyway. The check asks whether the process can hold more than one token, not
whether two threads are inside a handler. Both compensating executions exist from the moment the
throw event runs. A forking parallel gateway gets the same answer here. There the job executor also
serialises the exclusive jobs of one workflow, and the adapter still reports the gateway.

And a handler which WAITS keeps its token while the next handler is started. Two compensation
handlers drawn as user tasks are open at the same time. The application completes them in two
transactions which may overlap. If compensation were left out because the everyday case is a
service task, the check would be quiet about the case which really loses a write.

The wording of the core's message follows the measurement. It says the handlers can be open at the
same time, not that they run in parallel.

### 30. A call activity leaving the workflow module is reported, and the error code stays as it is

VanillaBP scopes everything per workflow module, including a BPMN error code. The code a
`TaskException` raises is built from the module of the process whose task raised it. The codes
written in a model are rewritten with the module whose file declares them. So when a call activity
calls a process of ANOTHER workflow module, the called process raises its code under its own
module's prefix. The error boundary event of the call activity waits for the caller's code, and the
error finds nothing to catch it.

Such a call is possible through exactly one attribute, and only on this BPMS.

Under `use-prefix`, a static `camunda:calledElement` is rewritten with the CALLING module's prefix
while the model is prepared. So it can only ever name a process of that module. Under `by-adapter`
there is no prefix. This engine resolves a called element in the tenant of the calling instance,
which is the calling module's tenant. `camunda:calledElementTenantId` overrides that tenant, and that
is the one door out.

A called element given as an expression is the application's own string and can name anything. No
deployment can resolve it, so the adapter says nothing about one.

On Camunda 8 this door does not exist. `zeebe:calledElement` has a process id and nothing else, so
the cluster always resolves it in the tenant of the calling instance. That adapter says so where its
code is built, and reports nothing.

What the adapter does about it: the deployment warns once per call activity which names a foreign
tenant. The warning names the call activity, the tenant, why the error finds nothing to catch it,
and the two ways out. Either let the called process end normally and report its outcome in a
variable the caller branches on. Or move that process into this workflow module.
`Camunda7CrossModuleCallActivityTest` holds the message.

It is a warning and not a refusal, because such a call runs. A called process which raises no BPMN
error is a model somebody may well have meant. Ending the boot over it would take a working
application down for a defect it may not have.

The code is not built from the CALLER's module instead. That would be a second rule for one
identifier, decided by the call activity a workflow happened to come through. Both processes would
then carry a name neither of them wrote. The developer who modelled the error is the one who can
answer it, and the boot is where they read about it.

### 31. A handler is held against the iterations around its own task, not against the whole process

When a model is deployed, this adapter refuses a `@WorkflowTask` method which reads the item of a
multi-instance element for which the model hands over no item. It used to read the elements without
an item PROCESS-WIDE, and then ask the core, per task, which of them a method wants. Camunda 8 has
always read the chain of iterations around the task instead. So the two adapters gave different
answers to one question, and this entry picks one.

The chain wins. A handler gets the item of the rounds its own element runs in, and of nothing else.
So an element in another branch of the same process cannot reach it, whatever that element names.
Refusing over such a pair ends the boot of an application which does nothing wrong. And the message
would name an element the developer cannot connect to the task. The process-wide reading was the
wider net, and everything it caught beyond the chain was a false catch.

So `Camunda7MultiInstanceItems.elementsWithoutAnItem` is gone.
`refuseHandlersWantingAnItemTheModelHasNot` walks the chain of each task through
`elementsWithoutAnItemAround`. The version catalog already used that method for a version the engine
still holds, so a task is now read one way in both directions.

A method which declares `@MultiInstanceElement` for an element it never iterates in is no longer
refused. It gets `null` at runtime, and neither adapter says a word about it. That is a different
defect. The element may well name its item. To find the defect, the core has to compare what a
method asks for against the chain the adapter read, and that is a roadmap row of its own.

The other half of the same question was the shape of a compensation finding for a held version.
Should a version which a BPMS only still holds report its compensation in the shape the deployed
model uses ("this throw event starts these handlers")? Or flat, among the other elements which can
put a second token into a workflow?

It stays flat, in both adapters. The shaped report exists because a developer reads which throw
event starts which handlers and then redraws the model. Nobody can redraw a version the BPMS already
holds. What is left to say about it is that its workflows can hold more than one token, and the
flat list says that. The shape would need a second method on `ProcessVersionCatalog`, and that
method would only add a sentence nobody acts on. Both adapters say so where the flat list is built.

### 32. A called process named by an expression is asked about while it runs

Camunda 7 hands a called process no business key, and this adapter keeps the workflow aggregate's
id in the business key. Decision 5 closes that gap in the model. The deployment writes
`camunda:in businessKey="#{execution.processBusinessKey}"` onto a call activity whose called process
works on the aggregate of its caller, and nowhere else. A process with an aggregate of its own must
not get the caller's identity.

A call activity which names the process to call in an expression has nothing this answer could be
written onto. The model does not say which process will be called, so the deployment cannot tell
the two cases apart. Such a call activity used to be left alone. Measured against the engine, that
model did not run. The called process reached the application with no name. The core read that as a
start past VanillaBP and refused it with "no `@WorkflowStartedByBpms` method builds a workflow
aggregate for it". That is the wrong advice. Writing that method would build a second workflow
aggregate, and the called process would then run beside its caller's business case instead of in
it. The outbox retried the refusal fifty times, and the workflow never moved. The same model runs on
Camunda 8, where the aggregate's id is a normal process variable which the cluster copies into the
called instance. So one model behaved differently on two engines.

Now the answer comes while the workflow runs. The start listener of the called process reads the
call activity which started the instance out of the execution tree. It asks the core whether caller
and called process work on one workflow aggregate. Where they do, it writes the caller's name into
the instance. Where they do not, nothing is inherited, and the start stays the start it looks like.
The start of a called process is not reported to the core (decision 44). The listener still hands
the caller's name on.

There were three other ways, and none of them works.

The cheap one is to always write the business key. It trades a loud failure for a quiet wrong
answer. A called process with an aggregate of its own would then get the caller's id. A lookup of
that id in the called process' own persistence then either finds nothing and refuses, or finds a row
with the same id and attaches the instance to business data it has nothing to do with. Decision 28
already names that last case as the one thing Camunda 7 cannot catch. Creating it on purpose is a
different matter.

The second way is to evaluate the model's own expression again inside an injected business-key
expression. This keeps everything in the model. But it evaluates the application's expression
twice, it breaks on a `calledElement` which mixes literal text with an expression, and the scoping of
decision 3 has to rewrite it again. Too clever for one attribute.

The third way was to leave the behaviour alone and write the difference into both READMEs. Then the
portability of a model is a promise which only one engine keeps. An application writes this model on
purpose, so the difference would be the first thing a reader of both adapters finds.

The cost is one question per start of a called process which arrives without a name. The core
answers it from a map it filled while the application started. The adapter asks through
`Camunda7TaskRegistry`. The deployment service hands the core's answer to it, the same way it
already hands over the process versions, so nothing new reaches the listener.

Decision 22 keeps the answer about a shared workflow aggregate in the deployed model. The reason is
that the multi-instance walk starts from an execution, and the core was not at hand there. That is
why a call activity named statically has its note, and `Camunda7MultiInstances` reads that note.
The start listener is built with the core and can ask. So the business key crosses a call activity
which the model names in an expression. When this was decided, the walk still ended there. That was
a difference between the two mechanisms, not between the two engines. Decision 34 closed it by
handing the walk the same registry.

`Camunda7CallByExpressionIT` runs the same called process, reached both ways, against the engine.
`Camunda7CalledProcessStartTest` holds the four cases the listener tells apart.

### 33. A decision named by an expression gets the prefix in front of the expression

Under `use-prefix`, the decisions of a workflow module are deployed under ids with a prefix. The
`camunda:decisionRef` of the business rule tasks which call them gets the same prefix. A
`decisionRef` written as an expression used to stay exactly as the application wrote it, because the
model does not say which decision a run will pick. Now the prefix is written in front of it, the
same as for the `camunda:calledElement` of a call activity. Camunda 7 reads the attribute as one
expression. The text in front stays text, and the engine looks up the prefixed id of whatever the
expression returns.

We measured against the engine before changing anything. `Camunda7DecisionByExpressionTest` deploys
one model twice, once scoped and once not. The decision of the module is next to it, and the parse
listener of this adapter is in the engine, so the business rule task runs in a job of its own as it
does in an application. Without a prefix, the run reaches the decision and the task writes its
result. Under `use-prefix` the job failed with "no decision definition deployed with key
'creditRating' and tenant-id 'null': decisionDefinition is null". The engine retries such a job and
leaves an incident. So the workflow stops at that task, and the application sees a model which never
gets past it. Nothing warned about it while the module was deployed. The wiring check leaves a
business rule task with a `decisionRef` alone on purpose, because the engine serves it, not the
application.

The other way was to report the case at deployment, and we did not take it. It would refuse a model
this mode can carry, and the engine does carry it: the measurement above shows the composed
expression. It would also make `use-prefix` the one mode in which a decision cannot be picked at
runtime, while Camunda 8 does the same thing with the same model (decision 55 of the Camunda 8
adapter writes the prefix into the `decisionId` of a `zeebe:calledDecision`). Both adapters now
answer the same for the same file, and a portable model needs that.

The application loses a reference out of its own module. The expression always resolves inside the
prefix of the module whose model has the task. `use-prefix` already had that limit for a decision
named by a literal, and the README says so in its `Decision tables` section. A module which has to
reach a decision somebody else deployed stays with `by-adapter`.

This follows decision 3 and decision 5. Decision 3 says the identifiers which the engine resolves
across definitions are scoped. Decision 5 says the adapter only changes a model in ways its author
can predict. The prefix in front of an expression is the same change the author already sees on a
call activity.

### 34. The multi-instance chain crosses a call activity named by an expression

Decision 22 keeps the answer about a shared workflow aggregate in the deployed model. For a call
activity which names the process to call in an expression, there was nothing to write it onto. So
the walk ended at such a call activity, the way it ends at a foreign aggregate. Decision 32 made the
business key cross it, by asking the core while the workflow runs. The deployment service hands the
one answer to `Camunda7TaskRegistry`, and the start listener of the called process reads it there.
The walk could have asked the same way, but it did not. So one model reported the iteration of its
caller on Camunda 8 and reported none here. `Camunda7MultiInstanceByExpressionIT` measured that
before anything was changed. In one model and one run, the four calls of a call activity named by
an expression reported `nothing`, and the one call of a call activity named by its id reported its
level.

This entry closes the gap. The walk asks the core through the same registry, and only where the
deployment could not answer. Where the model spells out the called process, the note written when
that model was deployed stands, and nobody asks again. So a workflow standing in an older version
of a process still gets the answer that version was deployed with, whatever the declarations of the
application say today. That is the part of decision 22 this entry leaves alone. Both mechanisms use
one method for it, `Camunda7CallActivities.continuesTheCallersWorkflowAggregate`, for a pair of
running executions. So the business key and the iteration cannot give one pair of processes two
answers.

The other way was to write the caller's chain into the called instance as a process variable, the
way the Camunda 8 adapter writes `vanillabpMiParents` as an input mapping. That needs no core while
the workflow runs, and it would answer for every reader of the walk. But it also copies the core's
answer into a place where an application can read and overwrite it. Camunda 8 pays for that with
three test cases about a model which writes that variable itself. The core's answer is the one
source of this truth, so the adapter asks it and does not copy it.

A reader of the walk which holds no registry still ends at such a call activity. That is any caller
of `Camunda7MultiInstances.of(execution)` or `of(engine, executionId)` outside this adapter. Such a
caller should use the overloads which take a registry. When this was decided, the Camunda 7 adapter
of the business cockpit was the second such reader. It reads the walk for the details of a user
task, and now passes the registry it gets through `Camunda7EngineFacts`.

### 35. A compensation runs in one transaction, and the deployment says so

Decision 5 lets this adapter write `asyncBefore` and `asyncAfter` on every service-like task. This
gives each of them a job, and so a transaction of its own. A compensation handler is the one place
where the engine does not follow. It starts such a handler outside the normal flow, where no job is
created. So all the handlers of a throw event run in the transaction of that event. The promise
holds everywhere else. This is the exception next to it, and the deployment names it.

Measured on 2026-10-01 against the pinned engine 7.24.0 by `Camunda7CompensationTokensTest`, with a
throw event which compensates two finished service tasks. Both handlers ran in the same command
context, which is the engine's own unit of work, and one commit covered both of them. The two
activities they compensated ran in a transaction each. So the flags do work where the engine makes
a job of an activity. And when one handler threw, the other one had to do its work again. The
engine rolled the whole compensation back, counted one retry off the single job and ran both
handlers again on the next attempt. Decision 29 had read the jobs and the thread on 2026-09-27, and
those numbers did not change. The command context, the commit and the retry are new.

It is a warning, not a refusal. The model is right, and no flag of this adapter changes what the
engine does. So ending the boot would stop an application which did nothing wrong. A reader can
change the handler. That is why the message asks for a handler which may run twice, and for work
the engine cannot roll back to stay out of it. It names the throw event and the handlers it starts,
because the cost grows with their number. With five handlers and their side effects in one
transaction, the work of the first four is only as safe as the fifth.

This decision does not answer whether the adapter should make up for it. The flag the adapter
writes at parse time IS the hook this engine offers, and the engine ignores it there. So anything
beyond that would be VanillaBP running the compensation instead of the engine. Whether it should is
a separate question with its own roadmap row. This decision only says what the engine does and what
the deployment says about it.

Camunda 8 hands out a job per handler, so the same model is expected to run in a transaction per
handler there. We took that from the way the Camunda 8 adapter reads the model. It is not measured
on a cluster, and only a run against a cluster would settle it.

### 36. A user task nothing serves is named in a claimed process, and nothing is refused - superseded by decision 42

*Superseded by decision 42: a user task of a claimed process now needs a `@WorkflowTask` method or the line `implemented-externally=true`, the INFO line is gone, and the core holds the rule for all adapters. Version 1 did ask for the method, so the sentence above saying that nobody used to say a word about it was wrong as well.*

*The split it draws between a claimed process and one nobody claims goes further with decision 43: the parse listener does not add the user-task listeners to a process nobody claims either.*

A user task of this engine runs without a `@WorkflowTask` method. The engine creates the task, it
stands in a task list, somebody finishes it, and the workflow moves on. That is why the core hands
a user task over as an OPTIONAL spec, and `validateTaskWiring` filters those out before it asks for
a method. Nobody used to say a word about it. So an application which drew a notification into its
model and forgot the method found out in production, if at all.

From now on the deployment names such a task. It does so once per BPMN process, at INFO, while the
model is wired, and only for a process which a `@WorkflowService` class of this application claims.
The message names each element, the name the modeller wrote on it, its form key or that it has
none, and the method which would serve it. At the end it says that a model whose user tasks are
worked only through a task list needs no change.

This is not the refusal Camunda 8 has. Decision 53 of the Camunda 8 adapter refuses a user task
which a job worker serves in a claimed process. Its reason is what that shape costs: the cluster
hands out a job, nothing fetches it, and the workflow stands at the element with no incident and
nothing in any log. Camunda 7 has no such shape. Every user task here belongs to the engine, and only
the application can be missing.

So the rule is the same where the two engines can be the same, and it stops where the engine stops.
We take over the split. A process this application claims is a process it is responsible for. A
process nobody claims is somebody else's model, which reached this engine because of the file it
sits in. We do not take over the level. A refusal would end the boot of an application whose model
is right. That would be stricter than the core, whose own field says a handler is optional. A WARN
would be the same claim in a quieter voice, on every boot, for a model nobody has to change. The
third option is a report which only speaks where the process is claimed. It says the one thing
which may be news, without asking anybody to act on it.

We rejected a refusal like the one Camunda 8 has for its own case. It would refuse the plain
Camunda 7 model of version 1, and of every application which works its user tasks through Camunda's
Tasklist. There is nothing to fix in such a model. A flag to switch the refusal off would be a flag
for the normal case.

A WARN instead of an INFO says that something needs attention. Here the model may be exactly what
the modeller meant. A warning on every boot for a correct model is how a log teaches people to skip
warnings.

The Camunda 8 adapter also says it for an unclaimed process, and it has a reason there: the workflow
of an unclaimed process really does stand at the element. Here nothing stands, and no method of
this application was ever meant to serve those tasks. The core already names the unclaimed
processes of a workflow module once, and that is the right place for it.

The check could also sit in the core. The core holds `optional` and knows which method serves which
spec, so it could name the unserved optional specs of a claimed process once for all adapters. Two
things speak against that for this release. First, the way out differs per BPMS, and the message is
only worth reading where it is concrete. Here it is a form key. On the Process-Engine-API it is an
external form reference. On Camunda 8 it is a form definition of a task which Camunda manages.
Second, the core only sees the specs an adapter chose to hand over. A Process-Engine-API user task
without an external form reference never becomes a spec at all. So the core could not name the
case which loses the most. A report in the core would also change the Camunda 8 adapter's boot
output, and this entry does not touch that.

This leaves the Camunda 8 adapter open. It stays silent about the same case for a user task which
Camunda manages and whose external form reference no method names. So three adapters say two
different things about one situation. Closing that is either one more story for that adapter or the
report in the core described above. Whoever takes it should start from this entry.

`Camunda7UnservedUserTasksTest` holds the message, the element without a form key, both keys a
method may be wired by, the silence about an unclaimed process, and that the line is an INFO.

### 37. The expressions of a model are reported for the model being deployed, not for the versions the engine still holds

The core is told the expressions of a process while that process is wired. So what it hears is the
model this application version brings. The engine keeps the older versions, and workflows still run
on them. Those models are not read for this.

There is a way to read them. `Camunda7ProcessVersions` walks the models the engine holds for
`concurrentTokenElementsOfVersion`, and the same walk would answer this question. So the question
here is what such a message would be worth.

An old model says nothing new. An expression which reads a path reads the same path in every
version which has it, and the message names the element, the place and the expression. The
difference is that nobody can act on an old model. A model in the engine cannot be edited. The
deployed model is where a developer writes the plain getter the message asks for. And the workflows
on the old version run out on their own. A warning about them would ask for work nobody can do.

There is a second reason: the count. The message ends with how many of the expressions of this
process name a variable and nothing else. This number tells a developer how far their model is.
Counting the held versions as well would count the same expression once per version, and the
number would no longer mean what it says.

`ConcurrentTokenCheck` asks about held versions for a reason this check does not have. A parallel
gateway which the newest model dropped keeps forking every workflow which started before, so the
finding only exists in the old version. An expression is not like that. It is read where the model
has it, and that is the model being deployed.

Whoever wants the held versions in the message needs a separate piece of work. The place to hook it
in is `Camunda7ProcessVersions`.

### 38. A user task reports the multi-instance levels a service task in its place reports

Camunda 7 notifies a `@WorkflowTask` method about a user task from a task listener. A task listener
gets a `DelegateTask`, not an execution. The multi-instance walk of decisions 22 and 34 needs an
execution. So the context built for a user task gave the SPI default of
`TaskInvocationContext#getMultiInstances`, which is an empty map. Every other kind of task reported
its levels, and the same model reports them on Camunda 8. So one model answered two ways again.

We measured against the pinned engine 7.24.0 before changing anything. A `DelegateTask` gives access
to the execution its task waits on. That execution is an `ExecutionEntity`, and from it the walk
reports both the enclosing multi-instance subprocess and the own round of a multi-instance user
task. It answers while the task is created and while it is cancelled.
`Camunda7MultiInstancesAtAUserTaskTest` holds both.

The gap cost more than an empty map. A named `@MultiInstanceElement("...")` parameter does not
become `null` where the level is missing. Instead the core refuses the call, the task listener
fails, and the engine rolls back the transaction which creates the user task. Measured by
`Camunda7MultiInstanceByExpressionIT#aUserTaskIsToldTheIterationItRunsIn` against the unchanged
adapter: the workflow never got past its start, and the outbox kept retrying the refusal,
twenty-five times in that one run. A `@MultiInstanceElement(resolverBean = ...)` parameter is the
silent half of the problem, because the resolver can read an empty map.

So the user-task context asks the walk the same way the service-like context asks it, with the
registry of its engine. The registry is what crosses a call activity which names the called process
in an expression (decision 34). The user-task context now has the registry instead of the adapter
id and the datasource flag it used to get. It reads those two from the registry.

Nothing of decision 22 or 34 changes. The walk is the same walk, and the question about the
workflow aggregate is the same question. Its third reader now holds the registry which the other
reader in this repository already held.

### 39. A delivery is recorded in both datasource modes, and only the deduplication differs

*Merged into decision 6 on 2026-10-12, before the 2.0 release.* It said that a delivery is recorded
on both kinds of datasource, and that the two modes differ only in the deduplication.

### 40. A workflow is served from the model of its own version

Camunda 7 evaluates the expressions of the model a workflow was started with. Two versions of a
process may name different expressions at the same element. For a process the application
deploys, the adapter used to know the tasks of that one model and nothing else. A workflow of an
older version then asked for the old name, and the adapter did not know it. The lookup by element
then found the task of the newer version at the same id. So that workflow ran a method its own
model never named. It did so when the old method was still there. And it did so when the old method was removed, although
the startup check had just said that the workflow would end in an incident.
`Camunda7HandlersOfTheOwnVersionIT` measured it for a task wired by `camunda:expression`, a task
wired by `camunda:delegateExpression` and a listener written as `camunda:expression`. All three ran
the method of the newer version.

So the lookup asks for the tasks of the process definition a workflow runs on. The registry reads
that model the first time a workflow of the definition asks. It uses the extraction a deployed model
goes through, and it keeps the answer. A definition never changes, so the answer never gets out of
date. The model comes from the execution itself, out of the engine's deployment cache. The task
listener of a user task and the listener which reports a cancellation ask the same way.

There were two other ways. The first was to wire every version the engine holds while the
application starts, as `wireTheVersionsHeldUnder` does for a declared process id. That would
register the old names, so the lookup by name would find them. But it keeps every version in one
list, so the lookup by element can still cross versions. And decision 10 asks that a start does not
grow with the number of versions. The second way was to put the version into the key of the
registry. That needs the same model read and ends up as this decision with more code. Reading per
definition is exact. It costs one read per version a workflow really runs on. And it also covers a
version which another node deployed after this one started.

A model the extraction refuses gets no task at all and one warning, the same way decision 13
handles a held version under a declared id. Its workflows then end in an incident at their next
task.

What the application sees when a method of an older version is missing does not change. The core's
check of held versions reports it while the application starts, and the workflow ends in an
incident at that task. The incident names the task definition, the element and the version. Only
the method of the newer version no longer runs in its place. A listener whose method is gone is no
longer a VanillaBP listener. So the engine evaluates its expression itself, and the incident names
the expression.

`Camunda7TasksOfTheOwnVersionTest` holds the lookup. `Camunda7HandlersOfTheOwnVersionIT` holds the
upgrade against a running engine.

### 41. A standard loop is refused in a claimed process, and a held version is warned about while workflows run on it

#### What was decided

Camunda 7 does not run a standard loop. An activity with `standardLoopCharacteristics` deploys
without a word and runs once. The workflow moves on, and the engine writes nothing above DEBUG
about it. Measured on 7.24 by `Camunda7StandardLoopTest`: `BpmnParse` reads only
`multiInstanceLoopCharacteristics`. A model which counts on the loop does its work once, and
somebody finds out in production.

So the deployment first reads the model for the marker, at any depth of its subprocesses. It
answers in two ways:

- A BPMN process which a `@WorkflowService` class claims does not deploy. The message names the
  activity, the process, the workflow module, and what the engine does with the marker. It also
  names the two forms which really repeat an activity: a loop in the sequence flow, or a
  multi-instance element with `@MultiInstanceElement`, `@MultiInstanceIndex` and
  `@MultiInstanceTotal`.
- A version the engine already holds is never refused, because nobody can change it. Where
  workflows still run on it, the start of the workflow module logs a WARN. The WARN names the
  version, the activity and how many workflows are on it. A version no workflow runs on any more is
  skipped. The model is read first, and the workflows are counted only where the model has the
  marker.

A process nobody claims is not looked at (decision 43). It is somebody else's model. Ending the boot
over it would take the application down for a process it does not run.

#### Why

We accept the price on purpose: a model which deployed in version 1 does not deploy any more. The
upgrade is the moment when somebody can still change the model, and the refusal is how they learn
that the loop never ran. The Camunda 8 adapter refuses the same model the same way. So both
adapters agree, and a migration from Camunda 7 to Camunda 8 does not stop at a model which was just
as wrong here.

This is a rule which refuses a model. If it came after the 2.0 release, it would stop an
application which boots today. That is why it comes with 2.0.

`Camunda7StandardLoopRefusalTest` holds the refusal, the unclaimed process which is not looked
at, the multi-instance element which must deploy, and the WARN about a held version with and
without workflows on it.

See [A standard loop is refused while deploying](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Deviations#a-standard-loop-is-refused-while-deploying).

### 42. A user task needs a method or a line, and an external task may pass with the line

Supersedes decision 36. On 2026-10-07 the platform decided two things. Every task of a claimed BPMN
process needs a `@WorkflowTask` method or the property `implemented-externally=true`. And the core
holds that rule for every adapter (decision 119 of `adapter-platform-integration`). This entry says
what it means for this engine.

**User tasks.** Decision 36 let a user task without a method pass and named it once at INFO. It was
based on a wrong reading of version 1, which it said never spoke about such a task. But version 1
did: `TaskWiringBpmnParseListener.parseUserTask` (1.5.0) made every user task a connectable, and
`Camunda7TaskWiring.wireTask` called `super.wireTask(connectable, false, ...)`. So the boot ended
with "No public method annotated with @WorkflowTask is matching task". Version 2 now does the same.
It offers the property for a user task which a task list works off on purpose. The INFO line and
`Camunda7UnservedUserTasks` are gone. The adapter still hands a user task over with `optional` set.
Now this only says "user task": the core asks for no method in a version the engine only still
holds.

The core's message replaces the adapter's. It names the form key as the task definition and both
ways to mark the task. So what decision 36 said about a concrete message per BPMS still holds.

**External tasks.** A service task wired by `camunda:topic` is served by whatever polls the topic,
and that is never this adapter. Deploying one used to be refused, with no way out for an
application which does run such a worker. Now the adapter asks the core whether the task is marked
(`WorkflowTaskWiring.isImplementedExternally`). A marked task goes to `validateTaskWiring` like any
other task, with the topic as its task definition. So the core refuses a method next to the line,
and warns about a line which no model needs. No part of this adapter subscribes to the topic. An
unmarked task is refused as before, and the message now names the line as the third way out.

**Listeners.** The engine itself resolves an execution listener whose expression no method names,
for example a Spring bean. Such a listener stays none of this adapter's business. Only the
listeners a method serves reach the core, now through `BpmnTaskSpec.listener(...)`. So a method
which names the element id no longer counts as serving a listener on that element.

`ImplementedExternallyTest` of the platform holds the rule. In this repository,
`Camunda7ListenersReportTest` holds the refused and the marked external task. The integration tests
mark the user tasks they leave without a method.

### 43. The parse listener changes only a process the application claims

Proposed by story 937. Decided by the maintainer on 2026-10-07.

On 2026-10-07 the platform decided, in a decision of `adapter-platform-integration` of its own,
that a process nobody claims is not supported. The core ends the start over a deployed process
which no `@WorkflowService` class claims. The exception is a process the application marks with
`vanillabp.workflow-modules.<module>.workflows.<process>.implemented-externally=true`. What this
adapter does with the processes it meets follows from that.

**The parse listener.** The engine runs `Camunda7AsyncBpmnParseListener` for every model it parses:
the ones this application deploys, and the ones somebody else deploys into the same database. It
used to change all of them: the `asyncBefore`/`asyncAfter` flags, `asyncBefore=false` on a user
task, the cancellation listener, the start listener on every start event, and the built-in
user-task listeners. Now it changes a process only where the application claims it. It asks
`Camunda7TaskRegistry#claimsTheProcessDefinition`. That method finds the workflow module through the
way back which the deployment registered, and then asks the core's
`WorkflowTaskWiring.isClaimedByAWorkflowService`. A definition this adapter did not deploy has no
workflow module to be found under, so it is never claimed. A process somebody else deployed keeps
its flags exactly as modelled, and so does a marked process of this application.

The way back is registered at the very start of `wireBpmn`, for a claimed process and for one
nobody claims, because the engine may parse a model as soon as it is deployed. The declared ids of
a renamed process are claimed. So the versions the engine still holds under them keep their flags.

A definition without a tenant is found by its key alone, and that key may be the key of a claimed
process. So the key leads to a workflow module only where the module deploys without a tenant. A
module which deploys into a tenant owns no definition without one. Somebody else may deploy a
process without a tenant under the id of such a module's process. That process is not claimed: it
gets no listener, and a start with a business key is not refused. A module which deploys without a
tenant finds its definitions by the key as before. `Camunda7ForeignProcessUnderAClaimedIdTest`
holds this.

**`wireBpmn`** returns right away for a process nobody claims. No task is collected, no external
task is refused, no standard loop is warned about, no listener is registered, and no start message
is reported.

**The start listener** also asks `Camunda7TaskRegistry#isClaimedByAWorkflowService`. It now sits only
on the start events of a claimed process, so the question is a guard and not the rule.

**Version 1.** Its `TaskWiringBpmnParseListener` (1.5.0) was a custom pre-parse listener of the
engine. A custom pre-deployer set the workflow module id from the name of EVERY deployment. So the
listener also ran for models which other applications deployed, flags included. For such a model it
then asked for a workflow service and threw "No bean annotated with @WorkflowService", and the parse
failed. So version 1 did not run a foreign process on its engine at all. Version 2 runs it as
modelled. The upgrade note says so.

This adds to decision 5 (what the adapter changes), 28 (a process this application does not serve
is left alone) and 41 (the WARN about a standard loop in a process nobody claims is gone).
`Camunda7UnclaimedProcessesAreLeftAloneTest` holds a claimed, a marked and a foreign process in one
engine.

### 44. The start of a called process is not reported, but the listener still hands the caller's name on

The platform decided that a called process is not a workflow of its own (decision 124 of `adapter-platform-integration`,
story 949). Its start and its end do not reach the application. The core tells an adapter which processes these are:
`BpmsInitiatedStartInvoker#startsAWorkflowOfItsOwn` and `WorkflowEndedInvoker#workflowEndedHandlerExists` answer
`false` for a process which the application declares only in `secondaryBpmnProcesses` and deploys a model for.

**The end.** The parse listener already asks `workflowEndedHandlerExists` before it attaches the end listener. So a
called process gets no end listener now. Nothing in this adapter had to change for that. Before, the extended
`Camunda7CallByExpressionIT` measured two `@WorkflowEnded` calls per workflow: one at `CBEC_End` of the called
process and one at `CBE_End` of the caller. Now it measures one.

**The start.** The start listener stays on every start event the process holds (decisions 27 and 28), including the
called process. It has a second job there. A call activity which names its process in an expression leaves the
caller's name to this listener (decision 32). So the listener asks `startsAWorkflowOfItsOwn` when it fires. Where
the answer is `false`, it writes the caller's name into the instance if it has to, and reports nothing.

The other way was to leave the listener off the called process at parse time. That would have broken the call by
expression, because nothing else gives that instance its name.

The tests: `Camunda7CallByExpressionIT` asks for exactly one end per workflow, for a call by id and for a call by an
expression. `Camunda7CalledProcessStartTest#aCalledProcessIsNoStartOfItsOwn` holds that the name is handed on while
nothing is reported.

### 45. A value of a called process is read under the called process' own id

Proposed by story 950. Decided by the maintainer on 2026-10-09.

On 2026-10-09 the platform decided, in a decision of `adapter-platform-integration` of its own,
that a task belongs to the BPMN process which contains it. For a task of a called process, that is
the called process. This adapter followed the rule where the engine hands a task to the
application. It did not follow it where the application calls the process service. A process
service belongs to the process at the top, so its requests name that process, and the adapter used
that id for everything it read.

**The serialization format.** The format of a nested shared value is configured per BPMN process
(`vanillabp.workflow-modules.<module>.workflows.<process>.adapters.<id>.serialization-format`).
These calls write values into the process instance which waits: completing or cancelling a task,
completing or cancelling a user task, pushing for a task, and correlating a message. The adapter now
reads the format under the process which that instance runs. It finds the process from the
instance's process definition and tenant, through the way back the deployment registered
(`Camunda7TaskRegistry#resolve`). Where it cannot tell, it uses the process of the call, as before.
It cannot tell when the instance is gone, when the process was not deployed by this adapter, or
when several instances wait for one message. The task path, `Camunda7WorkflowTaskBehavior`, already
read the format of the task's own process. So both ways now agree.

**A push without a task.** `aggregateChanged(aggregate)` wrote only into the instance of the
process at the top. In Camunda 7 a called process is a process instance of its own, with variables
of its own. So a conditional event waiting in the called process never saw the push. Measured on
2026-10-09 with Camunda 7.24 in memory: a conditional event behind an event-based gateway in the
called process stayed waiting. The push now also writes into every called instance which continues
the aggregate, each in the format of its own process. A called instance continues the aggregate
where it has the same business key. The deployment passes the key on exactly where caller and
called process share the aggregate (decision 5). The walk stops at a called process with an
aggregate of its own, because what that process calls belongs to its own aggregate.

**The viewer.** `getProcessDefinitions` reported the latest deployed version of every called
process, which is the version a call activity would call next. So a workflow which waits in an
older version was drawn with a model it never ran. Now it reports the version the called instance
runs or ran on. It reads it from the history, and at history level `none` from the called instances
which still run. A call activity which has not called anything yet still reports the latest
version. With history level `none` and a history context, the running called instance is now
checked against the root of the workflow. This is the same check the history path always made.
Without it, any called instance id showed its definition to whoever passed it.

**What stays.** The local variable which a correlation id is compared with keeps its version-1
name, `<primary bpmnProcessId>-<messageName>`. This also holds where the message is caught in a
called process. The models set that variable, so a new name would be one which no existing model
sets. The wiki page `Configuration` explains it with an example.

`Camunda7SettingsOfACalledProcessTest` holds the format and the push.
`Camunda7ViewerOfACalledProcessTest` holds the viewer.

### 46. A bean or a shared attribute named like a task definition ends the start, and VanillaBP's EL resolver stays last

Camunda 7 resolves a top-level EL name by asking its resolvers one after the other, and the first answer wins. The
adapter adds its own resolver at the END of that list, on Quarkus and on Spring Boot. Before it, the engine asks its
process variables, and on Spring Boot the beans of the application context. Version 1 had the same order.

So a name which is a task definition AND a bean, or a task definition AND a process variable, reaches the bean or the
variable. The `@WorkflowTask` method never runs, and nothing says so. For a bean this was already true in version 1.
For an attribute of the workflow aggregate it is new: version 1 read attributes in its own resolver and asked for the
task first. Since decision 1 every shared attribute is a process variable, so it now answers before the task.

**The order stays.** A task wired by its element id answers every name which is evaluated while an execution sits at
that element (`Camunda7TaskConnectable#applies`). With VanillaBP's resolver first, an input mapping `${amount}` at such
an element would run the handler instead of reading the variable. That breaks a correct model to protect a wrong one.

**The start ends instead.** `wireBpmn` checks every task and listener of the model this boot deploys which the engine
reaches through an expression. User tasks are not among them: a form key is not evaluated as an expression. A task
definition ends the start if a Spring bean has its name (the same `containsBean` the engine's resolver asks), or if
the aggregate shares an attribute of that name with the engine. The core answers which attributes are shared, under
this adapter's sync mode. Quarkus puts no beans into the engine's expressions, so there only the attribute counts.
The message names the task definition, the BPMN process, the element, the workflow module, and the bean with its class
or the attribute. It also names the ways out: rename the task definition, rename the bean or the attribute, or do not
share the attribute.

**What the start cannot see.** A process variable which the model or the application creates while a workflow runs.
It is not checked at runtime either. The documentation names the case instead. A model the engine already holds from
an earlier version is not checked. Nobody can change it any more.

Camunda 8 has no such collision. A task there is a job type, and no expression is resolved to find its worker.

### 47. The tables of an engine are compared across adapter types, too

Proposed by story 984. It follows decision 129 of `adapter-platform-integration`, stated here from
this adapter's side.

Two `camunda7` ids are the same engine if they share the datasource and the table prefix.
`Camunda7InstanceIdentity` compares them, and the platform hands this adapter only the ids of its own
type. The forks CIB seven and Operaton use the same tables. A fork adapter is generated from this
code, so it would compare only its own ids, too. A `camunda7` id and a fork id on the same tables
would pass both checks. Then each job executor runs the jobs of the other engine, and nothing fails
at startup.

**The adapter answers `getAddressedSystem()`.** The platform compares that answer for every id,
across types. This adapter builds it from the same `Camunda7InstanceIdentity` as the check of its
own ids, so both checks agree on what "the same tables" means. The key starts with the words
`embedded engine tables` and names the datasource and the prefix. It contains no type and no
`camunda`, so the generated fork adapters build exactly the same key. The advice in the message names
`data-source-name` and `table-prefix`, the same two ways out as the check of one type.

**The reserved datasource name `default` counts as no name.** Before, `data-source-name: default`
and an id without a name were two different datasources to the check, although both mean the
application's datasource. Now both are the same, in the check of one type and in the key.

The check of one type stays as it was, with its own message. It runs first.

### 48. The IDE learns every key from the metadata of the Spring Boot jars, and a test keeps it complete

Built by story 993 on 2026-10-21.

An IDE offers the keys of `application.yaml` and marks a key it does not know. It reads them from
`META-INF/spring-configuration-metadata.json` in the jars of the application. Until this story the
adapter shipped no such file, so every key of the adapter looked unknown. Version 1 had none either.

Both Spring Boot modules, `spring-boot` and `spring-boot-webapps`, now write the file in two parts:

- The configuration processor writes one entry for each map of the overlay class
  (`VanillaBpCamunda7Properties`, `Camunda7WebappsProperties`): `vanillabp.adapters` and, in
  `spring-boot`, `vanillabp.workflow-modules`. Each entry names the type of the map's values. The
  processor never describes the keys below a map key, because the application chooses that key. An
  IDE which follows the value type finds the keys there, for every adapter id, workflow module,
  workflow and task. We expect IntelliJ IDEA to do this, but nobody tried it in an IDE yet.
- `additional-spring-configuration-metadata.json` is written by hand. It describes every key of the
  adapter level once more, under the adapter id `camunda7`. That is the id which needs no `type`
  key. These entries are the only place where a key gets a type, a
  description and a default which every IDE shows. The
  metadata format has no name for "any id", so the levels below a workflow module cannot get entries
  of their own. A key which is also read there names those levels in its description instead. A map
  inside the adapter level, `engine-plugins`, is described as one entry with its type, like the
  processor does.

`EveryKeyIsKnownToTheIdeTest`, one in each module, walks the overlay class the way the binder does.
It fails when a key has no entry, when an entry describes a key nobody reads, and when a type or a
default in the file differs from the code. So a new key needs its field in the overlay of both
platforms and its entry in the file, or the build is red.

`vanillabp.ignore-suspended-process-definitions` has no entry. It is a system property or an
environment variable, not a key of `application.yaml`.

The platform also writes an entry `vanillabp.adapters`, with a type of its own. Which entry an IDE
uses when several jars describe the same name was not tried in an IDE. The Quarkus modules need
none of this: they are Quarkus extensions, and their build writes a description of every key into
`META-INF/quarkus-config-doc/`.

### 49. Every map of the Quarkus configuration names its key, with the names all adapters share

Built by story 995 on 2026-10-28.

The Quarkus module is a Quarkus extension. Its build writes a description of every key into
`META-INF/quarkus-config-doc/quarkus-config-model.json` (see decision 48). A map is a level where
the application chooses the key, for example an adapter id. Quarkus writes such a key as a name in
quotes. Without help it takes the name of the Java method. So the file showed
`vanillabp.adapters."adapters".engine-plugins."engine-plugins".properties."properties"`, and nobody
could tell from it what to write there.

Now every map of `VanillaBpCamunda7Properties` carries `@ConfigDocMapKey`. The platform and every
adapter use the same names. The first three are also the names which the platform wiki writes in
angle brackets, for example `<adapter-id>`:

|        Map        |         Name         |
|-------------------|----------------------|
| `adapters`        | `adapter-id`         |
| `workflowModules` | `workflow-module-id` |
| `workflows`       | `bpmn-process-id`    |
| `tasks`           | `task-definition`    |
| `enginePlugins`   | `plugin-name`        |
| `properties`      | `property-name`      |

So the key above now reads
`vanillabp.adapters."adapter-id".engine-plugins."plugin-name".properties."property-name"`.

`EveryMapKeyHasANameTest` reads the file which the build wrote. It fails when a map key has a name
which is not in this table. So a new map needs the annotation, or the build is red.

The Quarkus Tools plugin for IntelliJ IDEA (by Red Hat, checked in version 2.6.1) does not read this
file. It finds the keys in the `@ConfigMapping` interfaces themselves and writes every map key as
`{*}`. So the names help the generated documentation and everybody who reads the file. They do not
change what this plugin offers while you type.
