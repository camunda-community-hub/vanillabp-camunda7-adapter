# A decision named by an expression gets the prefix in front of the expression

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
