# A user task nothing serves is named in a claimed process, and nothing is refused

A user task of this engine runs without a `@WorkflowTask` method. The engine creates the task, it
stands in a task list, somebody finishes it and the workflow moves on. That is why the core hands a
user task over as an OPTIONAL spec, and `validateTaskWiring` filters those out before it asks for a
method. Until this story nobody said a word about it, so an application which drew a notification
into its model and forgot the method found out in production, if at all.

From now on the deployment names such a task. Once per BPMN process, at INFO, while the model is
wired, and only for a process a `@WorkflowService` class of this application claims. The message
names each element, the name the modeller wrote on it, its form key or that it carries none, and the
method which would serve it. It ends by saying that a model whose user tasks are worked through a
task list alone needs no change.

## Why this is not the refusal Camunda 8 has

Decision 53 of the Camunda 8 adapter refuses a user task a job worker serves in a claimed process.
The reason it gives is what the shape costs: the cluster hands out a job, nothing fetches it and the
workflow stands at the element with no incident and nothing in any log. Camunda 7 has no such shape.
Every user task here is the engine's own, and the application is the only thing which can be
missing.

So the rule is the same where the two can be the same, and it stops where the engine stops. What is
taken over is the split: a process this application claims is a process it stands in for, and a
process nobody claims is somebody else's model which reached this engine because of the file it sits
in. What is not taken over is the level. A refusal would end the boot of an application whose model
is right, which would be stricter than the core, whose own field says a handler is optional. A WARN
would be the same claim in a quieter voice, on every boot, for a model nobody has to change. The
third option, a report which only speaks where the process is claimed, says the one thing which may
be news without asking anybody to act on it.

## What was rejected

Refusing it, the way Camunda 8 refuses its own case, would refuse the plain Camunda 7 model of
version 1 and of every application which works its user tasks through Camunda's Tasklist. There is
nothing to fix in such a model, and a flag to switch the refusal off would be a flag for the normal
case.

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
report in the core would change the Camunda 8 adapter's boot output as well, which this story does
not touch.

## What this leaves open

The Camunda 8 adapter stays silent about the same case for a Camunda-managed user task whose external
form reference no method names. Three adapters then say two different things about one situation.
Closing that is either one more story for that adapter or the core-side report above, and whoever
takes it should start from this entry.

`Camunda7UnservedUserTasksTest` holds the message, the element without a form key, both keys a
method may be wired by, the silence about an unclaimed process and that the line is an INFO.
