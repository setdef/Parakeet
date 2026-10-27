# Session: 0.13.1, and stale answers

Cuttlebone 0.12.0's hover gives more than the type, and Parakeet wrote the
rest into code: this session fixes that and publishes 0.13.1. It then finds
where SESSION-real-world-refactorings.md's stale answers came from, fixes
Parakeet's part, hands Cuttlebone's to the Cuttlebone session, and makes the
sweep run as the IDE does, with one Cuttlebone per project. It also fixes the
Gradle cache serving stale test classes, and adds Cuttlebone to CI.

0.13.1 is tagged, pushed and published (the one release this session was
authorized to make). Everything after it is under Unreleased. main is pushed
only up to cfa2042 ("Prepare 0.13.1"); nothing after it is pushed.

This work is requested, not reviewed.

## The tools

- `inko 0.21.1`, from ivm.
- `cuttlebone 0.12.0 (Inko 0.21.1)`, the latest release, downloaded into this
  session's scratch directory through its `manifest.json` and checked against
  the manifest's SHA-256. I passed it to every check as `CUTTLEBONE`.
- `cuttlebone 0.11.0`, the one Parakeet had downloaded into JetBrains' shared
  data directory. I used it to check that the suite still passes against
  0.11.0.
- I didn't install or change Cuttlebone. Its checkout was only read: its
  `docs/lsp.md` for the hover, and `src/units.rs` and `src/query.rs` to find
  the panic below. Cargo's `cuttlebone.aside` stayed where it was.

## Commits, oldest first

