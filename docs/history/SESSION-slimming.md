# Session: slimming Parakeet

The goal was to slim Parakeet down: rely on Cuttlebone and Inko for what they
can do, delete Parakeet's own versions, and measure it in net lines removed
from `src/main` with the user's experience no worse. This session also made
the per-commit check robust to load, required Cuttlebone 0.13.0, and found
what hung kvi's tests in the real-world sweep.

This work is requested, not reviewed. Everything is under Unreleased; nothing
is tagged, published or pushed.

## The numbers

`src/main`'s Kotlin (the measure the request used, `find src/main -name
'*.kt' | xargs cat | wc -l`):

| | Lines |
|---|---|
| Before (20eef37) | 15,296 |
| After | 14,908 |
| Net | **−388** |

| Commit | What | `src/main` |
|---|---|---|
| e9b8631 | Scale local timing limits by the machine's load (step 0) | 15,296 |
| ead5552 | Require Cuttlebone 0.13.0, and delete what only older builds needed (step 1) | −35 |
| b6f9637 | Scale the tests' waits by the machine's load (step 0, found by the hook) | 0 |
| aad63b7 | Delegate folding to Cuttlebone | −89 |
| 1751edf | Delegate Reformat Code to Cuttlebone | −39 |
| 2b6135e | Delegate Go to Class and Go to Symbol to Cuttlebone | −70 |
| 2c8ae4d | Rerun a cut-off `inko test` once in the real-world sweep (step 3) | 0 |
| afd7ffa | Stop running `inko doc`: read definitions from the code | −211 |
| 7305822 | Refuse to extract a value that borrows (found by the sweep; [below](#3-kvis-hang)) | +7 |
| 185fc28 | Fold from Cuttlebone's ranges in Parakeet's own folding pass ([below](#folding-parakeet-asks-cuttlebone)) | +49 |
| (this commit) | This report, and README's link to it | 0 |

That reverses 388 of the 620 lines `src/main` grew by. Most of what's left
is either not something Cuttlebone answers yet, or something it answers with
gaps in the code being typed, where Parakeet's own reading has none; both are
below, with what Cuttlebone would need.

## The tools

- `inko 0.21.1`, from ivm.
- `cuttlebone 0.13.0 (Inko 0.21.1)`, the latest release, downloaded into this
  session's scratch directory by `ci/install-cuttlebone.sh` (through the
  release's `manifest.json`, checked against its size and SHA-256), and
  passed to every check as `CUTTLEBONE`. I didn't install or change
  Cuttlebone, and Cargo's `cuttlebone.aside` stayed where it was; its
  checkout was only read (docs/history/MILESTONE-12.md and MILESTONE-13.md,
  docs/lsp.md, and `src/lsp.rs` for how code actions choose names to import).
- Commits after the first two were made from a second worktree of this
  repository (in the scratch directory), so I could keep working while each
  commit's hook ran; every commit ran the hook, none bypassed it.

## 0. The per-commit check under load

`TimingLimits` (src/test) scales a timing limit as Cuttlebone's
tests/latency.rs does: its base times the load average over the last minute
per core, at least 1, unless `PARAKEET_TIMING_STRICT` is set. build.yml's
Cuttlebone job sets it (and release.yml runs build.yml), so on CI the limits
are as written; GuardrailsTest fails if the job stops setting it, and
build.gradle.kts passes it to the test and typing-replay JVMs.

What scales: the typing replay's latencies (hints and usage highlighting at
pauses, completion, parameter info), its share of answers for text that had
changed, its share of pauses with hints, and its waits. What doesn't: its
counts of requests and checks per keystroke, which say what Parakeet asks,
not how fast. Then, after the hook failed
`CuttleboneClientTest.testStopsWithShutdownAndExit`'s fixed 30 s wait at a
load average of 108 to 138 on 10 cores (it passed alone, at that load, in
10 s), b6f9637 made every test's wait for a condition `TimingLimits.seconds`
of what it was.

Two more hook runs failed on load, and passed when retried (no code
changed): `testAnEditReachesTheServerBeforeTheNextQuery` (the whole class
passed alone at a load average of 52 to 101), and
`testStopsWithShutdownAndExit` once more, in a unit run at a load near 100,
where the server was gone without ever logging `shutdown`: IntelliJ's own
stop timeout, which no wait of ours covers. Both are in
`CuttleboneClientTest`, and both only under load; I didn't change them.

## 1. Cuttlebone 0.13.0 or newer

`Cuttlebone.MINIMUM` is 0.13.0, and `--version` decides:

- On `PATH` or in Cargo's `bin` directory, an older one is skipped as one that
  doesn't run is ("Skipped … on PATH, which Parakeet can't use: it's
  Cuttlebone 0.12.0, and Parakeet needs 0.13.0 or newer."), so the download
  is offered as when there's none.
- In the setting, it's refused, saying the same, and nothing is downloaded
  around it (the setting is the user's).
- An older download is left unused, and the newer build offered as for none.
- Releases older than 0.13.0 are never offered.

Deleted, as only older builds needed them: looking up older releases through
GitHub's releases API (every release from 0.13.0 on has `index.json`), the
capability checks for inlay hints, signature help and document highlight
(milestone 4), and the tests' branches for 0.11.0 and older and for 0.12.0's
hover layout. Tests whose fake Cuttlebone named a version before 0.13.0 now
name a supported one (0.9.x, 0.10.0 and 0.11.0 became 0.13.x, 0.14.0 and
0.15.0; the fake is `0.13.0-fake`): those versions would now be refused for
a reason other than what the tests test. Four new tests failed before the
change and pass after it.

## 2. Delegating to Cuttlebone

### Folding: Parakeet asks Cuttlebone

`textDocument/foldingRange` folds blocks, comment runs and imports from the
tokens, so it answers in code that doesn't parse, as Parakeet's builder did,
and also folds a call's arguments and other brackets over several lines.

aad63b7 turned on IntelliJ's own LSP folding and deleted Parakeet's builder.
The real-world projects' tests then logged an error in every project:
IntelliJ's folding cache restarts the daemon (all highlighting, for every
open file) each time an answer arrives, which the test fixture forbids
during highlighting. In the IDE that's no assertion, but it is a whole
highlighting pass again for unchanged text, `inko check` included (its
results aren't cached). 185fc28 puts the folding back in Parakeet's
hands as glue only: `InkoFoldingBuilder` asks Cuttlebone for the ranges in
the folding pass (`CuttleboneQueries.foldingRanges`, which the pass waits
for, as the hints pass does) and turns them into folds; the brace, comment
and import parsing stays deleted. IntelliJ's LSP folding is off again.

What changed for the user: a fold starts at the end of its first line and
ends at the end of its last, so the first line (the `{`, the first comment
line, the first import) stays in sight, and every placeholder is `...`.
"Imports" and "File header" in Code Folding settings still collapse those
by default.

### Reformat Code: IntelliJ's LSP support shows it

`textDocument/formatting` is exactly `inko fmt`, from Inko's own formatter,
on the unsaved text. The LspCustomization turns it on and says to format
Inko files with it exclusively (otherwise IntelliJ's own formatting service
takes them). `InkoFormattingService` (which ran `inko fmt -`), its fake test,
and the integration test that formatted through it without Cuttlebone are
gone. Actions on Save's "Reformat code" uses the same service.

### Go to Class and Go to Symbol: IntelliJ's LSP support shows them

`workspace/symbol` has the project's, its dependencies' and std's
declarations, from the syntax. IntelliJ 2025.3 has Go to Class and Go to
Symbol contributors for it, so it's on, and Parakeet's contributors and
their tests are gone. Go to Symbol now finds fields too. What's left of
InkoGoto.kt (finding Inko projects, the startup activity) is
InkoProjectRoots.kt.

### The `inko doc` pipeline: replaced by reading the code, not by Cuttlebone

`InkoDocIndex` ran `inko doc --private --dependencies` in a mirror of each
project (with a stand-in module importing the dependencies and std when the
project didn't compile, and another for what only tests import), parsed its
JSON, rebuilt it in the background after saves, and read changed and unsaved
files from source over it. All of that is deleted. What's left is the part
that read from source, now for everything: `InkoDefinitionIndex` reads every
module of the project's `src/` and `test/`, its dependencies'
(`dep/…/src`) and the standard library's (`inko print std`), unsaved text in
place of a file's, each file again only when it changes. A trait's
implementations are the `impl Trait for Type` blocks naming it, by the
module each file imports it from, so the separate reading of `test/` for Go
to Implementation is gone too.

Why not Cuttlebone's answers: what reads this index needs whole-project
facts no request gives.

- **A trait's implementations, and a type's traits** (gutter icons, Type
  Hierarchy, Go to Implementation and Super Method, Implement Methods,
  Generate → Implement Trait): Cuttlebone has no `textDocument/implementation`
  or type hierarchy, `workspace/symbol` leaves out `impl` blocks, and
  `documentSymbol` is one file at a time.
- **Every definition of a name, with its owner, signature and whether it's
  public** (the resolver's reading of code being edited, parameter info for a
  constructor, Copy Reference for a library file, completion's names and
  auto-import, Extract Method's imports): `workspace/symbol` gives at most
  500 results, no signature, and no public flag.

`InkoSourceDefinitions` and `InkoSignatures`, which the request listed with
the pipeline, stay: the first is now the index's only reader, and the second
isn't part of the pipeline (it parses method headers and calls for Change
Signature, Inline Method, Introduce Parameter, Extract Method, Find Usages
and an inspection, which rewrite or read exact text no LSP request gives).

What changed for the user: nothing is compiled for the index, so it follows
unsaved edits and code that doesn't compile without a background rebuild; a
type's methods are those it and its `impl` blocks write (a trait's default
method it doesn't override is the trait's, where `inko doc` listed it under
the type too); and the first question about a project reads all of std and
the dependencies (a few hundred files) once, in the background when a file
opens.

The tests that described modules only in fake `inko doc` JSON now write them
as code; `FakeStd` writes a standard library for `inko print std` to name.
Two fixtures weren't valid Inko and now are: Type Hierarchy's `Other`
implemented `Debug: Show` without `Show` (the fake JSON listed `Show` among
its traits but not it among `Show`'s implementations), and the
documentation test's doc comments were only in the JSON. Their expectations
changed with them, and say why.

### Fallbacks kept: quick fixes, completion of names, documentation

These three stay Parakeet's. In each, Cuttlebone's answer has a gap in
exactly the code being typed, where Parakeet's reading has none, so
delegating would add the glue to ask Cuttlebone and keep all of Parakeet's
code as the fallback: more lines, not fewer.

- **Quick fixes for compiler diagnostics.** Cuttlebone 0.13.0 offers a fix
  only if the file then has no error at all (MILESTONE-13.md: "In a file with
  two undefined symbols, neither import is offered"). While typing, the file
  almost always has another error, so importing an undefined name, removing an
  unused import, `_x` and `let _ =` would all be lost there. It also has no
  fix for four IDs Parakeet fixes: `missing-field`, `invalid-implementation`,
  `missing-trait` and `unreachable`. Its `invalid-assign` fix (`mut`) is one
  Parakeet lacks; I didn't add it, since that's the glue above for one fix.
  (IntelliJ's own code actions also wouldn't do: Parakeet's diagnostics are
  `inko check`'s, so IntelliJ would send none in the request's context, and
  Cuttlebone reads the names to import from those.)
- **Completion of names, and auto-import.** Cuttlebone refuses names in code
  being edited whenever any declaration differs from a good check, even far
  away, and never answers them from the last good check (MILESTONE-12.md). So
  in the code being typed, names in scope and unimported names would be lost.
  It also has no keywords. Completion after `.` was already Cuttlebone's.
- **Quick documentation.** It shows the signature, where it's defined and the
  doc comment, from the definitions above, which answer wherever the code
  lexes; hover's declaration and doc comment are refused from a stale check
  once that text changed. The type at the caret ("Type here") was already
  hover's.

### Kept on purpose: the Structure view and breadcrumbs

`documentSymbol` leaves out the enclosing type while a brace inside it isn't
closed (MILESTONE-12.md: "an outline view will flicker"), so the Structure
view and breadcrumbs would come and go as code is typed. They stay
Parakeet's, from `InkoOutline`, which the refactorings use anyway.

## Requests for Cuttlebone

What would let Parakeet delete more:

1. **Implementations and the type hierarchy**: `textDocument/implementation`
   (a trait's implementing types, a trait method's implementations, in the
   projects, dependencies and std) and `typeHierarchy/supertypes` and
   `subtypes`. With them, the gutter icons, Type Hierarchy, Go to
   Implementation and Super Method could stop reading `impl` blocks.
2. **A trait's required and default methods**, say as a tag or a `detail` in
   `documentSymbol`, or a code action "implement missing methods" for
   `invalid-implementation`: Implement Methods and Generate could use it.
3. **Quick fixes that don't need the whole file to type check**: offer a fix
   that removes its diagnostic and adds no error, as MILESTONE-13.md's
   "Decisions" suggests (one line in `query::fixes`). Then Parakeet's import,
   unused-import, `_x` and `let _ =` fixes could go.
4. **The fixes Parakeet has and Cuttlebone doesn't**: `missing-field` (add the
   field to the call), `invalid-implementation` (stub the missing methods),
   `missing-trait` (implement the required trait), `unreachable` (remove the
   code).
5. **Completion of names in code being edited**, refused only where a change
   can bind another name (MILESTONE-12.md's "narrower rules"), and keywords.
   Then Parakeet's own names completion and auto-import could go.
6. **`workspace/symbol` for an index**: `impl` blocks, a public flag and the
   signature, and a way past 500 results (paging, or no limit for an empty
   query). With 1 and 2, `InkoDefinitionIndex` and `InkoSourceDefinitions`
   could go.
7. **Signature help for a type's constructor** (`Point(x: 1)`): parameter info
   reads the type's fields there.
8. **`collapsedText` in folding ranges**, a comment's first line, say: folds
   now all show `...`.
9. **Stable document symbols in code being edited** (the enclosing type kept
   while a brace inside it isn't closed): the Structure view and breadcrumbs
   could be IntelliJ's.

## 3. kvi's hang

It's neither a refactoring nor the sweep: kvi's own tests hang about once in
200 runs, on its code as pinned.

I rebuilt the tree after the failing site (Extract Method at
`test/kvi/test_server.inko:131`, which moved one `t.ok(…)` into
`fn extracted(t: mut Tests)`) and ran its tests over and over:

| Tree | Launched by | Runs | Hangs |
|---|---|---|---|
| after the Extract Method | `inko test`, from a shell | 100 | 0 |
| after the Extract Method | `inko test`, from a JVM, stdin a pipe left open (as IntelliJ runs it) | 150 | 1 |
| after the Extract Method | the test binary, from a JVM | 1,000 | 6 |
| after the Extract Method | the test binary, from a shell | 1,000 | 5 |
| kvi as pinned (7875efd) | the test binary, from a JVM | 1,000 | 6 |

Each hang has the same shape. The test binary (all its process threads idle)
waits for one child, a copy of itself: a `t.fork` test's child, from
`test/kvi/test_logger.inko` (`log.debug('foo')`, then `log.flush`, which waits
on a `Future`). std.test runs each such child with `INKO_PROCESS_THREADS=1`
and `INKO_BACKUP_THREADS=1` (std/test.inko, `Process.new`), and the child
spins, at 100% of a core, in `std.sync`'s `Shared.lock`
(std/sync.inko:160–162) on its one process thread. Its comment there says the
scheduler reschedules a process that spins too long; with one process thread,
whatever holds the lock doesn't get to run. That's Inko's standard library
(or runtime) and kvi's tests, not Parakeet. AGENTS.md rules out reporting it
to Inko, so it's here for you to decide.

Why the sweep hung in four full runs of five, when the last session saw no
hang in 493 runs outside it: the sweep runs kvi's tests once per site, and
dozens of sites, so at about 1 in 200 per run, a hang somewhere in a full run
is likely; the last session's loops ran `inko test` (compiling first), far
fewer runs per minute. Nothing leaked between sites: the hung run's processes
were its own (`inko test`, the test binary, its child).

**What the sweep found instead.** With kvi no longer hanging the sweep, the
full check at afd7ffa found a real case of what step 3 asked about, in
openflow: Extract Method of `Snapshot.of(state).rooms.get('bathroom')` (in
`….or_panic.status`) made a method returning `Result[ref RoomSnapshot,
MissingKey[String]]`. It type checked, but the `Snapshot` made in the new
method was dropped when it returned, while the `ref` into it was still
used, and the tests panicked ("can't drop a value of type 'Status' as it
still has 1 reference(s)"). It showed now because the index reads `test/`
and std, so Extract Method could import `RoomSnapshot` and `MissingKey`;
before, the site stopped at the placeholder type. **7305822** makes
Extract Method refuse a value whose type is a borrow (`ref` or `mut`,
anywhere in it), saying why. That refuses more than it must (a borrow of a
parameter's contents is safe), but nothing tells Parakeet what a borrow
points into. The test failed first.

**The fix (2c8ae4d)**: a cut-off `inko test` (600 s) runs once more, and the
site's outcome says so; a run cut off twice still fails the site, as does
any that fails. A refactoring that really hangs the tests hangs both runs.

## The final check

`scripts/check.sh --full` against Cuttlebone 0.13.0, at load averages of
roughly 100 to 370 on 10 cores:

- At afd7ffa: the tests, the typing replay and the plugin build passed; the
  real-world step failed 12 of 21. Nine projects logged IntelliJ's LSP
  folding error (fixed by 185fc28, [above](#folding-parakeet-asks-cuttlebone));
  openflow's sweep found the Extract Method bug (fixed by 7305822); and
  yorickpeterse.com's ("Rename at `request`: refused, but changed the code",
  `TITLE` renamed) and inko-builder's (Change Signature at `indent?`: the
  sweep's `declarationAt` found no declaration) didn't come back in the next
  run. Their details were overwritten before I looked at them, so I don't know
  more.
- At 185fc28: everything passed except one sweep, inko-syntax's, which threw
  a `NullPointerException` in the sweep itself (`filterKeys` on a map that
  `inBackground { … }` returned as null). Run alone, it passed (290 s). I
  didn't find how that map can be null.
- On this report's commit: run after the commit, since a report can't hold
  its own result; the session's closing message says how it went.

Many hook runs failed on load alone, each on another of Cuttlebone's timing-
sensitive tests (named in the commits that followed them); every one passed
alone. One wait the tests still don't scale: `RealWorldTestCase.openInEditor`'s
120 s, which b6f9637's search missed (the call spans lines).

## Hand-testing checklist

With Cuttlebone 0.13.0 for Inko 0.21.1, in IntelliJ 2025.3 and 2026.2:

**Cuttlebone 0.13.0 or newer**
- [ ] A `cuttlebone` 0.12.0 on `PATH` (nothing set, nothing downloaded):
  "Parakeet needs Cuttlebone" offers to download 0.13.0; the settings page
  says it skipped the one on `PATH`, and why.
- [ ] The same 0.12.0 set in *Settings → Languages & Frameworks → Inko →
  Cuttlebone*: refused, saying it's 0.12.0 and 0.13.0 or newer is needed; no
  download is offered.
- [ ] A 0.12.0 Parakeet downloaded earlier (in JetBrains' shared data
  directory, `parakeet/cuttlebone/`): not used; 0.13.0 is offered.

**Folding**
- [ ] Blocks, a run of comment lines, the imports, and a call's arguments over
  several lines fold; with a line that doesn't parse (`fn broken( {`), the
  rest still folds.
- [ ] *Settings → Editor → General → Code Folding*: "Imports" and "File
  header" collapse by default, as before. (Not checked here.)
- [ ] Placeholders are `...` (they were the comment's first line, `import
  ...` and `{...}`).

**Reformat Code**
- [ ] ⌥⌘L formats as `inko fmt` does, unsaved text included, and the undo
  restores it.
- [ ] Actions on Save → Reformat code formats on save.
- [ ] In a file that doesn't parse, nothing changes, and nothing breaks.

**Go to Class and Go to Symbol**
- [ ] ⌘O finds the project's types and traits; with "Include non-project
  items", std's (`Stdout`) and a dependency's.
- [ ] ⌥⌘O finds methods, fields and constants; a method typed and not saved
  is found.
- [ ] Choosing one goes to its name.

**Definitions read from the code (no `inko doc`)**
- [ ] Opening a project: no `inko doc` runs (Activity Monitor, or idea.log),
  and the IDE doesn't stall on the first question (std is read in the
  background when a file opens).
- [ ] Quick documentation (F1) on a std name, a dependency's and the
  project's: signature, "Defined in", the doc comment; in a project that
  doesn't compile, too.
- [ ] Trait gutter icons on a trait, its methods, a type and an `impl`, with
  an implementation in `test/` among the targets; Go to Implementation (⌥⌘B)
  and Super Method (⌘U).
- [ ] Type Hierarchy (⌃H) on a std trait the project implements (`ToString`),
  and on a project trait.
- [ ] Implement Methods (⌃I) in `impl ToString for …`, and Generate →
  Implement Trait in a type.
- [ ] Completion of a name from a module not imported yet (std's, a
  dependency's, the project's) adds the import; the auto-import quick fix on
  an undefined name too.
- [ ] Copy Reference in a std file gives `std.string.String`.
- [ ] A new method typed, not saved, shows up in all of the above.

**kvi's sweep**
- [ ] `./gradlew test -PrealWorld --tests '*.RealWorldRefactoringsIntegrationTest.testKvi'`:
  if `inko test` is cut off, the outcome says it was rerun.
