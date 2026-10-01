# A called process named by an expression is asked about while it runs

Camunda 7 hands a called process no business key, and the business key is where this adapter keeps
the workflow aggregate's id. Decision 5 closes that gap in the model: the deployment writes
`camunda:in businessKey="#{execution.processBusinessKey}"` onto a call activity whose called
process works on the aggregate of its caller, and nowhere else, because a process with an
aggregate of its own must not be handed the caller's identity.

A call activity which names the process to call in an expression has nothing for that answer to be
written onto. The model does not say which process will be called, so the deployment cannot tell
the two cases apart, and until story 816 such a call activity was simply left alone. Measured
against the engine, that model did not run. The called process reached the application with no
name. The core read that as a start past VanillaBP and refused it with "no
`@WorkflowStartedByBpms` method builds a workflow aggregate for it", which is the wrong advice:
writing that method would build a second workflow aggregate and the called process would then run
beside its caller's business case instead of in it. The outbox retried the refusal fifty times and
the workflow never moved. The same model runs on Camunda 8, where the
aggregate's id is an ordinary process variable the cluster copies into the called instance, so one
model behaved differently on two engines.

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

Decision 22 says that nobody can be asked about a shared workflow aggregate while a workflow runs.
That holds where it was written, for the multi-instance walk: `Camunda7MultiInstances` is reached
from an execution and has no core at hand, which is why the answer for a statically named call
activity travels in the deployed model. The start listener is built with the core and can ask. The
walk therefore still ends at a call activity named by an expression while the business key now
crosses it, which is a difference between the two mechanisms and not between the two engines
any more.

`Camunda7CallByExpressionIT` runs the same called process reached both ways against the engine, and
`Camunda7CalledProcessStartTest` holds the four cases the listener tells apart.