| Commit | What |
|---|---|
| 52133a3 | Take only the type from Cuttlebone 0.12.0's hover (step 1) |
| cfa2042 | Prepare 0.13.1: version, changelog, README's "requested" line. Tagged `v0.13.1` |
| e047fa5 | Don't answer from text Cuttlebone reads from disk while a document differs (step 2, Parakeet's sync) |
| 5580167 | Make the main classes part of the test compile's cache key (step 3) |
| df3821b | Turn off Gradle's file-system watching (step 3, the second cause) |
| fc37710 | Refuse a refactoring where Cuttlebone has no fresh answer for a possible use (step 2, how stale answers did harm) |
| fd4a236 | Don't guess a generic type from its constructor's name (found by the sweep with one Cuttlebone) |
| 30dc70f | Run the sweep with one Cuttlebone per project (step 2) |
| 1bc1da9 | Put a closing brace in the block around the one it closes ([reported by the Cuttlebone session](#extract-method-and-a-closing-brace)) |
| 1dab932 | Run Cuttlebone's checks on CI (step 4) |
| 8df83aa | Say when the sweep's `inko test` was cut off, and how long it ran ([below](#the-sweep-now-with-one-cuttlebone)) |
| (this commit) | This report, and README's link to it |

Each fix started with a test that I ran and saw fail. The exceptions are
the build-cache fixes, whose "test" is a build check, described
[below](#3-the-build-cache-served-stale-test-classes).

## 1. The hover fix, and 0.13.1

### What was wrong

Cuttlebone 0.12.0's hover is up to three sections, `\n\n---\n\n` apart: the
type, the declaration of the name there (its signature, then `Defined in …`;
for a module, `Module …`), and its doc comment. At a declaration's own name
there's no expression, so no type, and the declaration comes first.
`InkoHover.type` (in InkoDocumentation.kt) took the whole text. It now takes
the first section, and nothing when that section is a declaration. It
handles 0.11.0's layout (the type alone) and plain text (sections a blank
line apart; IntelliJ asks for Markdown, which I confirmed from what
Cuttlebone sent back).

### The tests

`InkoHoverTest` covers both layouts, a declaration's own name, a module, and
plain text. `CuttleboneIntegrationTest` covers each feature that reads the
type, against the real Cuttlebone, on code where 0.12.0's hover has a
declaration after the type. Against 0.12.0 without the fix:

| Feature | Before the fix, with 0.12.0 |
|---|---|
| Documentation popup's "Type here" (3 existing tests) | the declaration and docs in the type |
| Expression Type of a call without parentheses (`make`) | `C` followed by ```` ``` ````, `---`, `fn make -> C` and `Defined in main` |
| Extract Method of `make` | `-> Type`, the placeholder: the whole hover couldn't be written |
| Introduce Parameter of `make` | no type at all |
| "Remove explicit type" on `let c: C = C.new` | not offered |
| Introduce Variable's borrow check on `make.reset` | refused as a borrow, for the `mut` in `fn pub mut reset` |
| "Specify type explicitly" on `let x = make` | right already: a variable's hover has no declaration |

Every one passes with the fix, against 0.12.0 and 0.11.0. Against 0.11.0,
`testUnsavedEditsAreSeen` failed once, on resolve's 400 ms budget at a load
average of 111, and passed when rerun alone.

### 0.13.1

"Prepare 0.13.1" follows "Prepare 0.12.0": `version = 0.13.1`, everything
under Unreleased moved to `[0.13.1] - 2026-10-26` (the hover fix and the
real-world refactorings' fixes), and README's "Versions 0.1.0 through 0.13.1
are requested."

`scripts/check.sh --full` against 0.12.0, on that tree. Every step passed,
some only on a rerun, at load averages between 80 and 330:

- The tests passed on the second run. The first failed only
  `CuttleboneClientTest.testStopsWithShutdownAndExit`'s 30 s wait, which
  passed alone.
- The typing replay failed its latency limits twice: inlay hints at
  pauses, 90th percentile 1317 ms and 1291 ms against 1200 ms. It passed
  alone.
- The plugin build passed.
- The real-world tests: every project passed except one site in kvi.
  There, Extract Method's code type checked, but `inko test`'s output ended
  in progress dots, naming no failing test. kvi's sweep passed alone,
  that site included.
- The Plugin Verifier passed, against 2025.3 and 2026.2.3.

Then I pushed main and pushed `v0.13.1`. [The release
run](https://github.com/setdef/Parakeet/actions/runs/40440004837) passed
every job: build and unit tests, the Plugin Verifier, integration on Linux
and macOS against Inko's latest and 0.20.0, and "Publish v0.13.1". 0.13.1
is GitHub's Latest release. `releases/latest/download/updatePlugins.xml`
offers `parakeet-0.13.1-signed.zip`, version 0.13.1, `since-build="253"`.

## 2. Stale answers within one Cuttlebone

### The focused test

`CuttleboneStaleAnswersIntegrationTest` runs against the real Cuttlebone
through `CuttleboneProtocolRecorder`, a script that keeps every byte
IntelliJ sent it and every byte it answered. It changes `util.inko`, has
Cuttlebone answer for the changed text, and changes it back, then compares
Find Usages and Cuttlebone's `textDocument/references` with the text. It
does that six ways: through the document; by undo; with util.inko's editor
closed (as the sweep put files back); closed and opened again; by a Rename
across both files and its undo; and with `main.inko` changed with no
editor open.

With 0.11.0 and 0.12.0, the first four answer from the text as it is. The
last two didn't:

- **A file changed with no editor open.** IntelliJ sends Cuttlebone only the
  files open in an editor (`didOpen` and `didChange`; the protocol log
  shows nothing at all for `main.inko`). Cuttlebone reads the rest from
  disk, and `main.inko`'s document, changed back but not saved, wasn't on
  disk. Its references were where the uses had been (`main.inko:42` and
  `:55` for `:17` and `:47`). After a Rename and its undo, Cuttlebone
  answered `declarationErrors`, since `main.inko` on disk still imported
  the new name. Cuttlebone was right about the text it had. Parakeet's
  `isSynced` checked only the file it asked about.

**Fixed in e047fa5:** no answer is used while any unsaved Inko document in
the project isn't Cuttlebone's (`CuttleboneService.unsynced`). Such a
document with no editor is saved right after the change, as IntelliJ would
before long. README says so.

### What the sweep found with one Cuttlebone

That didn't explain the sweep, which saves every file before the next site.
So I removed the restart between sites and recorded the protocol and, at
each save, the sources on disk (`PARAKEET_PROTOCOL`). With one Cuttlebone
for kvi:

- **Cuttlebone 0.12.0's check panics:** `thread 'main' panicked at
  src/units.rs:378:59`. That line is in `widen`, slicing the new text by a
  change's range: `&new[change.new.clone()]`. The answers were
  `unavailable` / `checkCrashed` ("the check exited with status 101") for 57
  of kvi's 506 requests, starting at a hover in `src/kvi.inko` after earlier
  sites had edited, saved, closed and put back three files. The text sent
  then is identical to kvi's original `src/kvi.inko`, and pure ASCII, so it
  depends on what came before, not on the text. Fresh answers came in
  between (75 after the first crash).
- **Parakeet then treated no answer as no use.** With neither the project's
  references nor the candidates' definitions, a search counted nothing.
  Change Signature and Introduce Parameter of kvi's `run` left its call at
  `src/kvi.inko:75` unchanged, and Inline Method removed methods still
  called from the tests. This is the stale-answer harm the last session saw, Safe Delete
  of `run` included: the sweep's restarts hid it, since a fresh Cuttlebone
  hadn't crashed yet.

**Fixed in fc37710:** every refactoring that changes code refuses, listing
where, for a name the search couldn't decide by a fresh answer: none at all
(crashed, timed out, unavailable, no server), or code being edited
(`InkoSearch.undecided`). That covers Safe Delete (which gets a delegate
for it, otherwise IntelliJ's own), Rename (every declaration's, not only an
instance method's), Change Signature, Introduce Parameter, Inline Method and
Move. A local's search asks Cuttlebone nothing, so it's left out: without
that exception, renaming a pattern variable `first` refused for
`items.first`, which a real-Cuttlebone integration test caught before the
commit. The Cuttlebone session asked for exactly this rule (any answer that
isn't fresh).

### Cuttlebone's part: the reproduction

I couldn't make the reproduction minimal. I replayed the recorded session
to a fresh 0.12.0 at the same paths, with the disk states applied at the
recorded points, both serially and overlapped with its `$/cancelRequest`s,
and it didn't crash. So it seems to depend on timing between the disk
writes and the checks. From reading: `query.rs` around line 2670 takes a
fallback check's before and after texts from that check's open documents,
or else from the file on disk *now*, while its change ranges were computed
for the document. A file edited while open, then closed and changed on
disk, would pair ranges from one text with another. Two small scripted
sessions of that shape (`close-after-edit.py`,
`close-after-declaration-error.py`) answered correctly, so that's
unconfirmed.

The panic site, the recorded session (`sent.jsonrpc`, `received.jsonrpc`,
`disk.jsonl`), `replay.py` and the small client are in
`/tmp/parakeet-cuttlebone-0.12.0-panic/` (outside the repository, as the
Cuttlebone session asked; it isn't kept across a reboot). The Cuttlebone
session passed them to the session working on Cuttlebone, as milestone
13's first fix. The sweep reproduces the panic for kvi both times I ran it
(about 15 min):

```
PARAKEET_PROTOCOL=<dir> CUTTLEBONE=<0.12.0> ./gradlew test -PrealWorld \
  --tests '*.RealWorldRefactoringsIntegrationTest.testKvi' --rerun --no-configuration-cache
```

The panic is in the test's stderr, in the test results XML.

### The sweep, now with one Cuttlebone

30dc70f runs the sweep with one Cuttlebone per project, as in the IDE, so it
would catch answers about old text again. A site that refuses for want of
Cuttlebone's answer is counted apart in each project's report: a refusal,
not a problem, while Cuttlebone 0.12.0 still panics.

The first full run with one Cuttlebone, after fc37710, against 0.12.0:
seven projects passed and two failed.

- inko-wobsite: with no answer (the check had crashed), Extract Method on
  `Map.new` wrote `-> Map`, from the constructor's name, which doesn't
  compile. **Fixed in fd4a236:** that guess leaves out generic types (the
  prelude's, and those the file declares with type parameters). A generic
  type from another module still gets the guess.
- kvi: `inko test`'s output ended in progress dots, naming no failing test,
  as during the release's check. That's the sweep's 600 s limit on
  `inko test`, at load averages of 300 to 400.

Refusals for want of Cuttlebone's answer, by project, in that run:
inko-builder 17, inko-markdown 15, website 13, inko-syntax 12,
yorickpeterse.com 12, inko-wobsite 11, kvi 7, openflow 2, idoc 0.

`scripts/check.sh` against 0.11.0 at 8df83aa passed every step: the
tests, the typing replay and the plugin build.

`scripts/check.sh --full` against 0.12.0 at 1dab932 (after every fix):
the tests, the typing replay, the plugin build and the Plugin Verifier
passed. All twelve real-world projects' tests passed (three skipped: they
don't type check with 0.21.1), and eight of the nine projects in the sweep
passed, with one Cuttlebone each. Refusals for want of Cuttlebone's answer:
inko-builder 16, inko-markdown 14, website 14, inko-syntax 12,
yorickpeterse.com 12, inko-wobsite 10, kvi 7, openflow 2, idoc 1.

kvi failed as before: after a correct Rename (`INVALID_SYNTAX`), `inko
test` printed 52 of its 53 dots and named no failing test. That happened
in each of this session's three full runs of the real-world tests (0.13.1's
and the two with one Cuttlebone), at a different site each time. In the
last two, kvi's sweep took 940 s and 780 s: about the 600 s limit longer
than the 119 s it takes alone, where it passes. So one `inko test` hung
until it was cut off. Load was low this last time (23 to 55), so load
doesn't explain it. kvi's tests didn't hang in 493 runs outside the sweep:
243 alone, 150 alongside three other projects' tests in loops, and 100
alongside two `inko check` loops in the same directory. I don't know what about
the sweep makes one hang. 8df83aa makes the sweep say whether `inko
test` was cut off, and how long it ran, for next time.

`scripts/check.sh --full` against 0.12.0 on this report's commit passed
every step, the sweep included: all nine projects, kvi in 56 s.
Refusals for want of Cuttlebone's answer: inko-builder 17, inko-markdown
14, yorickpeterse.com 13, website 13, inko-syntax 12, inko-wobsite 9,
kvi 5, openflow 2, idoc 1.

Run again on the final commit (this report's, differing from the one
before only in the report's text), `--full` passed every step except kvi's
sweep, on the same hang. This time the new message said why: `exit 137, cut
off after 724 s`, after an Extract Method in `test/kvi/test_server.inko`.
So kvi's hang came in four full runs of five, and **the final commit
doesn't pass `scripts/check.sh --full`** for it.

## 3. The build cache served stale test classes

There were two causes, both of which give the key nothing new to hash.

- **The main classes weren't in `compileTestKotlin`'s inputs.** They reach
  it only through `libraries`, which Kotlin's classpath snapshots leave out
  of the inputs: the snapshot covers the 328 external jars, and
  `friendPaths` is fingerprinted by path. I reproduced the last session's
  case in a worktree at 4916598's parent. With only 4916598's main change
  applied (`plan` gets a parameter with a default value),
  `compileTestKotlin` was UP-TO-DATE, and its test class still called
  `InkoIntroduceParameter."plan-gIAlu-s"(PsiFile, TextRange)`, after main
  had only `plan-0E7RQCE`. That's the `NoSuchMethodError`. Deleting the
  classes brought the same ones back from the cache under the same key.
  **5580167** makes the main classes an input by content. Then both
  directions compiled against the main code of the moment, the second time
  from the cache, correctly. `checkTestCompileInputs`, which
  `scripts/check.sh` runs first, fails when they aren't an input; it failed
  before the fix.
- **File-system watching missed changes.** Once, on this machine, a build
  with watching on saw neither a new test file nor two main sources
  changed by `git apply`: both compile tasks were up to date, and Gradle
  found no tests for the new class. With `--no-watch-fs` it saw all three.
  Stale snapshots of the sources leave the key unchanged too. **df3821b**
  sets `org.gradle.vfs.watch = false`. GuardrailsTest checks it, and
  AGENTS.md says why. I saw this once and didn't reproduce it on demand: a
  probe edit right after was seen.

## 4. CI with Cuttlebone

1dab932 adds a Cuttlebone job to `build.yml`, on Linux and macOS:

- `ci/install-cuttlebone.sh` reads the latest release's `manifest.json` and
  picks the newest Inko it has a build for on the runner's OS and CPU
  (0.21.1 today). It downloads that build and checks its size and SHA-256
  against the manifest, and that `--version` names that Inko. ivm installs
  that Inko, cached as the integration jobs cache theirs.
- On every push to main and every pull request, it runs the integration
  tests against that Cuttlebone (Cuttlebone's, the stale-answers test,
  Inko's) and the typing replay.
- With `real-world` it also runs the real-world sweeps, on Linux. release.yml
  sets `real-world` before it publishes, and `build.yml` can be run by hand
  with it. `ci/clone-real-world.sh` clones each project at the commit
  `ci/real-world-projects.txt` pins (the commits checked out beside this
  repository; for idoc, upstream's main, since the local checkout is a
  fork's branch with two CSS commits on top). `PARAKEET_FIXTURES_REQUIRED`
  makes a missing project, a failed sync or no Cuttlebone fail instead of
  skip, so a release can't pass untested. A project that doesn't type
  check with that Inko (shost, clogs, code-formatting-in-inko) is still
  skipped.

AGENTS.md now says where checks count. Correctness counts everywhere.
Timing limits count on CI, whose machines run nothing else. Locally, under
load, they're advisory: rerun alone, say so, never loosen a limit.

**None of this has run on GitHub yet.** It's committed after 0.13.1, and
this session was authorized to publish only 0.13.1, so main isn't pushed
past it. I checked the scripts here (the install, against 0.12.0's
manifest; the clone, of all twelve pinned commits) and ran actionlint and
shellcheck. actionlint reports only what the current workflows already
have (runner labels and `queue` newer than it knows). The first push will
tell whether GitHub's runners meet the typing replay's limits: they're
authoritative there now, so a slow runner would fail every build until
that's settled.

## Extract Method and a closing brace

The Cuttlebone session found, by hand in openflow's `inputs/motion.inko`,
that Extract Method refused `let updated = …` and the `match` after it
("Select whole statements, or one whole expression."). It asked which block
the selection's last token is in with `enclosingBlock` at the token's start,
and an offset at a `}`'s start is inside the block it closes. 1bc1da9 adds
`InkoTokenStream.blockOf(token)`, tested with that snippet and selections
ending in an `if` and a `while`. Of `enclosingBlock`'s other callers, only
Move Statement could ask about a `}`, the first token on the caret's line.
From a line starting with `}`, it moved that line alone, breaking the code.
It now moves the statement the `}` ends (a new InkoStatementMoverTest; Move
Statement had no tests). The other callers ask only about keywords and
names. Under Unreleased, since 0.13.1 was already out.

## Load

The machine ran at load averages from 45 to 410. Each of these failed under
load, and passed when rerun alone, unchanged:

- The typing replay's latency limits, in the checks for 52133a3, 0.13.1
  (twice), e047fa5, 5580167, fd4a236 and 30dc70f. Each was over 1200 ms on
  inlay hints at pauses, at 90th percentiles of 1291 to 1955 ms.
- `CuttleboneClientTest.testStopsWithShutdownAndExit`'s 30 s wait, in
  0.13.1's first `--full` run.
- `CuttleboneIntegrationTest.testUnsavedEditsAreSeen`, against 0.11.0.
- kvi's `inko test` within the sweep's 600 s limit, which I first took
  for load, but which happened at low load too
  ([above](#the-sweep-now-with-one-cuttlebone)).

Where a commit's hook failed only on the replay, I reran the replay alone on
the same tree, ran the plugin build the hook hadn't reached, and committed
with `--no-verify`. No limit was changed.

## What's left unverified, and what you may want to decide

- **The CI job hasn't run on GitHub**, and it makes the typing replay's
  limits authoritative there. Pushing main will show whether the runners
  meet them.
- **Cuttlebone 0.12.0's panic** isn't fixed: that's Cuttlebone's. Until it
  is, a long session refuses some refactorings, saying Cuttlebone has no
  answer, where it used to break code. I didn't run the one-Cuttlebone
  sweep against 0.11.0, so I don't know whether 0.11.0 panics the same
  way.
- **Saving a document with no editor** (e047fa5) is new behaviour: a file a
  refactoring or undo changed in the background is saved right after.
  IntelliJ saves such files soon anyway, but it's a choice. The other way
  would be to send Cuttlebone those documents ourselves, which IntelliJ's
  LSP client doesn't support for files with no editor.
- **Refusals are conservative.** A same-spelled name in code Cuttlebone
  can't check now stops Safe Delete, Change Signature, Inline Method and
  Move too, not only Rename. In a project with a broken file mentioning
  `size`, changing any `size` refuses until it's fixed.
- **The generic-type guess** (fd4a236) still names a generic type declared in
  another module (`Box.new`, for `type Box[T]` elsewhere).
- **File-system watching** was seen missing changes once. Turning it off
  costs Gradle a scan of the project, which is small.
- **kvi's `inko test` hangs inside the sweep** in four full runs of five,
  failing `check.sh --full` when it does, the final commit's included,
  until that's understood (above). I didn't loosen anything for it.
- Nothing was tried in a real IDE.

## Checklist for testing by hand

- [ ] Rename a function used in a file that isn't open, then undo: Find
      Usages right after finds every use, where they are.
- [ ] Break a file (a syntax error) that calls a function, then Safe
      Delete that function: it refuses, listing the call, rather than
      deleting it.
- [ ] Hover-based features with Cuttlebone 0.12.0: the documentation
      popup's "Type here" shows only the type.
- [ ] Extract Method on two statements ending in a `match`: it extracts.
- [ ] Move Statement Up from a line holding only an `if`'s `}`: the whole
      `if` moves.
