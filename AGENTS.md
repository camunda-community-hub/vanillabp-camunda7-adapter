# Working on camunda7-adapter

The VanillaBP adapter for the embedded Camunda 7 engine, on Spring Boot and on Quarkus.

Read [`README.md`](./README.md) first. It says what the adapter changes in the BPMN it deploys,
how it runs what it delivers, and how to build. The README is for people who work on the code. The
[wiki](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki) is for people who
use the adapter.

[`ADAPTER-AUTHORS.md`](https://github.com/vanillabp/adapter-platform-integration/blob/main/migration-adapter/ADAPTER-AUTHORS.md)
in the platform repository describes what every adapter implements. It names the two interfaces
and the calls the core expects back. It also says what an answer promises and what a wrong answer
costs. Read it before you change anything at the SPI boundary. Where this adapter does something
different, the README says so and gives the reason.

## What belongs in `UPGRADE.md`

[`UPGRADE.md`](./UPGRADE.md) describes the step from VanillaBP 1 to the 2.0 release and nothing
else. It needs an entry where a version 1 application on this BPMS behaves differently, or where
the application has to change something. A change between two snapshots of 2.0 gets no entry, no
matter how much work it was. Such an entry would make the reader follow how the release was built,
while they only want to do their own upgrade. And git keeps that history anyway.

What does not go there still has a place. The end state of a new feature goes into the wiki,
because users read the wiki. A reason that several places in this repository rely on goes into
[`DECISIONS.md`](./DECISIONS.md). Anything else goes into the commit message only.

The file is sorted by version line and then by topic. No heading has a date. The file holds what
this adapter asks of an application during the upgrade. What VanillaBP itself asks is in the
[platform's file](https://github.com/vanillabp/adapter-platform-integration/blob/main/UPGRADE.md).
For users, the wiki page
[Migrating from version 1](https://github.com/vanillabp/adapter-platform-integration/wiki/Migrating-from-version-1)
covers both files. Where the page and a file disagree, the page is right.

## The decision log is binding

[`DECISIONS.md`](./DECISIONS.md) holds the decisions several places in this repository rely on. It
is the ONLY thing the code is allowed to cite, in the plain greppable form
`see decision 7 in the repository's DECISIONS.md`, and only entries of THIS repository.

Read it before you change behaviour. An entry is not background reading, it is the reason the code
around it looks the way it does, so a change which contradicts one is wrong until the entry says
otherwise.

**A decision is changed or replaced only after asking.** Where your change would make an entry
untrue, stop and put the question to the maintainer before you write the change. If the answer is
yes, the same commit updates the log: the old entry STAYS, marked as superseded and naming the
entry which replaced it, and the new decision takes the next free number. Numbers are never reused
and never renumbered, because a citation in an older release still points at them. Never edit an
entry until its old text is gone.

Adding an entry has the same rule. A decision earns a number when several places rely on it and
copying the explanation to each of them would rot; anything smaller is a comment where it belongs,
and anything larger is documentation.

## How we write

Most people who read this repository read English as a second language, and so does the
maintainer. Long sentences, rare words and stacked nouns slow them down. Write so that
nobody has to read a sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice.
The common word instead of the rare one: `use` instead of `leverage`, `about` instead of
`regarding`, `so` instead of `consequently`, `has` instead of `possesses`. A technical term
stays a technical term, but say what it means the first time it turns up, and write an
abbreviation out once. If a sentence trips you up when you read it aloud, rewrite it.

This holds for every English text here: the README files, `DECISIONS.md`, this file, the
Javadoc and comments which explain something, and the texts of commits and pull requests. It
holds for the [wiki](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki) as
well, because the wiki clone has no `AGENTS.md` of its own.

Nothing a program reads is renamed for the sake of language. Class and method names,
configuration keys, artifact coordinates and the headlines of decision log entries stay as
they are, because code, tests and other repositories point at them.

Before and after, from this file:

> A change between two snapshots of 2.0 earns no entry, however much work it was: it would ask the
> reader to follow how the release was built instead of carrying out their own upgrade, and git
> holds that history anyway.

becomes

> A change between two snapshots of 2.0 gets no entry, no matter how much work it was. Such an
> entry would make the reader follow how the release was built, while they only want to do their
> own upgrade. And git keeps that history anyway.

The next pair is from [`UPGRADE.md`](./UPGRADE.md):

> Version 2 refuses a message which does not start the process of the `ProcessService` you call,
> with an `IllegalArgumentException` before anything is saved.

becomes

> Version 2 refuses a message which does not start the process of the `ProcessService` you call.
> It throws an `IllegalArgumentException` before anything is saved.

## Before you open a pull request

A number your branch hands out can be taken by the time you open the pull request. Another branch
was open at the same time and got there first. So check your numbers against `origin/main` and
against every open pull request, before the pull request exists.

It went wrong twice on 2026-09-13, in the Business Cockpit repository: two branches claimed one
number, which had to become 19 and 20, and two more claimed the next, which had to become 21 and
22. Both times it showed up at the merge, which is the worst moment for it. A merge happens on
GitHub, and a `see decision 21` in a Java file cannot be changed there.

The check:

```bash
bin/check-decision-numbers.sh
```

The script reports and changes nothing. By hand it is:

```bash
git fetch origin
git show origin/main:DECISIONS.md | grep -E '^### [0-9]+\. '  # the numbers already taken
gh pr list --state open
gh pr diff <n> | grep -E '^\+### [0-9]+\. '                   # for each open pull request
```

`gh pr diff` takes no path argument, so the grep does the filtering.

If your number is taken, your entry gets the next free one, and you correct every citation of it
in the code and in the documentation.

Read each citation before you change it. Not every `see decision <n>` in the branch is about your
decision. A branch can cite a number somebody else handed out long ago, and that citation stays
as it is. A search and replace over the branch turns a right reference into a wrong one.

None of this breaks the rule that a number is never renumbered. That rule is about a merged
number, which a citation in a released artifact points at. Until the pull request is merged,
nothing outside the branch has seen the number, so correcting it costs no more than the branch.

Every other running number is checked the same way. The story prompts are such a series. They are
kept outside this repository, so they are checked where they are kept.

After you renumber, run `bin/check-decision-citations.sh`. It says whether every citation of a
decision still points at an entry, also where a citation is wrapped over two lines. The *Checks*
workflow runs it on every pull request.

`bin/check-orphaned-javadoc.sh` finds a javadoc block which stands directly in front of a second
one. Javadoc drops the first block without a word, so its text appears nowhere. Run the script when
you wrote or moved a comment. If it reports a block, move the block back to the element it
describes. Do not delete it.

## What code may point at

Nothing which a later change can invalidate without anything noticing: no story or prompt number,
no issue or pull-request number, no chat transcript, no person. Those record a conversation at a
point in time. A decision entry lives next to the code and changes in the same commit as the code.
That is why code may cite it.

Where a name can carry the reason, the name is the better fix. Where it cannot, a comment says why
in its own words, complete where it stands. Only what several places have to carry becomes an
entry in the log.

Commit messages and pull-request descriptions may cite whatever they like. They are records of a
point in time themselves.
