# A compensation runs in one transaction, and the deployment says so

Decision 5 lets this adapter write `asyncBefore` and `asyncAfter` on every service-like task, which is
what gives each of them a job and therefore a transaction of its own. A compensation handler is the one
place where the engine does not follow. It starts such a handler outside the normal flow, where no job
is created, so all the handlers of a throw event run in the transaction of that event. The promise
stands everywhere else. This is the exception beside it, and the deployment names it.

Measured on 2026-10-01 against the pinned engine 7.24.0 by `Camunda7CompensationTokensTest`, with a
throw event compensating two finished service tasks. Both handlers ran in the same command context,
which is the engine's own unit of work, and one commit covered the two of them. The two activities they
compensated ran in a transaction each, so the flags do work where the engine makes a job of an
activity. And a handler which threw sent the other one back to work: the engine rolled the whole
compensation back, counted one retry off the single job and ran both handlers again on the next
attempt. Story 658 had read the jobs and the thread on 2026-09-27 and those numbers did not move; the
command context, the commit and the retry are new.

A warning and not a refusal. The model is right and no flag of this adapter changes what the engine
does, so ending the boot would stop an application which did nothing wrong. What a reader can change is
the handler, which is why the message asks for one that may run twice and for work the engine cannot
roll back to stay out of it. It names the throw event and the handlers it starts, because the cost
grows with their number: five handlers and their side effects in one transaction means the work of the
first four is only as safe as the fifth.

Making up for it inside the adapter is not what this decision answers. The flag the adapter writes at
parse time IS the seam this engine offers, and the engine ignores it there, so anything beyond that
would be VanillaBP running the compensation instead of the engine. Whether it should is a question of
its own, it has a roadmap row of its own, and this decision only says what the engine does and what
the deployment says about it.

Camunda 8 hands out a job per handler, so the same model is expected to run in a transaction per
handler there. That is read off the model reading of the Camunda 8 adapter and not measured on a
cluster, and `prompts/FINDINGS-camunda7.md` says what would settle it.
