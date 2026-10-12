# Contributing

This repository is the VanillaBP adapter for Camunda 7. It implements the adapter SPI (service
provider interface) of the
[platform integration](https://github.com/vanillabp/adapter-platform-integration). The engine runs
embedded in the application, in the application's own transaction. Business code never sees the
adapter. An application is written against
[`spi-for-java`](https://github.com/vanillabp/spi-for-java). The
[wiki](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki) describes
everything this adapter does for its users.

Where the rules are: [`README.md`](./README.md) explains how the adapter works, module by module
and behaviour by behaviour. Read it first. [`AGENTS.md`](./AGENTS.md) says how work is done here,
in the form an agent reads. [`DECISIONS.md`](./DECISIONS.md) holds the decisions several places
rely on. It is the only thing the code may cite.

## Getting started

Start from the [development workspace](https://github.com/vanillabp/development-workspace). Clone it
with `git clone --recurse-submodules`. This repository builds on `spi-for-java` and
`adapter-platform-integration`, and the workspace holds all three side by side. The [README of the
workspace](https://github.com/vanillabp/development-workspace#readme) says how to build them in
order without published snapshots, and how to open a pull request, with or without the right to push
here. It also shows how a coding agent can take a bug off your hands. The rest of this file is about
this repository.

## Building and testing

You need a JDK 21 or newer, and Maven. There is no Maven wrapper. The workflows build with the JDK
named in `.github/workflows`, currently 25. Use that one if you want to see what the pipeline sees.
The class files stay at Java 21 either way, because the property `version.java` in the root
`pom.xml` says so. Two repositories come first, in this order: `spi-for-java`, then
`adapter-platform-integration`. Build and install them in the workspace, see [Getting
started](#getting-started), or read their [published snapshots](#snapshots-on-maven-central-and-in-github-packages).
Then, here:

```bash
mvn spotless:apply
mvn install
```

Run `install`, not `install verify`. `install` already runs every phase of `verify`. Naming both
runs two lifecycles per module and reports every compiler warning twice.

Nothing in this repository needs Docker or a network. The Camunda 7 engine runs embedded, on an
in-memory database. So a full run is cheap compared to the other adapters. That is also why both
platforms test the documented features against a real engine and not against a test double: Spring
Boot in `integration-tests`, Quarkus in `quarkus/integration-tests`. A correct core does not prove
that the glue code of a platform ever calls it.

Two tools read the javadoc, and each one sees a part the other misses. The compiler checks every
class for a broken reference or broken HTML, the package private ones included. The javadoc plugin
checks what the published documentation shows, so it looks at protected and public only. One thing
below protected is shown as well: the fields a serializable class writes into its serialized form.
That is why a private field of an exception needs a comment too.

A missing comment breaks the build. Everything this repository publishes has one now, and the
plugin fails on a warning so that it stays that way. Write the sentence instead of switching the
check off. Write the sentence a reader needs: somebody who wires this repository into an
application reads it, and `@return the value` tells them nothing. A module which publishes nothing
sets `maven.javadoc.skip`, so a test module never needs comments.

The javadoc plugin cannot see an accessor which Lombok generates. The plugin reads the source, and
Lombok writes bytecode. So a published comment names a property in words and does not link a getter
which is not in the file. A published class which gets its constructor from Lombok writes that
constructor out. Otherwise the documentation shows a constructor without parameters, which does not
exist.

Neither tool sees two javadoc blocks in a row. Javadoc keeps the last block before an element and
drops the earlier ones without a word. So a comment somebody wrote and kept up to date appears
nowhere. `bin/check-orphaned-javadoc.sh` finds this case. A block it reports describes something,
usually the element next to it. Move it back there instead of deleting it.

The build also measures itself. `test-coverage-report/coverage-gate` is the last module of the
reactor. It fails below 85 percent of covered instructions per platform, while the rule is 90. See
[Test coverage](./README.md#test-coverage) for what the gate prints and why the threshold is not
the target.

## Snapshots on Maven Central and in GitHub Packages

If you clone only this repository, Maven takes `spi-for-java` and `adapter-platform-integration`
from the snapshot repository of Maven Central, `https://central.sonatype.com/repository/maven-snapshots/`.
Every push to `main` of those two repositories publishes a snapshot there. The root `pom.xml` names
that repository, and reading it needs no login and no token.

The snapshots of this adapter stay in GitHub Packages, because the namespace
`org.camunda.community.vanillabp` belongs to the Camunda Community Hub. Every push to `main`
publishes one to `https://maven.pkg.github.com/camunda-community-hub/vanillabp-camunda7-adapter`.
Reading it takes a personal access token (classic) with `read:packages` and a few entries in your
`~/.m2/settings.xml`. [Using the published
snapshots](https://github.com/vanillabp/development-workspace#using-the-published-snapshots) shows
both. The old URL `https://maven.pkg.github.com/vanillabp/camunda7-adapter` still answers, but with
an old snapshot which no longer changes.

## What a POM hands an application

A tool which only compiles our source belongs in scope `provided`. Write the scope at the
declaration in the module which uses the tool. Lombok is such a tool, and so is an annotation
processor. An application asked for a workflow engine. Every jar it did not ask for is one more
thing to ship, and one more thing to answer a CVE (security vulnerability) report about.

`<optional>true</optional>` in a `dependencyManagement` does not help. Maven copies the managed
version, scope and exclusions into a dependency, but not the optional flag. So the POM we publish
says nothing at all about that dependency. Lombok reached the runtime classpath of
every application that way, here and in the platform.

## How we write

Most people who read this repository read English as a second language, and so does the maintainer.
Long sentences, rare words and stacked nouns slow them down. Write so that nobody has to read a
sentence twice.

Short main sentences, one thought each. One subordinate clause is enough. Active voice. The common
word instead of the rare one: `use` instead of `leverage`, `about` instead of `regarding`, `so`
instead of `consequently`, `has` instead of `possesses`. A technical term stays a technical term, but say what it means the first
time it turns up, and write an abbreviation out once. If a sentence trips you up when you read it
aloud, rewrite it.

This holds for every English text here, the javadoc and comments in the main code and in the
tests, the commit message and the pull request included, and for the [wiki](https://github.com/camunda-community-hub/vanillabp-camunda7-adapter/wiki)
as well. The section `How we write` in [`AGENTS.md`](./AGENTS.md) shows a few sentences before and
after. Nothing a program reads is renamed for the sake of language: type and method names,
configuration keys and artifact coordinates stay as they are, because code in other repositories
points at them.

## What is asked before the code is written

Where a change would make an entry of [`DECISIONS.md`](./DECISIONS.md) untrue, ask before you write
it and wait for the answer. An entry is never edited away: it stays, marked as superseded and
naming its successor, and the new decision takes the next free number.

The second question is the SPI. The platform integration publishes what this adapter implements,
and three other adapters implement it as well. So a change to the SPI is discussed there, and not
worked around here. An adapter may refuse what its BPMS cannot do, with a message which names the
adapter. That is not the same as bending the contract.

## Opening a pull request

Work on a branch of your own and keep one subject per pull request. The description says what
changed and why. It may cite an issue or a conversation, because it records a moment in time. The
code does not.

Check the numbers your branch hands out before you open it. Another branch may have taken the
decision number you used while you were writing, and once a pull request is merged a
`see decision 7` in a Java file can no longer be corrected on GitHub:

```bash
bin/check-decision-numbers.sh
```

After you corrected a number, `bin/check-decision-citations.sh` says whether every citation of a
decision still points at an entry of `DECISIONS.md`. The *Checks* workflow runs it on every pull
request.

The *Publish to GitHub Packages* workflow builds and tests every pull request. It publishes nothing
from a branch. A red check is a finding about your change. When the build fails, the workflow
uploads the log as a test report. Read it and fix what it says. Do not push again just to see
whether the error goes away.

If this is your first pull request in the repository, a maintainer approves the first run of the
workflows. A pull request from a fork builds and tests like any other, because it can read the
snapshots it needs without a login. It cannot publish anything, because GitHub gives it no secrets
of this repository. [What the build of a pull request can
do](https://github.com/vanillabp/development-workspace#what-the-build-of-a-pull-request-can-do) says
more.

This repository lives in the Camunda Community Hub, which asks every contributor to sign its
[contributor license agreement](https://cla-assistant.io/camunda-community-hub/community). On your
first pull request here, the check `license/cla` asks you to sign it. [The license agreement of the
Camunda Community
Hub](https://github.com/vanillabp/development-workspace#the-license-agreement-of-the-camunda-community-hub)
says how.

`main` has a ruleset which requires two green checks before a merge. `publish` is the build of
that workflow. `orphaned-javadoc-check` is the *Checks* workflow, which runs the script named above. While one of them is red, GitHub does not offer the merge.

## License

VanillaBP is published under the [Apache License, Version 2.0](./LICENSE), and by contributing you
agree that your contribution is licensed the same way. [`NOTICE`](./NOTICE) names who holds the
copyright.
