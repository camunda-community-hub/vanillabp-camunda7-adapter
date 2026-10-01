# The multi-instance chain crosses a call activity named by an expression

Decision 22 says that the answer about a shared workflow aggregate travels in the deployed model,
and it ends with this sentence:

> And a call activity which names the process to call in an expression carries no note, because
> nobody could be asked before it runs, so the walk ends there the way it ends at a foreign
> aggregate.

That was true when it was written. Story 816 made the business key cross such a call activity, so
the called process reaches the workflow aggregate of its caller, and it did that by asking the core
while the workflow runs: the deployment service hands the one answer to `Camunda7TaskRegistry`,
where the start listener of the called process reads it. The walk could have asked the same way and
did not, so one model reported the iteration of its caller on Camunda 8 and reported none here.
`Camunda7MultiInstanceByExpressionIT` measured that before anything was changed. In one model and
one run, the four calls of a call activity named by an expression reported `nothing` and the one
call of a call activity named by its id reported its level.

Story 875 closes it. The walk asks the core through the same registry, and it asks only where the
deployment could not answer. Where the model spells the called process out, the note written while
that model was deployed stands and nobody asks again. The second half of decision 22 therefore
holds unchanged: a workflow standing in an older version of a process still gets the answer that
version was deployed with, whatever the declarations of the application say today. Both mechanisms
read one method for it, `Camunda7CallActivities.continuesTheCallersWorkflowAggregate` for a pair of
running executions, so the business key and the iteration cannot give one pair of processes two
answers.

What this needs from you is the wording of decision 22, because an existing entry is not changed
without you. Either the sentence quoted above is replaced, which leaves one paragraph saying where
each answer comes from, or it stays and a paragraph behind it says that story 875 took the one case
out of it. The sentence is the same either way:

> A call activity which names the process to call in an expression carries no note, because the
> model does not say which process will be called. The core is asked for it while the workflow
> runs, through the `Camunda7TaskRegistry` the deployment service hands that one answer to, which
> is where the start listener of a called process asks the same question. It is asked ONLY there.
> Where the model spells the called process out, the answer of the deployment stands, and a
> workflow standing in an older version keeps the answer that version was deployed with.

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
registry at hand through `Camunda7EngineFacts`. Passing it there is one argument in one call and it
belongs to that repository, so it is written down in `prompts/FINDINGS-camunda7.md`.
