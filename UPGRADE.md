# Upgrade notes

This list is for contributors. It says what a VanillaBP 1 application on Camunda 7 has to do,
sorted by version line. It describes the step to the release, not how the release was built. Git
holds the development history. These entries feed the
[migration guide](https://github.com/vanillabp/adapter-platform-integration/wiki/Migrating-from-version-1)
for users. The same file exists for
[VanillaBP itself](https://github.com/vanillabp/adapter-platform-integration/blob/main/UPGRADE.md)
and for the [Camunda 8 adapter](https://github.com/camunda-community-hub/vanillabp-camunda8-adapter/blob/main/UPGRADE.md).

## 2.0

### A message starts only the process of its own `ProcessService`

Version 1 correlated the message of `startWorkflowByMessage` with the engine's
`correlateStartMessage()`. So every process with a start event for that message started, no matter
which `ProcessService` you called. Version 2 refuses a message which does not start the process
of the `ProcessService` you call. It throws an `IllegalArgumentException` before anything is
saved. On Camunda 7 the correlation also names the process definition. So even a message the
check cannot judge starts no other process.

If your code starts a process through the `ProcessService` of another process, call the
`ProcessService` of the process the message is meant for instead. Once the correlation names a
definition, Camunda 7 compares the message name exactly as the model writes it. So a message start
event whose name is an expression, such as `${orderMessage}`, can no longer be started by a
message. Give it a plain name.

### A shared value Camunda 7 has no variable type for needs a serialization format

Version 1 answered the expressions of a model from the live workflow aggregate. So
`${order.customer.name}` navigated a Java object, and there was nothing to configure. Version 2
writes what the aggregate shares as Camunda process variables. If the engine has no variable type
for a value, the value becomes an object variable. The engine stores it in the serialization
format it was told to use. This concerns nested values (an object or a collection) and the numbers
this engine cannot store as themselves: a `BigDecimal`, a `BigInteger` or a `Float`.

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
[camunda-xstream](https://github.com/RasPelikan/camunda-xstream). The application has to bring the
dependency which provides it. VanillaBP passes the value set for the adapter to the engine's
`defaultSerializationFormat` and to every variable it writes. A workflow module or a single
workflow may set its own value
(`vanillabp.workflow-modules.<module>.adapters.<id>.serialization-format`,
`vanillabp.workflow-modules.<module>.workflows.<workflow>.adapters.<id>.serialization-format`).

If you configure nothing, the engine's own default applies. Without a dataformat that is Java
serialization. Cockpit then shows a base64 blob instead of the data, so nobody who operates the
workflows can read what a decision was based on. Also, the engine's database then holds serialized
instances of the application's own classes. So `${order.customer.name}` only keeps working as long
as the class in the database still matches the class on the classpath. The adapter warns about this
once per JVM when it writes such a value. The warning names the property to set and the dataformat
to add.

A format can lose information too, and where it does, the boot tells you. JSON has only one number
type. So a `BigDecimal` of `120.50` comes back as `120.5`, and a nested one comes back as a
`Double`. While your application starts, the adapter writes a sample of the type through the
engine's own serializer. It warns about every attribute your models read whose value would not
come back the way it went in. The warning names the attribute, the format and what the engine
answered.

None of this affects an application whose aggregates share only values this engine stores as
themselves. A short, an integer, a long, a double, a boolean, a string, a date and bytes are
scalar variables, and `${amount > 1000}` compares numbers as before. A `Character` is written as a
string, and EL treats `'A'` and `"A"` the same when it compares it. A number the engine has no type
for keeps its class. Version 1 widened it to a double. `${total > 100}` compares numbers either
way, and `${total}` shows the value your code holds, not one that somebody converted on the way in.

The [page about a shared value](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/What-becomes-of-a-shared-value)
of the wiki tells the same in more detail. Decision 9 in the repository's
[`DECISIONS.md`](./DECISIONS.md) says why such a value travels in the engine's own format and not
as a JSON string. Decision 16 says why a number keeps its class.

### What the startup check tells you about your expressions, and what it cannot

Version 1 answered every BPMN expression from the live workflow aggregate. So nothing a model read
could be missing. Version 2 writes only what the aggregate SHARES. An expression which reads
something unshared reads `null` instead. On Camunda 7 a null often goes unnoticed. A gateway with a
default flow takes that flow. A conditional event answers false and keeps waiting, with no incident
and no log line. A completion condition which is never true lets a multi-instance element end as
if it had done its work.

So the adapter lists such expressions while your application boots, with one WARN each. Each WARN
names the element, the expression, the part of the path where it breaks, what this engine will do
with the null and how to fix it. Nothing fails, because an expression the check gets wrong must not
stop your application from starting. Treat the list as your migration backlog and work through it
before you go live. The runtime will not remind you.

Know where the list ends. The check reads the DECLARED types along an expression's path. Where
they cannot decide, it says nothing: a `Map`, an interface, an abstract type, or a collection which
does not say what its elements are. It does not judge method calls either. So
`${order.getTotal()}` and `${order.status.name()}` are not in the list, although both stop working
on version 2: the flattened value is a map and a text, and neither has those methods. Those two
cases are LOUD at runtime. They raise an incident which names the expression and the class it
looked at, so they are left to the engine. The check is for what stays silent at runtime.

The list also does not cover the input expressions of a business rule task. It says nothing about
Camunda 8 or the Process-Engine-API. Those adapters flatten values the same way, so the same
expressions miss the same keys there. But neither adapter gets a parsed model of this kind, and
FEEL is not JUEL. The gap is known and not closed here.

The [configuration page](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Configuration#migrating-from-an-adapter-which-read-the-aggregate-live)
of the wiki tells the same in more detail.

### A renamed BPMN process no longer needs its old model deployed

An application may declare a BPMN process id under which it deploys nothing
(`@WorkflowService(secondaryBpmnProcesses = ...)`). This is how a renamed process stays served. On
version 1 that declaration reached the handler registry, but not the engine. This adapter wired the
tasks of the models it deployed. A workflow still running under the old id reached its next task,
found nothing wired to it and ended in an incident. The way around this was to keep the old BPMN
file next to the new one until those workflows had ended.

Version 2 wires the old id as well. When a workflow module starts processing, the adapter asks the
core which ids the module declares without a model. For each of these ids it reads the model of
every version the engine still holds, from the engine's own repository. Those workflows then reach
the `@WorkflowTask` methods of the current application. This is what the declaration always
promised.

There is nothing to configure and nothing to remove. An application which keeps the old model
deployed sees no change, because then no id is declared without a model. An application which
deleted the old file gets one log line per declared id on every start. The line says how many
versions were wired. This costs no extra query: the check for old process versions runs that query
anyway.

Two things are worth knowing while the declaration stays in place. First, the engine must still
hold the old definitions. It does as long as workflows run on them. Second, the check for old
process versions reports a version whose tasks the application dropped, with the number of
workflows it affects. This works exactly as for the older versions of any process. The
[recipe for a rename](https://github.com/vanillabp/adapter-platform-integration/wiki/Renaming-a-BPMN-process)
is on the platform's wiki.

Version 2 also checks the declaration itself. The engine keeps firing the old model's timer and
keeps matching its signal subscription. So the adapter reads the start events of the versions it
holds under the old id. It tells you about any `@WorkflowStartedByBpms` method for which none of
them has a start event. A `@WorkflowEnded` method kept for the old id is checked in the same place.
Both are warnings, and neither stops a start, because they read models nobody can change any more.

### What the outbox costs where version 1 used your transaction

Camunda 7 runs inside your application. So version 1 could do its engine work in the caller's
transaction, and for most operations it did. Version 2 schedules every operation which moves a
workflow forward through the phase-two outbox. This is decision 2 of this repository. The cost is
described once for every adapter, in the platform's
[upgrade notes](https://github.com/vanillabp/adapter-platform-integration/blob/main/UPGRADE.md#what-the-upgrade-costs-under-load).
What is special here is which of your calls pay it.

A workflow start costs nothing new. Version 1 wrote an engine job inside your transaction and let
the job executor run it. So the start already cost a row in your transaction and one more
transaction after it. Version 2 writes an outbox entry where that job was. The row goes into a
different table, and the call leaves on a different thread.

The cost shows up when you answer a message, and when you complete or cancel a task. Version
1 called the engine while your transaction was open, so your commit covered the engine's work.
Version 2 writes an entry for it and calls the engine after your commit, in a separate
transaction.

The job executor stays as it was. Service tasks are still asynchronous before and after, as
version 1 made them. So your handlers still run on job executor threads, and the engine still uses
the same number of transactions per task. The dispatch threads of the outbox are new and run next
to it. Both use the database your workflow aggregates live in.

If your application had raised `camunda.bpm.job-execution.max-pool-size`, look at
`vanillabp.outbox.dispatch-threads` as well. Camunda's Spring Boot starter starts the job executor
with three threads and grows it to ten. That pool used to carry VanillaBP's starts along with the
engine's own work. Now four dispatch threads carry them.

### The wakeup job executor has a new key and is no longer experimental

Version 1 had an experimental job executor behind `camunda.bpm.job-execution.wakeup`. That key is
gone. It belonged to Camunda's Spring Boot starter, not to the adapter, and a migration setup runs
two Camunda 7 adapter ids which may need different values. The same feature is now configured per
adapter id, and it is supported:

```yaml
vanillabp:
  adapters:
    c7:
      sleep-until-something-is-due: true
```

It is still off by default, so an application which does nothing keeps the engine's own timing. An
application which had set the version 1 key does have to act. Without the new key, its engine polls
every 5 to 60 seconds again.

What the feature does has changed in three ways. Read all three before you switch it on.

The feature is the waiting, not the waking. Version 1's README promised an immediate wake-up
after a commit which created a job. But the engine has always done that by itself for a job which
is due now. What this feature adds is the case the engine does not cover: a job which is due LATER.
That only matters once the executor stops polling.

Every commit wakes the executor now, not just four methods of one class. Version 1 published
a Spring event from `Camunda7ProcessService`. So a plain save of a workflow aggregate, a signal, or
a continuation the engine wrote for itself woke nothing. The servlet filter which version 1 put on
the Camunda webapp and on `/engine-rest` is gone as well. Those calls are engine commands like any
other and are covered without a filter. The waking needs no configuration, and you no longer need
the Spring `TaskScheduler` bean version 1 asked for.

Two engine settings change with the key. Jobs are acquired by due date. Version 1's README claimed
this, but its code set neither flag. This order needs a database index on the due date of
`ACT_RU_JOB`, and only the operator can create it. Also, the engine's metrics reporter stops
writing, because otherwise it would wake a waiting engine four times an hour. Set
`db-metrics-reporting: true` to keep it.

There is also no Quarkus caveat any more. Version 1 had no Quarkus artifact at all, so the feature
was Spring-only by accident. Now it works on both platforms.

The [README section](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/blob/main/README.md#an-idle-engine-lets-go-of-its-database)
and the [configuration page](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Configuration#letting-an-idle-engine-go-quiet)
of the wiki have the details.

### One tenant for every workflow module refuses two modules sharing a BPMN process id

Version 1 deployed each workflow module into a tenant named after the module. `tenant-id` let you
choose the tenant's name yourself. An application which names one tenant for the whole adapter puts
every workflow module into that tenant. If two modules declare the same BPMN process id, both reach
the engine under one process definition key. Camunda 7 keeps one definition under that key and
loses the other. So one of the two modules runs on a model nobody deployed. Version 1 deployed it
and said nothing.

Such an application boots today and will not boot after the upgrade. The deployment of the SECOND
of the two modules ends the boot, while the first one is already in the engine. The message names
both workflow modules, both process ids, the key they share and the way out. Only this one
configuration is affected: one `tenant-id` for the whole adapter, the mode `by-adapter`, and the
same BPMN process id in two workflow modules.

There are three ways out. The first one leaves every other module where it is:

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

Setting the name per workflow module is new. Before, you could only set it for the whole adapter.
The second way is `name-clash-avoidance: use-prefix`. It drops the tenant and puts the workflow
module id in front of the identifiers instead. The third way is to rename one of the two BPMN
processes. Which one fits depends on what the engine's running workflows are deployed under. So
read [what the mode changes](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Configuration#keeping-workflow-modules-apart)
before you change an application which boots.

An application which configures no `tenant-id` at all is not affected. Every workflow module has a
tenant of its own, and that is why two of them can use one process id in the first place.

### A listener served by a `@WorkflowTask` method has to be allowed now

Version 1 served a `camunda:executionListener` with a `@WorkflowTask` method, but documented this
nowhere. It did so in one case only: on an END EVENT or an INTERMEDIATE THROW EVENT, with the
listener written as `camunda:expression` or `camunda:delegateExpression`. Any other listener on
those two elements ended the boot with `Unsupported listeners at ...`. A listener anywhere else in
the model was left to the engine alone. The task definition was the expression text without its
wrapper. So `${archiveOrder}` was served by `@WorkflowTask(taskDefinition = "archiveOrder")`. An
expression like `${bean.doIt()}` became the literal task definition `bean.doIt()`, so only
`@WorkflowTask(id = "<element id>")` could serve it.

If your models carry such a listener, and one of your `@WorkflowTask` methods names its expression,
this is the entry to act on. Version 2 does not serve such a listener unless you allow it. A model
which carries one ends the boot. The message names the elements, the key and what allowing it
costs. You can allow it per adapter, per workflow module or per workflow. The most specific
configured value wins, both for `true` and for `false`:

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

The boot failure is the good case, and it is on purpose. Without the key there is no task and no
worker for the listener, so the engine evaluates the expression itself. A workflow which reaches the
element then either fails on a name nothing resolves, or runs a method the application never meant
for that element. Both happen at runtime and in production, not during a boot somebody is watching.
The Camunda 8 adapter has the same key for the same reason. There, a workflow would get stuck at
the listener's job with nothing in the log.

Read what the key costs before you set it. A listener lets an application in at a moment the BPMS
controls. Every BPMS defines that moment differently, so the model is no longer portable. Another
BPMS has no listener at this element, and a migration of the model gets stuck at the method which
serves it. Every boot of a workflow module whose listeners are served writes a framed WARN about
this. It names each listener and the way back, and no key turns it off. Where you can, move what
the listener does into a task of the model with a `@WorkflowTask` method behind it. This is the way
back the report names.

Four more things change besides the key.

Any element may now carry a served listener, not only an end event or an intermediate throw event.
That opens the door wider than version 1 did, and the key keeps it shut by default.

`@TaskEvent` now reports two moments. On version 1 it received `CREATED` for every listener event.
That did not tell you whether the listener fired on `start`, on `end` or on `take`. Now the event is
part of the wiring: one method serves one event of one element. So the parameter receives `CREATED`
whenever the modelled listener fires. It receives `CANCELED` when the element the listener sits on
is canceled. VanillaBP reports this through the engine's end listener. A method without the
parameter subscribes only to `CREATED` and hears no cancellation. A listener you modelled on `end`
gets no separate report. The engine fires an END execution listener on a cancellation too, so such
a method already hears that moment.

A `@TaskId` parameter is refused while the process is wired. A listener is notified and then done.
So such a task can never stay open, and the id would complete nothing. Version 1 accepted the
method, and the workflow went on without it.

If two listeners of one element use ONE expression, the boot ends and names both. Version 1 ran
one of them, and a `findFirst` decided which one. So the model promised something it could not
deliver. Give every listener of an element its own expression, and write one method per
expression.

Two things got easier. A `camunda:class` or `camunda:script` listener on an end event or an
intermediate throw event no longer ends the boot. The engine runs it itself, there is no expression
which names a task definition, and this version ignores it completely.

And a listener whose expression names something other than a `@WorkflowTask` method stays exactly
as it is. With Spring or CDI, a delegate expression is resolved against your application context.
So `${auditTheOrder}`, naming one of your own beans which implements Camunda's
`ExecutionListener`, is an ordinary Camunda 7 model. VanillaBP does not read it, does not refuse it
and does not mention it. The key above is only about a listener whose expression names a method
you wrote. That is exactly the version 1 case.

The [README section](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/blob/main/README.md#listeners-somebody-modelled)
and the [configuration page](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki/Configuration#listeners-somebody-modelled)
of the wiki have the details.

### A called process is told about the iteration of its caller, unless it has a workflow aggregate of its own

Version 1 reported ONE multi-instance level, the innermost one. For the most common way to split a
process into parts it reported none at all. Take a plain call activity inside a multi-instance
subprocess, with the called process in a BPMN file of its own. There, the task ended with
`No multi-instance context found for element '...' or its parents!`. The same models worked if both
processes were written into ONE file, because the lookup then found the enclosing element by
chance. Version 2 reports every level of every caller, outermost first, no matter how the files are
split.

A handler which used to fail with that message now runs, and you do not have to change anything
for it.

A handler may now name an element which encloses its own element. Take
`@MultiInstanceElement("OrderBatch")` on a task inside `OrderBatch`. That used to be the only name
the map held. For an outer element the answer was `null`, or a `NullPointerException` inside the
framework. Both now get an answer. A `MultiInstanceElementResolver` finally sees the map sorted from
outermost to innermost, as its javadoc always promised.

A process with a workflow aggregate of ITS OWN is told nothing about the iteration which called it.
Version 1 treated every call activity as part of the same process and reported across it. Version 2
asks whether the calling and the called process work on the same workflow aggregate. The business
key follows the same rule. Where they do not share one, it reports nothing. If a handler of such a
process reads `@MultiInstanceElement` of a caller today, model the value into the called process,
for example with a `camunda:in` on the call activity. Then read it with `@TaskParam`.

### A handler asking for an item the model hands none over for ends the boot

`@MultiInstanceElement` reads the variable which a multi-instance element names in
`camunda:elementVariable`. An element which names none never says what the value of a round is
called. So in version 1 the parameter received `null`, and nothing said why. A cardinality-based
element is the everyday case: it runs a fixed number of times and walks over no collection at all.

Version 2 tells you while the application boots. For each task, the deployment goes through the
multi-instance elements around it. It asks the core which of them a `@WorkflowTask` method wants
the item of. Where both are true, it ends the boot. The message names the task, the element, the
attribute the model would need and the two ways out.

Either write the attribute on the element and give it a collection to walk over:

```xml
<bpmn:multiInstanceLoopCharacteristics camunda:collection="${items}" camunda:elementVariable="item"/>
```

Or drop the parameter. Every multi-instance element answers `@MultiInstanceIndex` and
`@MultiInstanceTotal`, whatever it iterates. So a handler which only counts needs no change to its
model.

Nothing else is refused. An element which runs a fixed number of times still deploys if no handler
asks for its item. The same holds for a collection whose handler reads only the index and the
total. A method which names an element of another branch of the process is not refused either.
That item never reaches it, so the model it is deployed with is not the place to complain about it.

### A standard loop in your model ends the boot

Camunda 7 does not run a standard loop. Take an activity with `standardLoopCharacteristics`, drawn
to repeat while a condition holds. It deploys without a word, runs once, and the workflow moves on.
Version 1 deployed such a model the same way. So a model which relies on the loop has always run
the activity only once.

Version 2 refuses the model while it deploys it, if one of your `@WorkflowService` classes
claims the process. The boot ends there. The message names the activity, the BPMN process and
the workflow module. A model which deployed in version 1 no longer deploys, and this is on
purpose: the upgrade is where you learn that the loop never ran. A process nobody claims is not
checked, because the model belongs to somebody else.

Change the model to one of the two forms which really repeat an activity. Either draw a loop in the
sequence flow, with a gateway after the activity which leads back to it while the condition holds.
Or make the activity a multi-instance element. A handler then reads its round with
`@MultiInstanceElement`, `@MultiInstanceIndex` and `@MultiInstanceTotal`.

A version the engine already holds is not refused, because nobody can change it any more. If
workflows still run on it, the start logs a WARN. It names the version, the activity and how many
workflows run on it.

### A task which has to stay open but is wired by *Expression* ends the boot

A method which declares `@TaskId` keeps its task open until the application completes it. An
*Expression* task cannot do that: the engine completes it as soon as the expression returns.
Version 1 said nothing about this pairing while the application started. So the problem only showed up
when a workflow reached the task.

Version 2 asks the core while it wires the process. It ends the boot for every *Expression* task
whose method wants to keep it open. The message names the task, the process, the workflow module
and the fix: wire the task by *Delegate expression*.

This applies however the method is wired to the task, by `@WorkflowTask(taskDefinition = ...)`
or by `@WorkflowTask(id = ...)`. The opposite pairing is fine: a *Delegate expression* also serves
a method without `@TaskId`, because the behavior leaves the activity when the handler returns.

### A bean or a shared attribute named like a task definition ends the boot

The engine resolves `${chargeCard}` by asking in this order: its process variables, on Spring
Boot the beans of the application context, and VanillaBP last. Version 1 asked in the same
order. So a Spring bean named `chargeCard` got the expression, and the `@WorkflowTask` method
for the task definition `chargeCard` never ran. Nothing said so.

Version 2 ends the boot in that case. Such an application ran in version 1 and does not start
any more. This is on purpose: the method never ran in version 1 either.

Version 2 also ends the boot where the workflow aggregate has an attribute with the name of a
task definition and shares it with the engine. This case is new. Version 2 writes every shared
attribute as a process variable, and the variable would win against the task. In version 1 the
task won, because the adapter read attributes itself and asked for the task first.

The message names the task definition, the BPMN process, the element and the workflow module. It
also names the bean and its class, or the attribute. There are three ways out. Rename the task
definition in the BPMN and in the `@WorkflowTask` method. Rename the bean or the attribute. Or
keep the attribute away from the engine with `@NoSyncWithBPMS`.

The check reads only what is known at the start. A process variable which the model or your code
creates while a workflow runs, for example by an output mapping, is not checked. Do not give it
the name of a task definition.

### A task somebody else serves can say so

Version 1 ended the boot over a user task which no method serves. Its parse listener wired every
user task with `allowNoMethodFound=false`. Version 2 does the same for every task of a process
which one of your `@WorkflowService` classes claims. What is new is a way out, for a user task you
leave to a task list on purpose:

```properties
vanillabp.workflow-modules.<module>.workflows.<process>.tasks.<element-id>.implemented-externally=true
```

You may also name the task by its form key. The same line also lets an external task
(`camunda:topic`) through, for a worker you run next to the application. Otherwise this adapter
refuses such a task. A task which has a method AND this line ends the boot, because both would
answer it. A line for a whole workflow covers only the tasks without a method.

### A process none of your classes claims, and a process somebody else deployed

Version 1 ended the boot over a BPMN process which no `@WorkflowService` class declared. Its parse
listener asked for a workflow service for every process the engine parsed. Version 2 does the same
for a process your workflow module deploys. But it offers a way out for a process somebody else
serves:

```properties
vanillabp.workflow-modules.<module>.workflows.<process>.implemented-externally=true
```

Such a process is deployed with its file and left as you modelled it: no transaction flag, no
listener, no check.

**A process somebody else deploys into the same engine runs as modelled now.** The parse listener
of version 1 ran for every model the engine parsed. That included a model which another application
or a script deployed into the same database. It set `asyncBefore` and `asyncAfter` on the model's
service-like tasks and `asyncAfter` on its user and receive tasks. It also removed a modelled
`asyncBefore` from its user tasks. Then it asked for a workflow service for that process, found
none and failed the parse. So in practice such a process did not run on an engine which version 1
shared. Version 2 does not touch it at all. The flags it carries are the ones its modeller set, and
its transactions end where that model says. If such a process runs on your engine and relies on the
transaction boundaries version 1 added to it, model them yourself.
