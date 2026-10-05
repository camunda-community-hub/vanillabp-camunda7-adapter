# Upgrade notes

Contributor-facing list of what a VanillaBP 1 application on Camunda 7 has to do, organised per
version line. It describes the step to the release, not how the release was built - the
development history is in git. These entries feed the user-facing
[migration guide](https://github.com/vanillabp/adapter-platform-integration/wiki/Migrating-from-version-1);
the same file exists for
[VanillaBP itself](https://github.com/vanillabp/adapter-platform-integration/blob/main/UPGRADE.md)
and for the [Camunda 8 adapter](https://github.com/vanillabp/camunda8-adapter/blob/main/UPGRADE.md).

## 2.0

### A message starts only the process of its own `ProcessService`

Version 1 correlated the message of `startWorkflowByMessage` with the engine's
`correlateStartMessage()`, so every process with a start event for that message started,
whichever `ProcessService` you called. Version 2 refuses a message which does not start the
process of the `ProcessService` you call, with an `IllegalArgumentException` before anything is
saved. On Camunda 7 the correlation also names the process definition, so even a message the
check cannot judge starts no other process.

Where your code starts a process through the `ProcessService` of another one, call the
`ProcessService` of the process the message is meant for instead. Camunda 7 compares the
message name as the model writes it once the correlation names a definition. So a message start
event whose name is an expression, such as `${orderMessage}`, cannot be started by a message any
more. Give it a plain name.

### A shared value Camunda 7 has no variable type for needs a serialization format

Version 1 answered the expressions of a model from the live workflow aggregate, so
`${order.customer.name}` navigated a Java object and there was nothing to configure. Version 2
writes what the aggregate shares as Camunda process variables, and whatever the engine has no
variable type for becomes an object variable, which it stores in whatever serialization format it
was told to use. That is a nested value, an object or a collection, and it is a number this engine
cannot store as itself: a `BigDecimal`, a `BigInteger` or a `Float`.

Name the format, and give the engine the dataformat which provides it:

```yaml
vanillabp:
  adapters:
    camunda7:
      serialization-format: application/json
      engine-plugins:
        spin:
          plugin-class: org.camunda.spin.plugin.impl.SpinProcessEnginePlugin
```

`application/json` comes from the SPIN JSON dataformat, `application/xstream` from
[camunda-xstream](https://github.com/RasPelikan/camunda-xstream), and the dependency providing it
belongs to the application. VanillaBP passes the adapter-level value to the engine's
`defaultSerializationFormat` as well as to every variable it writes, and a workflow module or a
single workflow may deviate
(`vanillabp.workflow-modules.<module>.adapters.<id>.serialization-format`,
`vanillabp.workflow-modules.<module>.workflows.<workflow>.adapters.<id>.serialization-format`).

Configure nothing and the engine's own default applies, which without a dataformat is Java
serialization. Cockpit then shows a base64 blob where the data should be, so nobody operating the
workflows can read what a decision was made on. And the engine's database holds serialized
instances of the application's own classes, so `${order.customer.name}` keeps evaluating only as
long as the class in the database still matches the class on the classpath. The adapter warns
about it once per JVM when it writes such a value, and the warning names the property to set and
the dataformat to add.

A format is not free of loss either, and where it loses something the boot says so. JSON has one
number type, so a `BigDecimal` of `120.50` comes back as `120.5`, and a nested one comes back as a
`Double`. While your application starts, the adapter writes a sample of the type through the
engine's own serializer and warns about every attribute your models read whose value would not
come back as it went in. The warning names the attribute, the format and what the engine
answered.

An application whose aggregates share nothing but values this engine stores as themselves meets
none of this. A short, an integer, a long, a double, a boolean, a string, a date and bytes are
scalar variables, `${amount > 1000}` compares numbers as it did before, and a `Character` is
written as a string, which EL compares to `'A'` and to `"A"` alike. A number the engine has no type
for keeps its class instead of being widened to a double, which is what version 1 read.
`${total > 100}` compares numbers either way, and `${total}` renders the value your code holds
rather than one somebody converted on the way in.

The [page about a shared value](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/What-becomes-of-a-shared-value)
of the wiki carries the narrated version. Why such a value travels in the engine's own format
rather than as a JSON string is decision 9 in the repository's [`DECISIONS.md`](./DECISIONS.md),
and why a number keeps its class is decision 16.

### What the startup check tells you about your expressions, and what it cannot

Version 1 answered every BPMN expression from the live workflow aggregate, so nothing a model read
could be missing. Version 2 writes what the aggregate SHARES, so an expression reading something
unshared reads `null` instead, and on Camunda 7 a null is often silent: a gateway with a default
flow takes it, a conditional event answers false and keeps waiting without an incident and without
a log line, and a completion condition which is never true lets a multi-instance element end as if
it had done its work.

The adapter therefore lists such expressions while your application boots, one WARN each, naming
the element, the expression, the segment which stops the path, what this engine will do with the
null and how to fix it. Nothing fails: an expression the check misreads must not keep your
application from starting. Treat the list as your migration backlog and work it off before you go
live, because the runtime will not remind you.

Know where the list ends. The check reads the DECLARED types of an expression's path and stays
silent wherever they cannot decide, which is a `Map`, an interface, an abstract type or a
collection which does not say what its elements are. It judges no method call either, so
`${order.getTotal()}` and `${order.status.name()}` are not in the list although both stop working
on version 2: the flattened value is a map and a text, neither of which has those methods. Those
two shapes are LOUD at runtime, an incident naming the expression and the class it looked at, which
is why they are left to the engine. What is silent at runtime is what the check is for.

The list also does not cover the input expressions of a business rule task, and it says nothing
about Camunda 8 or the Process-Engine-API. Those adapters share the same flattening, so the same
expressions meet the same missing keys there, but neither hands the adapter a parsed model of this
shape and FEEL is not JUEL. The gap is known and not closed here.

The [configuration page](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Configuration#migrating-from-an-adapter-which-read-the-aggregate-live)
of the wiki carries the narrated version.

### A renamed BPMN process no longer needs its old model deployed

An application may declare a BPMN process id it deploys nothing under
(`@WorkflowService(secondaryBpmnProcesses = ...)`), which is how a renamed process keeps being
served. On version 1 that declaration reached the handler registry and not the engine: this
adapter wired the tasks of the models it deployed, so a workflow still running under the old id
reached its next task, found nothing wired to it and ended in an incident. The way through was to
keep the old BPMN file next to the new one until those workflows had ended.

Version 2 wires the old id as well. While a workflow module starts processing, the adapter asks
the core which ids it declares without a model and reads the model of every version the engine
still holds under each of them, out of the engine's own repository. Those workflows then reach the
`@WorkflowTask` methods of the current application, which is what the declaration always promised.

Nothing has to be configured for it and nothing has to be removed. An application which keeps the
old model deployed is unaffected, since then nothing is declared without a model. An application
which deleted the old file gains one line per declared id in the log of every start, saying how
many versions were wired, and it costs no query beyond the one the check for old process versions
runs anyway.

Two things are worth knowing while the declaration stands. The engine has to still hold the old
definitions, which it does as long as workflows run on them, and a version whose tasks the
application dropped is reported by the check for old process versions with the number of workflows
it affects, exactly as for the older versions of any process. The
[recipe for a rename](https://github.com/vanillabp/adapter-platform-integration/wiki/Renaming-a-BPMN-process)
is on the platform's wiki.

Version 2 also judges the declaration itself. The engine keeps firing the old model's timer and
keeps matching its signal subscription, so the adapter reads the start events of the versions it
holds under the old id, and you are told about a `@WorkflowStartedByBpms` method none of them has
a start event for. A `@WorkflowEnded` method kept for the old id is
checked in the same place. Both are warnings and neither stops a start: they read models nobody
can change any more.

### What the outbox costs where version 1 used your transaction

Camunda 7 runs inside your application, so version 1 could do its engine work in the transaction of
the caller, and for most operations it did. Version 2 schedules every operation which progresses a
workflow through the phase-two outbox, which is decision 2 of this repository. What that costs is
written once for every adapter, in the platform's
[upgrade notes](https://github.com/vanillabp/adapter-platform-integration/blob/main/UPGRADE.md#what-the-upgrade-costs-under-load).
What is special here is which of your calls pay it.

A workflow start pays nothing new. Version 1 wrote a job of the engine inside your transaction and
let the job executor run it, so the start already cost a row in your transaction and a transaction
after it. Version 2 writes an outbox entry where that job was. The row lands in another table and
the call leaves on another thread.

Answering a message is where the cost appears, and so is completing or cancelling a task. Version
1 called the engine while your transaction was open, so your commit covered the engine's work.
Version 2 writes an entry for it and calls the engine after your commit, in a transaction of its
own.

The job executor stays where it was. Service tasks are still asynchronous before and after, as
version 1 made them, so your handlers still arrive on job executor threads and the engine's own
transaction count per task is unchanged. The dispatch threads of the outbox are new beside it, and
both draw on the database your workflow aggregates live in.

An application which had raised `camunda.bpm.job-execution.max-pool-size` should look at
`vanillabp.outbox.dispatch-threads` as well. Camunda's Spring Boot starter starts the job executor
with three threads and grows it to ten, and that pool used to carry VanillaBP's starts along with
the engine's own work. Four dispatch threads carry them now.

### The wakeup job executor has a new key and is no longer experimental

Version 1 had an experimental job executor behind `camunda.bpm.job-execution.wakeup`. The key is
gone, because it belonged to Camunda's Spring Boot starter rather than to the adapter, and a
migration setup runs two Camunda 7 adapter ids which may want different answers. The same feature
is configured per adapter id and is supported:

```yaml
vanillabp:
  adapters:
    c7:
      sleep-until-something-is-due: true
```

It is still off by default, so an application which does nothing keeps the engine's own timing. An
application which had the version 1 key set does have to act: without the new key its engine polls
every 5 to 60 seconds again.

What it does has changed in three ways, and all three are worth reading before switching it on.

The waiting is the feature, and the waking is not. Version 1's README sold an immediate wake-up
after a commit which created a job; the engine has always done that by itself for a job due now.
What this adds is the case the engine does not cover, a job due LATER, which is only worth
something once the executor stops polling.

Everything which commits wakes the executor now, not four methods of one class. Version 1 published
a Spring event from `Camunda7ProcessService`, so a plain save of a workflow aggregate, a signal and
a continuation the engine wrote for itself woke nothing. The servlet filter version 1 put on the
Camunda webapp and on `/engine-rest` is gone with it: those are engine commands like any other and
are covered without a filter. Nothing has to be configured for the waking, and the Spring
`TaskScheduler` bean version 1 demanded is no longer needed.

Two engine settings follow the key. Jobs are acquired by due date, which version 1's README claimed
while its code set neither flag, and that order wants a database index on the due date of
`ACT_RU_JOB` which only the operator can create. And the engine's metrics reporter stops writing,
because it would otherwise wake a waiting engine four times an hour; set `db-metrics-reporting:
true` to keep it.

There is also no Quarkus caveat any more. Version 1 had no Quarkus artifact at all, so the feature
was Spring-only by accident; it works on both platforms now.

The [README section](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/blob/main/README.md#an-idle-engine-lets-go-of-its-database)
and the [configuration page](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Configuration#letting-an-idle-engine-go-quiet)
of the wiki carry the details.

### One tenant for every workflow module refuses two modules sharing a BPMN process id

Version 1 deployed each workflow module into a tenant named after it, and `tenant-id` was the way
to name the tenant yourself. An application which names one tenant for the whole adapter puts every
workflow module into it, and two modules which declare the same BPMN process id then arrive at the
engine under one process definition key. Camunda 7 keeps one definition under that key and loses
the other, so one of the two modules runs on a model nobody deployed. Version 1 deployed it and
said nothing.

That application boots today and will not boot after this. The deployment of the SECOND of the two
modules ends the boot, with the first one already in the engine, and the message names both
workflow modules, both process ids, the key they share and the way out. Only this one configuration
is affected: one `tenant-id` for the whole adapter, the mode `by-adapter`, and the same BPMN process
id in two workflow modules.

Three ways out, and the first one keeps every other module where it is:

```yaml
vanillabp:
  adapters:
    c7:
      tenant-id: shared-tenant
  workflow-modules:
    loan-approval:
      adapters:
        c7:
          tenant-id: loan-approval   # a scope of its own, per workflow module
```

The name per workflow module is new; it was only settable for the whole adapter before. The other
two ways are `name-clash-avoidance: use-prefix`, which drops the tenant and prefixes the
identifiers with the workflow module id instead, and renaming one of the two BPMN processes. Which
of the three fits depends on what the running workflows of the engine are deployed under, so read
[what the mode changes](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Configuration#keeping-workflow-modules-apart)
before changing a booting application.

An application which configures no `tenant-id` at all meets none of this: every workflow module has
a tenant of its own, which is what lets two of them use one process id in the first place.

### A listener served by a `@WorkflowTask` method has to be allowed now

Version 1 served a `camunda:executionListener` with a `@WorkflowTask` method and documented it
nowhere. It did so in one place only: an END EVENT and an INTERMEDIATE THROW EVENT, with the listener
written as `camunda:expression` or `camunda:delegateExpression`. Anything else on those two elements
ended the boot with `Unsupported listeners at ...`, and a listener anywhere else in the model was the
engine's business alone. The task definition was the unwrapped expression text, so `${archiveOrder}`
was served by `@WorkflowTask(taskDefinition = "archiveOrder")`, while an expression like
`${bean.doIt()}` became the literal task definition `bean.doIt()` and left only
`@WorkflowTask(id = "<element id>")` usable.

If your models carry such a listener and a `@WorkflowTask` method of yours names its expression, this is
the entry to act on. Version 2 does not serve it unless you say so, and a model carrying one ends the
boot with a message naming the elements, the key and what it costs. Say so per adapter, per workflow module or per workflow, and the most specific
configured value wins in both directions:

```yaml
vanillabp:
  adapters:
    c7:
      allow-listeners: true
  workflow-modules:
    loan-approval:
      adapters:
        c7:
          allow-listeners: false   # this module does not, whatever the adapter says
```

The boot failure is the good case, and it is deliberate. Without the key there is no task and no
worker for the listener, so the engine evaluates the expression itself: a workflow reaching the
element either fails on a name nothing resolves or runs a method the application never meant for that
element, both of them at runtime and in production rather than at a boot somebody is watching. The
Camunda 8 adapter has the same key for the same reason, where a workflow would stall at the listener's
job with nothing in the log.

Read what the key costs before you set it. A listener is where a BPMS lets an application in at a
moment the BPMS owns, and every BPMS draws that moment differently, so the model stops being portable:
another BPMS has no listener at this element and a migration of the model stops at the method serving
it. Every boot of a workflow module whose listeners are served writes a framed WARN saying it, naming
each listener and the way back, and no key silences it. Where you can, move what the listener does into
a task of the model with a `@WorkflowTask` method behind it, which is the way back the report names.

Four things change beyond the key itself.

Any element may carry a served listener now, not only an end event and an intermediate throw event.
That is a wider door than version 1 had, and the key is what keeps it shut by default.

`@TaskEvent` answers two moments now. On version 1 it received `CREATED` for every listener event,
which said nothing about whether the listener fired on `start`, on `end` or on `take`. Here the event is
part of the wiring: one method serves one event of one element, so the parameter receives `CREATED`
whenever the modelled listener fires. It receives `CANCELED` when the element the listener sits on is
canceled, which VanillaBP reports through the engine's end listener. A method without the parameter
subscribes to `CREATED` alone and hears no cancellation. A listener you modelled on `end` gets no report
of its own, because the engine fires an END execution listener on a cancellation too and such a method
already hears the moment.

A `@TaskId` parameter is refused while the process is wired. A listener is notified and done, so such a
task can never stay open and the id would complete nothing. Version 1 accepted the method and the
workflow went on without it.

Two listeners of one element under ONE expression end the boot naming both. Version 1 ran one of them
and a `findFirst` decided which, so the model said something it could not deliver. Give every listener
of an element an expression of its own and write a method per expression.

Two things got easier. A `camunda:class` or `camunda:script` listener no longer ends the boot on an end
event or an intermediate throw event: the engine runs it itself, there is no expression naming a task
definition, and this version says nothing about it at all.

And a listener whose expression names something other than a `@WorkflowTask` method is left exactly
where it is. With Spring or CDI a delegate expression is resolved against your application context, so
`${auditTheOrder}` naming a bean of your own which implements Camunda's `ExecutionListener` is an
ordinary Camunda 7 model: VanillaBP does not read it, does not refuse it and does not mention it. Only a
listener whose expression names a method you wrote is what the key above is about, which is exactly the
version 1 shape.

The [README section](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/blob/main/README.md#listeners-somebody-modelled)
and the [configuration page](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Configuration#listeners-somebody-modelled)
of the wiki carry the details.

### A called process is told about the iteration of its caller, unless it has a workflow aggregate of its own

Version 1 reported ONE multi-instance level, the innermost one, and it reported none at all for the
most ordinary decomposition there is. A plain call activity inside a multi-instance subprocess, with
the called process in a BPMN file of its own, ended the task with
`No multi-instance context found for element '...' or its parents!`. The same models with both
processes written into ONE file worked, because the lookup then found the enclosing element by
chance. Version 2 reports every level of every caller, outermost first, whichever way the files are
split.

A handler which used to fail with that message runs now, and nothing has to be changed for it.

A handler may now name an element which encloses its own. `@MultiInstanceElement("OrderBatch")` on a
task inside `OrderBatch` used to be the only name the map held, and an outer element was answered
with `null` or with a `NullPointerException` inside the framework. Both are answered now, and a
`MultiInstanceElementResolver` finally sees the sorted map from outermost to innermost its javadoc
always promised.

A process with a workflow aggregate of ITS OWN is told nothing about the iteration which called it.
Version 1 took every call activity for decomposition and reported across it. Version 2 asks whether
the calling and the called process work on the same workflow aggregate, which is what the business
key follows as well, and it reports nothing where they do not. If a handler of such a process reads
`@MultiInstanceElement` of a caller today, model the value into the called process, for example with
a `camunda:in` on the call activity, and read it with `@TaskParam`.

### A handler asking for an item the model hands none over for ends the boot

`@MultiInstanceElement` reads the variable a multi-instance element names in
`camunda:elementVariable`. An element naming none never says what the value of a round is called,
so in version 1 the parameter received `null` and nothing said why. A cardinality-based element is
the everyday case: it iterates a number of times and walks over nothing at all.

Version 2 says it while the application boots. Per task, the deployment walks the multi-instance
elements enclosing it, asks the core which of them a `@WorkflowTask` method wants the item of, and
ends the boot where the two meet. The message names the task, the element, the attribute the model
would have to carry and the two ways out.

Either write the attribute on the element, giving it a collection to walk:

```xml
<bpmn:multiInstanceLoopCharacteristics camunda:collection="${items}" camunda:elementVariable="item"/>
```

Or drop the parameter. `@MultiInstanceIndex` and `@MultiInstanceTotal` are answered by every
multi-instance element, whatever it iterates, so a handler which only counts needs no change to its
model.

Nothing else is refused. An element which iterates a number of times still deploys where no handler
asks for its item, and so does a collection whose handler reads the index and the total only. A
method naming an element of another branch of the process is not refused either: that item never
reaches it, so the model it is deployed with is not the place to say anything about it.

### A task which has to stay open but is wired by *Expression* ends the boot

A method declaring `@TaskId` keeps its task open until the application completes it. An
*Expression* task cannot do that: the engine completes it the moment the expression returns.
Version 1 said nothing about the pairing while the application started, so it showed up once a
workflow reached the task.

Version 2 asks the core while it wires the process and ends the boot for every *Expression* task
whose method wants to keep it open. The message names the task, the process, the workflow module
and the remedy, which is to wire the task by *Delegate expression*.

It applies whichever way the method is wired to the task, by `@WorkflowTask(taskDefinition = ...)`
or by `@WorkflowTask(id = ...)`. The reverse pairing stays silent: a *Delegate expression* serves a
method without `@TaskId` just as well, because the behavior leaves the activity when the handler
returns.

### A user task without a `@WorkflowTask` method is named at boot

Version 1 said nothing about a user task which no method serves, and version 2 says one line about
it: once per BPMN process, at INFO, while the workflow module boots, and only for a process one of
your `@WorkflowService` classes claims. The line names each element, its form key or that it has
none, and the method which would serve it.

Nothing is refused and nothing changes about how such a model runs. The engine creates the user
task, a task list shows it and whoever finishes it moves the workflow on. The line exists for the
other case: a notification somebody drew into the model and never wired, which used to be
invisible until a workflow reached the task and nothing happened.

Where your user tasks are worked through Camunda's Tasklist alone, the line is the whole story and
there is nothing to do about it. The Camunda 8 adapter ends the boot over a user task a job worker
serves, and this engine has no such shape: every user task here is the engine's own.
