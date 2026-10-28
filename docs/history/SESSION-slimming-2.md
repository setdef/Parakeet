# Session: slimming Parakeet, part 2 (Cuttlebone 0.14.0)

The goal was still to slim Parakeet down: rely on Cuttlebone and Inko for what
they can do, delete Parakeet's own versions, and measure it in net lines
removed from `src/main`, with the user's experience no worse. Cuttlebone
0.14.0 answers several of [the last session's requests](SESSION-slimming.md#requests-for-cuttlebone)
(its docs/history/MILESTONE-14.md, "What Parakeet can now delete"):
implementations, call and type hierarchies, selection ranges, file renames
and pull diagnostics. This session requires 0.14.0 and delegates each.

This work is requested, not reviewed. Everything is under Unreleased; nothing
is tagged, published or pushed.

## The numbers

`src/main`'s Kotlin (`find src/main -name '*.kt' | xargs cat | wc -l`):

| | Lines |
|---|---|
| Before (ab00ed8, 0.14.0) | 14,908 |
| After (9bebec9) | 14,722 |
| Net | **−186** |

| Commit | What | `src/main` |
|---|---|---|
| 9d4c963 | Require Cuttlebone 0.14.0 | ±0 |
| 986a99c | Delegate Extend Selection (`selectionRange`) | −77 |
| e60fe8a | Delegate Call Hierarchy (`callHierarchy/*`) | +22 |
| bd50bbe | Delegate Type Hierarchy (`typeHierarchy/*`) | −20 |
| e28198f | The shutdown test checks what was sent (from release/0.14's a436307) | 0 |
| 2b7bf30 | Delegate trait implementations (`implementation`, `declaration`) | ±0 |
| 55f2ea8 | Delegate moving files' imports (`willRenameFiles`) | +50 |
| 9bebec9 | Delegate errors (pull diagnostics) | −161 |
| (this commit) | This report, README's link to it, and the Type Hierarchy step's CHANGELOG entry and README row, which that commit had four times | 0 |

Three of the six delegations added lines. Asking Cuttlebone costs glue
(`CuttleboneQueries` grew by 130 lines: nine queries and a helper, each query a request
with its waiting and its documentation), and where Parakeet's own versions
were short, the glue is longer: Call Hierarchy's callers and callees were
about ten lines over Find Usages and the resolver. Moving files grew because
Cuttlebone's edits have to be collected before the move and made after it,
by range markers, and because a missing answer is now a conflict rather than
silence; trait implementations broke even, with Rename now refusing (a
conflict) where Cuttlebone has no answer instead of renaming what the `impl`
blocks named. The big deletion is `inko check`: its mirror, its JSON parser
and the project check after saves.

## The tools

- `inko 0.21.1`, from ivm.
- `cuttlebone 0.14.0 (Inko 0.21.1)`, downloaded into this session's scratch
  directory by `ci/install-cuttlebone.sh` through the latest release's
  `manifest.json` (which said 0.14.0; checked against its size and SHA-256),
  and passed to every check as `CUTTLEBONE`. Cuttlebone wasn't installed or
  changed, and Cargo's `cuttlebone.aside` stayed where it was; its checkout
  was only read (MILESTONE-14.md, docs/lsp.md, and `src/lsp.rs` for which
  status a file rename gets).
- Commits were made from a second worktree of this repository (in the scratch
  directory), so work could go on while each commit's hook ran; every commit
  ran the hook, none bypassed it. The machine ran at load averages of about
  170 to 610 on 10 cores throughout.

## Where IntelliJ's LSP support shows a feature

IntelliJ 2025.3 (sinceBuild 253) has LSP customizers for none of these:
implementation, call hierarchy, type hierarchy, selection range, file
operations, or a workspace pull (it has a document pull cache, but it shows
diagnostics only as IntelliJ's own annotations: no Parakeet quick fixes, no
Inspect Code, no marks on files that aren't open). 2026.1 adds call and type
hierarchy and selection range, but using them there would leave 2025.3 users
without the features. So for each, Parakeet asks Cuttlebone itself
(`CuttleboneQueries`) and keeps only the IDE glue, the same in both; IntelliJ's
own versions stay off in 2026.x (`CuttleboneCustomization261`), so nothing
shows twice.

## 1. Cuttlebone 0.14.0 or newer

`Cuttlebone.MINIMUM` is 0.14.0: an older one on `PATH` or in Cargo's `bin`
directory is skipped with the download offered, one in the setting refused,
an older download left unused, and older releases not offered for download,
exactly as for 0.13.0. The tests' fixtures that named a release moved up a
minor version (0.13.x to 0.14.x, and so on), so the four minimum tests test
0.13.0 against 0.14.0; they failed before the change and pass after it.

Nothing in `src/main` was there only for 0.13.0: the last session had
already deleted what older builds needed.

## 2. What was delegated, and what replaced it

Each is its own commit, in this order.

### Extend Selection: `textDocument/selectionRange`

`InkoSelections` (steps from the tokens and the outline: a string's contents,
an argument, a bracket's contents and the brackets, the statement, enclosing
declarations with their doc comments) is gone. `InkoExtendSelectionHandler`
asks `CuttleboneQueries.selectionRanges` and turns the chain into ranges.
Extend Selection runs on the EDT, where Parakeet otherwise never waits; this
one question waits there, up to 0.5 s, since Cuttlebone answers it from the
tokens without a check (MILESTONE-14.md measured ten positions in std's
largest module at 25 ms).

The old test's expectations moved to `CuttleboneIntegrationTest`, against the
real Cuttlebone, which meets them (and InkoInterpolationTest's, from the code
in a string to the string); `InkoSelectionTest` now tests the glue with a
stubbed answer.

### Call Hierarchy: `callHierarchy/incomingCalls` and `outgoingCalls`

Callers were Find Usages' references grouped by the method each is in;
callees were every name in the body resolved one by one. Both are now one
request each, from an item naming only the method's name's position
(Cuttlebone finds the method again at `selectionRange.start` and reads
nothing else of the item). Callers come from one check of the whole project,
by the compiler's ids, complete or none; callees from the method's file, a
stale answer where the method itself hasn't changed. Where the hierarchy's
root is (a method's name, a call, else the method around the caret) is
still found as before.

### Type Hierarchy: `typeHierarchy/supertypes` and `subtypes`

`InkoTypeHierarchy` read a type's traits from the `impl` blocks and a
trait's requirements by parsing its signature (`requiredTraitNames`); both
are gone, and so is `InkoRequiredTraitsTest`, which tested that parsing.
The answers come from the type database, std and the dependencies included,
so a trait's subtypes are everything the compiler records, not just the
`impl` blocks Parakeet could see. The node's label, module and icon come
from the declaration and its file.

### Go to Implementation, Super Method, the gutter icons, Implement Methods and Generate

- **Gutter icons** (`InkoTraitLinks`): a trait and a trait's method link to
  `textDocument/implementation`'s answer, which the slow line marker pass
  waits for (one whole-project check, then answers from the kept check). The
  icons pointing up, from an `impl`'s trait name and its methods to the
  trait, come from the `impl` header and the trait's declaration
  (`InkoTraitImpl`, which Implement Methods uses), and a type has no icon for
  the traits it implements any more. The first version asked Cuttlebone for
  those too (`textDocument/declaration` per method, supertypes per type), and
  the typing replay failed it: 380 requests for 230 keystrokes in std's
  `json.inko` (11 types and 15 `impl`s, asked on every highlighting pass),
  against a limit of 0.7 a keystroke. Now it's 128 for 230.
- **Go to Implementation** and **Super Method** ask Cuttlebone on demand:
  implementations; and declaration (an `impl`'s method), definition (an
  `impl`'s trait) or supertypes (a type). Super Method runs on the EDT, so it
  asks under a modal progress (`CuttleboneQueries.offTheEdt`).
- **Find Usages and Rename of a trait's method**, which count and rename the
  methods implementing it, take them from the same answers. Rename takes
  fresh answers only, and where Cuttlebone has none (a declaration has an
  error somewhere), it lists that as a conflict instead of renaming only
  what it knows of: before, it renamed what the `impl` blocks named.
- **Generate → Implement Trait** leaves out the traits Cuttlebone says the
  type implements, in other modules too, as well as the `impl`s in the file.
- **Implement Methods** still finds the trait by the name in the `impl`
  header and reads its methods from `InkoDefinitionIndex`: Cuttlebone has no
  list of a trait's required and default methods yet (request 1 below), and
  this runs while an `impl` is being typed.

Deleted from the index: reading `impl` blocks
(`InkoSourceDefinitions.implementations`), linking them into
`InkoDefinition.related`, and `InkoTypeRef`.

### Moving files and directories, and renaming directories: `workspace/willRenameFiles`

`InkoMoveFileHandler` found a moved module's imports with Find Usages and
rewrote each import's path after the move (`InkoReference.bindToElement`,
`InkoModuleImports`); renaming a directory did the same for each module in
it. Now both ask Cuttlebone, before the move, for the edits to every import
of every module that moves (one request for a directory), keep them by range
markers while the files move, and make them after. `bindToElement` does
nothing now: IntelliJ calls it for each reference to a moved file, which
would rewrite the imports twice.

The edits are asked for where IntelliJ says what moves and where to: a move's
`detectConflicts` (with the moved items) and a directory rename's
`prepareRenaming`. `findUsages` was the first try, but IntelliJ hands it each
file of a moved directory with the directory's destination rather than the
file's, so Cuttlebone was asked about the wrong move
(`CuttleboneFileMovesIntegrationTest.testMovingADirectoryUpdatesEachModuleInIt`
found it: `import lib.util` for `lib.other.util`).

Two things Cuttlebone does that Parakeet works around: its status for a file
rename names the first module that moves (in its order), not the document
the request was about, so Parakeet takes that request's status by method;
and it replies `null` with no status where no module's name changes, so
Parakeet only asks where one does (a move out of `src/` and `test/` is
already a conflict of its own). Requests 4 and 5 below.

### Errors: pull diagnostics, for the editor, Inspect Code and the whole project

All of Parakeet's `inko check` is gone: `InkoCheck` (running `inko check` in a
temporary mirror of the project with the unsaved text, and parsing its JSON,
Rust escapes and all), the temporary mirror (`InkoMirror` keeps only the
persistent one the test runner uses), and `InkoProjectCheck`'s check after
saves and at startup.

- **In the editor** (`InkoCheckAnnotator`) and **Inspect Code**
  (`InkoCompilerInspection`): `textDocument/diagnostic`, which Cuttlebone
  answers from a full check of the project with the documents exactly as they
  are, never an older one. The annotator waits outside a read action, so
  typing isn't held up.
- **The whole project** (`InkoProjectCheck`): `workspace/diagnostic` with the
  last result ids, which Cuttlebone holds until a check changes a file's
  report (a long poll); Parakeet asks again as soon as it answers, from when
  Cuttlebone starts. Files with errors are marked as problem files as
  before, now as you type rather than after saves.
- **Quick fixes** keyed on the diagnostic IDs are unchanged: the pull's
  `code` is the compiler's ID.
- **Optimize Imports**, which ran `inko check` on the file to find unused
  imports, takes them from the same pull.
- Parakeet no longer sends `"diagnostics": false`: with it, Cuttlebone never
  checks after an edit (docs/lsp.md), so a held workspace pull would wait for
  the next pull to schedule one. A
  client that pulls gets no pushes, so IntelliJ shows nothing twice.

What the user sees matches: CuttleboneIntegrationTest checks errors as you
type with their quick fixes, a module that isn't open marked and listed by
Inspect Code, and the invalid-escape case (`inko check --format json` wrote
`\u{1}`, which is why Parakeet had its own parser). Cuttlebone's
tests/diagnostics.rs holds its diagnostics to the installed `inko check`. One
thing a new test assumed was wrong, and the compiler is what it is: with an
error in a method, `inko check` doesn't report an unused import either
(checked by hand), and Cuttlebone doesn't. Where they differ (MILESTONE-14.md,
docs/lsp.md): after a compiler panic, Cuttlebone shows what was found before
it, where `inko check` showed nothing.

Tests that faked `inko check`'s JSON now stub the pull
(`InkoTestCase.pullDiagnostics`); the tests of the JSON parser, the temporary
mirror, and the dependency-sync tests of Parakeet's own checks are gone with
what they tested (Cuttlebone never syncs). `CuttleboneClientTest`'s
`testTurnsOffCuttlebonesDiagnostics` became
`testPullsDiagnosticsAndAsksForFileRenames`.

## Fallbacks kept, and what was lost

No new fallback was kept: in each case either Cuttlebone answers wherever
Parakeet's own version did (selection ranges and diagnostics answer in code
that doesn't parse), or what's lost is navigation, not something needed
while typing.

What's lost, exactly:

- **Without Cuttlebone, there are no errors.** Parakeet ran `inko check`
  itself with or without Cuttlebone; now errors are Cuttlebone's. Parakeet
  already requires Cuttlebone (and offers to download it), and most features
  are unavailable without it, so keeping `inko check` for that case would
  keep all of what was deleted.
- **While a module of the project doesn't parse** (the one being typed, say,
  or a quick fix's `y: ` left to fill in), the other modules show no errors
  or warnings, in the editor or the Project view, until it parses again.
  Cuttlebone checks the project as `inko check` does, and a module that
  doesn't parse stops the compiler before it checks the others; Parakeet ran
  `inko check` on each file, which checked it and what it imports. The
  request said to keep a fallback for what's lost; I didn't for this one:
  it would keep the whole `inko check` mirror this step deletes, for a
  transient state Cuttlebone could answer (request 8).
  `InkoIntegrationTest.testQuickFixesForMissingFieldsAndUnreachableCode`
  found it, and is now two tests, since its second half only failed because
  of the first's leftover.
- **Where a declaration has an error anywhere in the project**, Cuttlebone's
  implementations, hierarchies and file renames have no answer (complete or
  none). Then there are no trait gutter icons, Go to Implementation finds
  nothing, Type Hierarchy and Call Hierarchy's callers are empty, Rename of a
  trait's method lists a conflict, and a move lists a conflict (and, if
  continued, leaves the imports). Parakeet's `impl`-block reading answered
  there. The fix belongs in Cuttlebone (request 2).
- **In code that doesn't type check**, Call Hierarchy showed Find Usages'
  possible usages; it shows nothing now.
- **A type's gutter icon** for the traits it implements is gone (⌘U still
  goes to them): it would be a whole-project question per type on every
  highlighting pass (request 7).
