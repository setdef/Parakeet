# Session: hand-test fixes, and 0.12.1

A hand test of 0.13.0 in RustRover 2026.2.4 (macOS, Inko 0.21.1, Cuttlebone
0.11.0 downloaded by Parakeet) found two bugs: an "IDE error occurred" on
every project open, and a broken `cuttlebone` on PATH blocking the download
offer. This session fixes both, checks the plugin for other calls made from
the wrong thread, adds tests that open real-world Inko projects (which found
three more bugs), and prepares 0.12.1 on `release/0.12`. Nothing is tagged,
pushed or published.

This work is requested, not reviewed.

## Commits, oldest first

On `main`:

| Commit | What |
|---|---|
| 5eec688 | Bug 1: report project-wide errors off the EDT |
| 8996434 | Mark Inko files with errors as problem files (found by bug 1's test) |
| 55e17f9 | Bug 2: skip a `cuttlebone` on PATH that isn't a working Cuttlebone |
| c02265f | Let a local hide the module it's named like (found by the real-world tests) |
| 8554768 | Document what only the tests import (found by the real-world tests) |
| dffac79 | Import same-named modules under names of their own (found by the real-world tests, partly a regression from 8554768) |
| 30a959e | The real-world projects' tests, in `check.sh --full` |
| 388aa67 | 0.12.1's changelog entry, below Unreleased |
| 5fa548c | The real-world tests' skip messages without color codes |
| db49a7d | This report, and README's link to it |
| 5a33409 | A flaky test's race, fixed ([below](#a-flaky-test-fixed)) |
| (this commit) | This report, updated for 5a33409 |

On `release/0.12` (in the `Parakeet-release-0.12` worktree, from v0.12.0, 94ffec0):

| Commit | What |
|---|---|
| 0ff2c73 | 5eec688, cherry-picked |
| 61af9f1 | 8996434, cherry-picked (its changelog hunk left out: it's in the next commit) |
| fbc1ddd | Prepare 0.12.1: version, README's "requested" line, changelog entry, as "Prepare 0.12.0" did |

Every commit went through the pre-commit hook (`scripts/check.sh` on what
was staged), with `CUTTLEBONE` set from 8996434 on, so Cuttlebone's
integration tests and the typing replay ran too. (5eec688's check skipped
them; see [the tools](#the-tools).) Each fix's test was run and seen to
fail before the fix, on `release/0.12` too, by putting the old code back
for one run.

## The tools

- `inko 0.21.1`, from ivm.
- `cuttlebone 0.11.0 (Inko 0.21.1)`: the one Parakeet downloaded into
  JetBrains' shared data directory (`parakeet/cuttlebone/cuttlebone-0.11.0-inko-0.21.1/`),
  passed to the checks as `CUTTLEBONE`. The one in Cargo's `bin`
  directory had been moved aside as `cuttlebone.aside`, and I left it
  there, so there was no `cuttlebone` on PATH.
- The real-world projects, copied to a scratch directory and the tests'
  own temporary directories. I didn't write to the clones, to the
  Cuttlebone checkout, or to Inko's.

## Bug 1: "IDE error occurred" on every project open

**Cause.** `InkoProjectCheck.schedule` ran the whole project's `inko check`
on `Dispatchers.IO`, then switched to the EDT to call `report`, which calls
`WolfTheProblemSolver.reportProblemsFromExternalSource` and
`clearProblemsFromExternalSource`. In 2026.2's bytecode, both start with
`ThreadingAssertions.assertBackgroundThread()`. 2025.3 and 2025.2 (the
oldest IDEs that `main` and `release/0.12` support) check the same thing
with `assertIsNonDispatchThread()`. So 0.11.0 through 0.13.0 log the error
in every supported IDE. Each method takes its own read lock (through
`ReadAction.computeBlocking`), so the caller needs none.

**Test.** `InkoProjectCheckTest.testScheduledChecksReportToTheRealProblemSolverOffTheEdt`.
`schedule` still returns early in unit test mode, so saves in other tests
don't leave checks running. Its background work is now `launch(root,
immediately)`, which returns the `Job`, and the test calls it directly. Light
tests replace the problem solver with `MockWolfTheProblemSolver`, which does
nothing, so the test gives it the real `WolfTheProblemSolverImpl` as a
delegate (`createTestInstance`). It then waits for the job while
dispatching EDT events. Without the fix, the real service threw the hand
test's exception from `InkoProjectCheck.report`, and the files weren't
marked.

**Fix.** `report` runs on the IO thread the check ran on, after
`ensureActive()`, so a cancelled check doesn't report. A lock keeps two
reports from overlapping, which the EDT used to prevent.
`refreshAndFindFileByNioFile` runs outside the lock, since a synchronous
refresh can wait for the EDT.

### What the real service showed next: nothing was ever marked

With the threading fixed, the test still failed: `isProblemFile` was false.
`reportProblemsFromExternalSource` returns right away for a file that no
`problemFileHighlightFilter` extension accepts, and Parakeet registered
none. So since 0.11.0, the project-wide check has never marked a file in
the Project view or the Problems tool window. Only Inspect Code, which
doesn't go through this service, showed its results. `InkoProblemFileFilter`
accepts the project's own Inko files: Inko file type, in project content,
so not `dep/` or `build/`.

That filter also makes the service run IntelliJ's general highlighting pass
(parser errors and annotators, not `inko check`'s external annotator) on
changed Inko files that aren't open, as it does for Java and Kotlin. For
Inko that pass is cheap. I haven't measured it in a real IDE.

## Other threading problems: none found

I looked for the same pattern: work that unit test mode makes synchronous or
skips, so the threads it runs on in the IDE are never tested.

| Where | What tests skip | Finding |
|---|---|---|
| `InkoProjectCheck.schedule`, `InkoProjectCheckOnStartup` | the background check | bug 1 |
| `InkoLibraries.refresh` | tests on the EDT update synchronously; the IDE updates on IO, then `invokeAndWait`s a write action | ran it from a pooled thread in a throwaway test: it worked, and logged nothing |
| `InkoProjectCheckOnStartup`, `InkoLibraries` | `InkoProjectRoots.find` runs in a plain (not smart) read action, unlike `InkoIndexOnStartup`'s `smartReadAction` | ran it in dumb mode (`DumbModeTestUtils`): `FilenameIndex` answers there in 2026.2, so no `IndexNotReadyException` |
| `InkoDocIndex` | rebuilds are synchronous in tests | the background rebuild touches only the file system and `inko`, not IDE models |
| Dialogs and hints skipped in tests (Inline Method, Implement/Generate, Go to Super, paste imports, the accessor rename dialog) | the UI | shown from EDT handlers, as IntelliJ expects |
| `CuttleboneDownloads.lookUp`, downloads, the precheck, `CuttleboneQueries` | nothing | tests run them on real pooled threads |
| `InkoRunConfigurationEditor`'s targets | nothing | fetched on a pooled thread, Swing updated through `invokeLater` |

The real-world tests (below) also run the project-wide check against the
real problem solver, and refresh the libraries from a background thread, in
nine real projects, while recording every logged error.

## Bug 2: a broken `cuttlebone` on PATH blocked the download offer

**Cause.** The precheck took the first `cuttlebone` on PATH (or in Cargo's
`bin` directory) as Cuttlebone, and when its `--version` failed, reported
`Refused`, which isn't the "not found" state that leads to the download
offer.

**Tests**, in `CuttleboneDownloadOfferTest`:

- `testACuttleboneOnPathThatDoesntRunIsSkippedForTheDownload`: a script that
  prints `mise ERROR cuttlebone is not a valid shim` and exits 1, as on PATH.
  The notification offers the download and says nothing about refusing.
  After the click, the download runs, and the "In use" line names it and
  the skipped shim, with mise's message.
- `testACuttleboneOnPathThatDoesntSayItsVersionIsSkipped`: one that prints
  `cuttlefish 2.0` and exits 0. The download is used.
- `testABrokenCuttleboneOnPathLeavesTheOneInCargosBinDirectory`: a broken
  one on PATH, and a working one in Cargo's `bin`, which is used.
- `testACuttleboneOnPathForAnotherInkoIsStillRefused`: a real (fake)
  Cuttlebone for Inko 0.20.0 is still refused, with no download offered and
  nothing skipped. This one passed before the fix too, as it should.

The first three failed before the fix, the first with the hand test's
message.

**Fix.** `CuttleboneLocations.installed()` lists every `cuttlebone` on PATH,
in PATH's order (`findAllExeFilesInPath`), then the one in Cargo's `bin`
directory. The precheck runs `--version` on each in turn and skips one that
can't run, fails, times out, or doesn't print `cuttlebone <n>.<n>…`. The
next one is used, else a download, else the download offer. With nothing
left, the "not found" message says what was skipped. The skipped ones are
logged, kept on `CuttleboneService.skipped`, and shown in the settings' "In
use" line. That line moved into `CuttleboneService.inUse()` so tests can
read it. It looks like this: `cuttlebone 0.11.0 (Inko 0.21.1), downloaded
by Parakeet: <path>. Skipped <path> on PATH, which isn't a working
Cuttlebone: --version failed (mise ERROR cuttlebone is not a valid shim).`
With none in use, it starts with "None."

**Decisions you may want to revisit:**

- A Cuttlebone named in the settings that doesn't run is still reported
  (as `Refused`) rather than skipped: you chose it, so Parakeet doesn't
  download around it.
- A real Cuttlebone that refuses the project (another Inko) is still
  reported, with no download offered, as you asked. Downloading around it
  would also be reasonable, since a download never takes PATH's place.
- Every `cuttlebone` on PATH is considered, not just the first, so a mise
  shim earlier on PATH doesn't hide a real one later. The pure `locate`
  function, and its tests, are unchanged.

## The real-world projects

`RealWorldProjectsIntegrationTest` has one test per project. Each reads
from `../yorickpeterse/<name>` or `../inko-lang/<name>`, relative to the
repository, or from under `PARAKEET_FIXTURES`. It copies the project into
its temporary directory, leaving out `.git`, `build` and `dep`, and runs
`inko pkg sync` there if `inko.pkg` requires anything (so it needs the
network). It's skipped, with a message, if the project isn't there, the
sync fails, or `inko check` fails. Otherwise, against the real `inko` and
Cuttlebone:

- every error logged on any thread is recorded (`LoggedErrorProcessor`),
  and there must be none;
- the project-wide check, reported to the real problem-file service, marks
  no file;
- the libraries refresh from a background thread, and `inko doc` indexes the
  project;
- in up to three `src/` files and one `test/` file: highlighting (with the
  `inko check` annotator) finds no error; Cuttlebone has the file's hints;
  every member call (`x.name`, not in an import) and type name resolves
  through Parakeet, and every member call has a Cuttlebone definition; and
  for five member calls spread through the file, Cuttlebone has a hover,
  quick documentation renders, and completion at the name offers it.

The one exception is a closure's `.call`: the compiler provides it, and
nothing declares it, so neither Cuttlebone nor Parakeet has a target (12 of
these, printed rather than failed). The tests run with `-PrealWorld`, which
`scripts/check.sh --full` passes, and the per-commit check doesn't.

### Results with Inko 0.21.1 and Cuttlebone 0.11.0

| Project | Type checks | Files | Names resolved | Time |
|---|---|---|---|---|
| openflow | yes | 4 | 681 | 6 s |
| kvi | yes | 4 | 582 | 6 s |
| inko-markdown | yes | 4 | 2,244 | 9 s |
| inko-syntax | yes | 4 | 736 | 6 s |
| inko-builder | yes | 3 | 605 | 5 s |
| inko-wobsite | yes | 4 | 652 | 48 s (3 dependencies to sync) |
| yorickpeterse.com | yes | 3 | 477 | 6 s |
| idoc | yes | 4 | 141 | 6 s |
| website | yes | 3 | 202 | 6 s |
| shost | no: imports `std.sandbox`, which 0.21.1 lacks | | | skipped |
| clogs | no: the same | | | skipped |
| code-formatting-in-inko | no: `Format` is undefined | | | skipped |

All nine pass, in the final `check.sh --full` too.

### What they found, all fixed test first

1. **A local named like an imported module was taken for the module**
   (c02265f). yorickpeterse.com builds pages with
   `html.Document.html('en', fn (html) { head(html.head, …) })`. The closure's
   parameter `html` hides the module `builder.html`, so `html.head` is the
   document's method, and Cuttlebone answers it. `InkoNameContext` read
   `alias.name` as a module member whenever `alias` named an imported module,
   before looking for locals. So Parakeet never asked Cuttlebone, found no
   module method `head`, and resolved `html` to the module. Completion's
   fallback for code Cuttlebone can't check made the same mistake. Tests:
   `InkoResolverTest.testALocalHidesTheModuleItIsNamedLike` and
   `InkoCompletionTest.testALocalHidesTheModuleItIsNamedLike`.
2. **What only tests import had no definitions** (8554768). `inko doc`
   documents what `src/` imports, so `std.test` was missing in every project.
   `Tests` in `import std.test (Tests)` resolved to nothing, and quick
   documentation for `t.equal` and `t.test` was empty, though Cuttlebone
   found them. The stand-in module Parakeet already wrote, for projects that
   don't compile, now also imports the standard library's and dependencies'
   modules the tests import, in every project. To make sure it can't break
   `inko doc`, it imports only modules that exist, without build tags. Test:
   `InkoDocIndexTest.testModulesOnlyTheTestsImportAreDocumentedToo`.
3. **Two dependencies' modules of the same name broke the stand-in**
   (dffac79). inko-wobsite's tests import `builder.html` and
   `markdown.html`, which became two `import`s of `html` in one module. The
   compiler rejects that (`duplicate-symbol`), so `inko doc` failed and only
   the project's own code was known. I checked this by hand with the real
   `inko doc` on a copy. Fix 2 introduced this for inko-wobsite, which
   compiles. But a project that doesn't compile, whose dependencies have
   same-named modules, had it before this session, since that stand-in
   imports every dependency module. A module whose last name is taken is
   now imported as `self` under a name of its own. The existing test of the
   stand-in's text is unchanged. Test:
   `InkoDocIndexTest.testModulesWithTheSameNameAreImportedUnderTheirOwn`.

Fixes 1 and 2 are bugs in 0.12 too. They aren't in 0.12.1, which has only
bug 1's fixes, as you asked.

## release/0.12

`release/0.12` is at fbc1ddd: v0.12.0 plus three commits, with version
0.12.1. `scripts/check.sh --full` passed in that worktree: tests, plugin
build, and the Plugin Verifier against 2025.2.6.3 and 2026.2.3. Its
`build/distributions/parakeet-0.12.1.zip` is the unsigned local build. The
branch has no cuttlebone, so bug 2 doesn't apply. Not tagged, not pushed.

`main`'s CHANGELOG has the same 0.12.1 entry, below Unreleased.

## A flaky test, fixed

The first `check.sh --full` on db49a7d failed in
`CuttleboneFeaturesTest.testUsageHighlightingWaitsForAFilesFirstCheck`.
Three earlier sessions had seen it fail the same way and rerun it. The race
was in the test. After the highlights, usage highlighting prefetches the
caret's definition from a pooled thread, and on purpose doesn't wait for
it. But the test read the fake's log as soon as the highlights were in, so
on a busy machine the definition request hadn't reached the fake yet. I put
a 500 ms delay before that request was sent (only for one run), and the
test then failed every time, with the same message. It now waits until the
fake has received the definition request, then checks the same order. It
passes with the delay, and still fails if the definition is asked first.

The one other intermittent failure in earlier reports is the typing
replay's latency limits. One failed at a load average of about 90
(SESSION-download-cuttlebone.md). That one isn't a race: it measures
typing responsiveness against fixed limits, so heavy load can push it over.
I left it alone. Making it independent of load (relative limits, say, or
retrying only past a load threshold) is a decision for you, and loosening
a check is something AGENTS.md says not to do.

## What's left for you

### To publish 0.12.1

1. Review `release/0.12`'s three commits (0ff2c73, 61af9f1, fbc1ddd).
2. Push the branch, and tag `fbc1ddd` as `v0.12.1`. `release.yml` signs the
   plugin, creates the GitHub Release with `updatePlugins.xml`, and
   publishes to the Marketplace if `PUBLISH_TOKEN` is set.
3. **Mind which release is "Latest".** IDEs that use the custom repository
   read `releases/latest/download/updatePlugins.xml`, and `release.yml`'s
   `gh release create` leaves GitHub to choose which release is Latest. If
   0.12.1 is created after 0.13.0, check that 0.13.0 is still Latest
   (`gh release edit v0.13.0 --latest`). Otherwise the repository would
   offer 0.12.1 to everyone. Publishing 0.12.1 first avoids the question.

### To publish 0.13.0

1. `main` still says version 0.13.0, unpublished, and this session's six
   fixes are under **Unreleased**, not 0.13.0. The Marketplace's "What's
   New" comes from the version's own section (`build.gradle.kts`), so before
   tagging, either move the Unreleased entries into 0.13.0 (they'd ship in
   it), or tag the commit before them (902ce17, without them) and release
   them later as 0.13.1. I'd move them, since 0.13.0 has the EDT error
   otherwise. I didn't, because it changes a release you'd prepared.
2. Hand test (below), then tag and push as for 0.12.1.

### Checklist for testing by hand

- [ ] Open an Inko project in RustRover 2026.2.4: no "IDE error occurred",
      and nothing about `WolfTheProblemSolver` or the EDT in `idea.log`.
- [ ] Break a module that isn't open (e.g. rename a function a closed file
      calls) and save: the closed file turns red in the Project view and
      appears in the Problems tool window's project errors. Fix it and save:
      the mark goes. (This never worked before.)
- [ ] With the mise shim first on PATH (and no other `cuttlebone`): the
      notification offers the download, not "refused". After it, *Settings →
      Languages & Frameworks → Inko* says "downloaded by Parakeet" and
      "Skipped …/mise/shims/cuttlebone on PATH, which isn't a working
      Cuttlebone: --version failed (…)".
- [ ] With a Cuttlebone for another Inko on PATH: "Cuttlebone refused this
      project", as before.
- [ ] In a test file: quick documentation on `t.equal`, and ⌘-click on
      `Tests` in `import std.test (Tests)`.
- [ ] In yorickpeterse.com's `src/site/layouts.inko`: ⌘-click on `head` in
      `html.head` goes to `Document.head` in inko-builder.
- [ ] 0.12.1 in an IDE of its own (2025.2 or newer): the first two items.

## What's left unverified

- Nothing was tried in a real IDE: every check here is a headless test.
  The threading fix is tested against the real problem-file service, but
  not the Project view's red marking itself, or the cost of the
  background highlighting pass the filter enables.
- The real-world tests need the network to sync dependencies, and are
  skipped (not failed) offline. The three skipped projects need a newer or
  older Inko than 0.21.1. With another Inko, other projects may type
  check, or stop.
- Only member calls and type names are swept. Plain calls, fields and
  locals, Find Usages, Rename and the refactorings aren't exercised on the
  real projects.
- I didn't port fixes 1 and 2 of the real-world findings (the local hiding
  a module, and what tests import) to `release/0.12`. They are bugs there
  too.