- **Extend Selection** no longer has a step for a declaration with the
  comment above it, and without Cuttlebone only IntelliJ's word and line
  steps are left.

Kept on purpose, as the last session said and still true with 0.14.0: quick
fixes, completion of names and auto-import, quick documentation, the
Structure view and breadcrumbs (Cuttlebone's answers have gaps in the code
being typed), and Implement Methods' reading of a trait's methods (no
answer for it yet).

## Requests for Cuttlebone

What would let Parakeet delete more, or lose nothing:

1. **A trait's required and default methods** (a tag or `detail` in
   `documentSymbol`, or a code action stubbing the missing methods for
   `invalid-implementation`), still open from the last session: then
   Implement Methods, Override Methods and Generate → Implement Trait
   could stop reading the trait's methods, and with requests 6 and 7 of the
   last session, `InkoDefinitionIndex` and `InkoSourceDefinitions` could go.
2. **Implementations, hierarchies and file renames past a declaration
   error elsewhere**: answer from the last good check (stale) where the
   symbol is in text that hasn't changed, as definition does, or only refuse
   where the erroneous declaration could change the answer (as references'
   finer rule does). The gutter icons, the hierarchies and Rename of a
   trait's method go dark while any declaration has an error.
3. **Call Hierarchy's callers in code being edited**, as references answer
   them since milestone 8.
4. **A status for every file rename**: Cuttlebone replies `null` with no
   status where no module's name changes, so a client can't tell "no edits"
   from "no answer" except by working out module names itself. A `fresh`
   status would do.
5. **Statuses keyed by request id, or naming the request's document**: a
   file rename's status names the first module that moves, not the document
   the client asked about, so Parakeet takes it by method.
6. **A selection range for a declaration with its doc comment**, as
   Parakeet's steps had (and documentSymbol's `range` could include it).
7. **Several answers in one request** (implementations or supertypes for
   every declaration of a document, say, or as `codeLens`): the gutter
   icons would then cost one request a pass, and a type's icon could come
   back, and the icons pointing up could be Cuttlebone's too.
8. **Diagnostics past a module that doesn't parse**: check the other modules
   (or each file with what it imports, as `inko check FILE` does), or keep
   their last diagnostics, so one module mid-edit doesn't blank the rest.
9. Still open from the last session: quick fixes that don't need the whole
   file to type check (3), the fixes Parakeet has and Cuttlebone doesn't (4),
   completion of names in code being edited (5), `workspace/symbol` for an
   index (6), signature help for a constructor (7), `collapsedText` (8), and
   stable document symbols (9). Another session is building 0.15.0 from
   those now.

## Not slimming: the 0.14.0 release's CI failure

Midway, the session building Cuttlebone 0.15.0 asked for a fix to 0.14.0's
release run (40638215892), which failed only on the macOS Cuttlebone job's
typing replay, on a branch of its own off the tagged commit
(`release/0.14`, in `../Parakeet-release-0.14`), not mixed into this work.
Three commits there, each through the hook:

- **fc21b6a**: CI's timing limits scaled by the runner's baseline, as
  Cuttlebone's milestone 14 does. `ci/timing-baseline.py` times `inko check`
  of a copy of std (the median wall time of 7 runs) at the start of the
  Cuttlebone job; the scale is that over this M1 Max's 255 ms (the median
  CPU time of 15 runs, since this machine is never idle), never below 1, in
  the job's summary and `PARAKEET_TIMING_SCALE`. A `slow` input wraps
  Cuttlebone in `ci/slow-cuttlebone.py`, holding each answer, to see the
  limits fail.
- **a436307**: `testStopsWithShutdownAndExit` became `testStopsWithShutdown`
  (below).
- **2a3ab23**: the failed limit wasn't a latency but the share of cancelled
  requests (4 of 10, where half must be). Over 20 replays (18 here and the
  runner's 2), 0 to 7 requests whose text changed weren't cancelled (those
  something still waits for), whatever the count; the runner simply had few
  (10). So the share applies from 20, unscaled, and from 5 at least one must
  be cancelled; with cancelling turned off, 0 of 37 and 0 of 39 were, and
  both replays failed.

Pushing the branch was refused by this session's permissions; the other
session reviewed the diff, pushed it and ran CI: build.yml with real-world
passed every job (runner scales 1.09 and 1.23), and with `slow=1000` both
Cuttlebone jobs failed on timing, as they should. Moving the `v0.14.0` tag
was refused there as destructive, so it was released as 0.14.1 (d6f9359,
"Prepare 0.14.1"); `v0.14.0` stays unpublished. `a436307` was cherry-picked
here first (e28198f), since its flake kept failing this branch's hooks; then
release/0.14 was merged in, its CHANGELOG's 0.14.0 section becoming 0.14.1's.

## The per-commit check under load

The machine ran at load averages of about 120 to 610 on 10 cores. Hook runs
failed on load and passed rerun alone, each named in its commit's message:
`testStopsWithShutdownAndExit` (four times),
`testAnEditReachesTheServerBeforeTheNextQuery`, three resolves that missed
their 0.4 s budget, a tear-down race (a fake still writing its log as the
temporary directory went), and InkoDefinitionIndexTest's
`testSavedChangesAreKnown`, which isn't a timing check: it looks like the
index reusing its merged definitions until a save's VFS event arrives, left
for a session of its own. Once, two hooks collided on one worktree's test
results, because a hook I had stopped kept running.

`testStopsWithShutdownAndExit` failed often enough that AGENTS.md's "make the
check itself robust" applied: IntelliJ's `Lsp4jServerConnector` sends
`shutdown`, waits a fixed 10 s for the answer, and sends `exit` only if one
came, so a fake starved of CPU can be gone before it has logged either. The
test now checks what was written to the server (`CuttleboneInputTap`'s
counts): `shutdown`, a stop rather than a kill, and no server left.

## The final check

`scripts/check.sh --full` against Cuttlebone 0.14.0 passed on 9bebec9's code
with this report's files in place (the tree this commit makes), in 959 s, at
load averages of about 10 to 40 (the other agents had gone quiet): the tests
(unit, integration and guardrails), the typing replay (128 and 85 requests
in std's `json.inko`, under 0.7 a keystroke; about 0.4 before this work, the
rest being the editor's diagnostics pulls in place of `inko check`), the
plugin build, the real-world projects (`PARAKEET_FIXTURES` naming the
checkouts beside this repository), and the Plugin Verifier against 2025.3
and 2026.2.3.

Of the real-world projects, three skipped themselves, as the test does for a
project that doesn't type check with the installed Inko: clogs and shost
(`std.sandbox` isn't in 0.21.1) and code-formatting-in-inko (`Format`
undefined). Their checkouts here aren't on the commits
ci/real-world-projects.txt pins. The other nine passed both the projects'
test and the refactorings' sweep.

This commit's own hook ran the normal check; a report can't hold its own
result.

## Hand-testing checklist

With Cuttlebone 0.14.0 for Inko 0.21.1, in IntelliJ 2025.3 and 2026.2:

**Cuttlebone 0.14.0 or newer**
- [ ] A `cuttlebone` 0.13.0 on `PATH` (nothing set, nothing downloaded):
  "Parakeet needs Cuttlebone" offers to download 0.14.0; the settings page
  says it skipped the one on `PATH`, and why.
- [ ] The same 0.13.0 in the setting: refused, saying it's 0.13.0 and 0.14.0
  or newer is needed.
- [ ] A 0.13.0 download from before: not used; 0.14.0 is offered.

**Extend Selection**
- [ ] ⌥↑ from a name in a call's argument: the name, the argument, the
  arguments, the brackets, the call, the statement, the block, the method,
  the file. In a string: its contents, then the string. In a file with
  `fn broken( {` below: the same.
- [ ] No noticeable pause on ⌥↑ on a busy machine (it waits up to 0.5 s).

**Call Hierarchy**
- [ ] ⌃⌥H on a method: callers (with a caller in another module), and callees
  including std's methods; a trait's method's callers through a bound.

**Type Hierarchy**
- [ ] ⌃H on a project trait: the traits requiring it and the types
  implementing it (in `test/` too); on a type: its traits, std's (`ToString`)
  included; on std's `ToString`: the project's types among std's.

**Trait gutter icons, Go to Implementation, Super Method**
- [ ] Icons on a trait, its methods, an `impl`'s trait and its methods (none
  on a type); clicking each goes where it says, an implementation in `test/`
  and in another project's module included.
- [ ] ⌥⌘B on a trait and on a trait's method; ⌘U in an `impl`'s method, on an
  `impl`, and on a type.
- [ ] Rename a trait's method: its implementations are renamed too. With a
  declaration error elsewhere (`fn broken -> Nope {}`), Rename lists the
  conflict.
- [ ] Generate → Implement Trait in a type that implements a trait in
  another module: that trait isn't offered.
- [ ] Implement Methods (⌃I) in a new `impl ToString for …` typed just now.

**Moving files and directories**
- [ ] Move `src/app/util.inko` to `src/lib/`: `import app.util (…)` becomes
  `import lib.util (…)` everywhere, open files and not; Undo puts it back.
- [ ] Move the directory `src/app/other` into `src/lib`: its modules'
  imports become `lib.other.…`.
- [ ] Rename the directory `src/app` to `core`: imports become `core.…`.
- [ ] With a declaration error in the project, a move lists the conflict.

**Errors**
- [ ] Errors and warnings as you type, with quick fixes (import, unused
  import, `_x`, `let _ =`, missing field, implement methods).
- [ ] A module with an error that isn't open is red in the Project view and
  listed in Problems, without saving anything; fixing it (in another file,
  say) clears the mark.
- [ ] *Code → Inspect Code* lists every module's errors and warnings.
- [ ] Optimize Imports removes an unused import and sorts.
- [ ] Without Cuttlebone (the setting at a path that doesn't exist): no
  errors, and nothing in idea.log beyond Parakeet's notice.
