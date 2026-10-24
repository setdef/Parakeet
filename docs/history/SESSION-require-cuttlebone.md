# Session: requiring Cuttlebone

Parakeet now requires Cuttlebone. No version that depends on it has been
published, so nobody relies on Parakeet working without it. This session:

- makes what needs the compiler's answers unavailable without Cuttlebone,
  with a one-time setup message, instead of guessing
- decides, feature by feature, where Parakeet's own reading of the code
  still stands in (only where Cuttlebone can't check code being edited), and
  deletes the rest
- moves Find Usages, Rename and the other searches onto
  `textDocument/references`
- finds and fixes the cause of the flaky usage-highlighting test

This work is requested, not reviewed.

## Commits, oldest first

| Commit | What |
|---|---|
| 723a69a | Step 1: no guesses stand in for Cuttlebone; the setup message; the search-based refactorings unavailable without it |
| f9c0137 | Step 2: each feature's fallback decided; the parameter-name hint heuristic and the stated-types reading deleted; resolve split into read-only and exact |
| 19b627c | Step 3: searches by `textDocument/references`, once per search |
| a19d418 | Step 4: usage highlighting waits for a file's first check; waits cancelled by IntelliJ no longer cancel the request |
| (this report) | The Find Usages handler's gate, this report, README |

## The `cuttlebone` on PATH

The one in Cargo's `bin` directory, `cuttlebone 0.1.0 (Inko 0.21.1)`. Its version
string doesn't say the milestone, so I checked its behaviour: a second
question about unchanged text came back with `"cached": true` in 0.6 ms
(the first took 39 ms), which only milestone 6 does. I didn't install,
rebuild or modify it, or anything in the Cuttlebone or Inko checkouts. I
read Inko's checkout only through `git archive` (std 0.21.1, below) and
copied idoc's tree (with its synced `dep/`) into my scratch directory.

## Step 1: without Cuttlebone

- **A setup message, once.** When the precheck finds no `cuttlebone`, or
  one that refuses the project (another Inko version), a warning
  notification says what's unavailable, with **How to set up Cuttlebone**
  (README's section) and **Settings…**. It's shown once per project, and
  again only after the settings change
  (`CuttleboneClientTest.testWithoutCuttleboneASetupMessageIsShownOnce`).
  The status in the settings page no longer says "types are guessed".
- **No guesses.** `CuttleboneQueries` now tells three cases apart: an
  answer, no answer, and no answer because the code is being edited
  (`Answer.editing`: reasons `noHir`, `declarationErrors` and `untyped`, or
  only a stale answer where the question doesn't take one). Every feature
  falls back to Parakeet's own reading only in the last. Without Cuttlebone,
  after a timeout, while it starts, or where it answers that nothing is
  there, nothing is shown.
- **Unavailable, not half done.** Find Usages, Rename, Safe Delete, Change
  Signature, Inline Method, Move, Introduce Parameter and Call Hierarchy
  can't run without Cuttlebone, except on locals and modules, whose uses
  follow from the code (`Cuttlebone.canSearch`, `InkoWithoutCuttleboneTest`).
  Otherwise they'd find only imports, and Rename would break the code.
- **The guarantee is gone** from the code's comments, the tests, README,
  `plugin.xml` and CHANGELOG. README's "Cuttlebone (optional)" is now
  "Cuttlebone".

## Where Cuttlebone gives no answer

From docs/lsp.md and Cuttlebone's milestone 5 and 6 reports, and checked
against the real server with a small LSP client in my scratch directory
(not committed):

| State | What the server answered |
|---|---|
| Healthy project | `definition` fresh, 39 ms; the same again `cached`, 0.6 ms; `references` fresh, 29 ms |
| A type's constructor, `B(w: 1)` | `signatureHelp` fresh, `null`: it has none |
| `let z = [1,` unclosed, earlier in a method | after it in that method, `definition`, `hover` and `documentHighlight` unavailable (`noHir`); in another method, `definition` fresh from a repaired copy; `completion` unavailable in both methods; `references` unavailable |
| After a good check, `import app.nope` added | `definition` and `hover` stale; `references` and `completion` unavailable |
| Another file gets a declaration error after a good check | `definition` and `references` unavailable (`declarationErrors`) in the file importing it |
| A project opened with a declaration error (no good check yet) | `definition`, `hover`, `documentHighlight`, `signatureHelp` and `inlayHint` all unavailable in a file importing it |
| `references` at std's own `Array.size` declaration | unavailable (`noHir`): std isn't the user's project. At a use of it in the project: fresh, the project's uses |
| `references` on a trait's method, `Shape.area` | the declaration, a call in a default method, and `s.area` through a bound; not `B`'s implementation, nor `x.area` on a `B` |
| `references` on `B.area`, the implementation | the declaration and `x.area` |

So mid-edit, whole files go dark when a project opens with a declaration
error, and the method being edited goes dark after the edit point; and
`references` has no answer while any file in the project has an error that
stops type checking.

## Step 2: each feature's decision

"Kept" means Parakeet's existing code, unchanged, used only when
Cuttlebone's answer is `editing`. Lines are in `src/main`.

| Feature | Decision | Fallback states | Lines |
|---|---|---|---|
| Resolve, for navigation, quick documentation, Call Hierarchy's callees, Type Hierarchy's target, parameter info's candidates | A fresh answer, else a **stale** one (new: Cuttlebone guarantees it holds for the code as it is), else Parakeet's reading. Only a fresh answer is a valid `ResolveResult`, so Rename at the caret never acts on the others | a project opened with a declaration error, after the edit point in the method being edited, a declaration error elsewhere: navigation and documentation would go dark mid-edit | heuristic kept; the guess where Cuttlebone answered nothing deleted |
| Resolve, for what changes code: Rename, Change Signature, Inline Method, Introduce Parameter, Move, the argument-name intentions, the redundant-argument-name inspection and fix | fresh answers only (`resolveExactly`), never the heuristic | none | |
| A definition `inko doc` hasn't seen | the target counts anyway: navigation goes there | | |
| Completion after `.` | kept | after a line that doesn't parse, in any method of the file (probe above): completion is needed most while typing | none deleted |
| Parameter info | kept; a constructor's fields come from the type the compiler resolves | the same states: parameter info is needed while typing a call | none deleted |
| Parameter-name hints | **deleted**: no hints without an answer | none: hints only inform, and a guessed name misleads | `InkoParameterHints.at` and `arguments`: +7 −64 |
| Type hints | unchanged: only Cuttlebone's | | |
| Quick documentation | the target as for navigation; "Type here" from hover, stale marked | as resolve | |
| Expression Type | **deleted** the stated-types reading: the compiler's type, else "Unknown", saying why (no Cuttlebone, code it can't check, or no type for exactly that expression) | none: rarely needed mid-edit, and a guessed type misleads | `InkoExpressionTypes`, 190 lines: +42 −237 in the file |
| "Specify type explicitly" | the compiler's **fresh** type for the variable, never one with `?` | none: it writes code | in the 237 above |
| "Remove explicit type" | only where the value says its type itself (a literal, a cast, `Point(…)`, `Type.method` returning that type: Cuttlebone's own rule for leaving out type hints), and the compiler's fresh type for the value is the one written. So it's no longer offered for `[1, 2]` | none | +39 −5 |
| Usage highlighting | kept (`InkoUsages.inFile`, which Change Signature and Inline Variable also use for parameters and locals) | after the edit point, and in a file whose imports have a declaration error: seeing a variable's uses in the method being edited | none deleted |
| Find Usages | step 3 | | |
| Inspections and intentions in the highlighting passes | resolving now waits there, within its 0.4 s budget, instead of using only cached answers: after a file's first check each is a lookup | | |

**Locals, imports and module paths** still resolve from the code alone.
They aren't guesses: no type is involved, and the completion of names in
scope, Extract Method, Inline Variable and Change Signature's parameter
renames need the same scoping anyway. Cuttlebone isn't asked about them.

**Actions on the EDT** never wait, as before. The usage-highlighting pass
now asks the caret's definition in advance, so Change Signature or Inline
from a call, and an intention's action, find it already given.

**A bug the compiler's answers found:** Change Signature renamed a named
argument (`height: side`) of a call to the method in the same file as a
use of the parameter, because Cuttlebone rightly says a named argument
refers to the parameter. Calls are rewritten as a whole, so that call then
couldn't be mapped and kept the old name. Parameter renames now stay in
the method's body. `InkoIntegrationTest.testChangedSignaturesStillCompile`,
once run against the real server, failed without the fix.

## Step 3: Find Usages with real types

### How it works now

The word index finds the candidates, as for any language. Each is a use
if Cuttlebone's `textDocument/references` lists it (`InkoCompilerReferences`):
one check of the whole project per search, kept until the code changes.

- **A declaration outside the project** (std, a dependency) can't be asked
  about where it's declared (the probe above), so a use in the project is
  found first: candidates resolved through `definition`, a file at a time,
  until one is the declaration.
- **Where references have no answer**, each candidate is resolved through
  `definition` instead, a file's requests sent at once (the first costs the
  file's check, the rest are lookups). Fresh answers only, except in Find
  Usages, which changes nothing: there, where Cuttlebone can't check the
  code, Parakeet's reading counts too, as a **possible usage**. Wherever
  Cuttlebone answers there are none.
- **Rename** leaves such places alone, and lists them as conflicts first
  ("Cuttlebone can't check the code at main.inko:7 (it doesn't type
  check), so `value` there isn't renamed").
- Searches no longer use only answers already given (`cachedOnly` is gone):
  they ask, and the kept checks make it cheap.

### Trait methods

The compiler gives a trait's method and each implementation their own ids,
so `references` on `Shape.area` doesn't list calls on a `B`. I decided that
**Find Usages of a trait's method includes its implementations' calls**: a
call on a type implementing the trait calls the implementation, and asking
who uses the trait's method means all of them. An implementation's own
search lists only its own calls. Both paths apply the same rule.
Consequently **renaming any of them renames the trait's method and every
implementation**, or they'd no longer implement it, and Rename warns when
the trait is outside the project, since it can't be renamed with them.

The first measurement of the per-candidate path on idoc found 2 usages
where references found 66: it compared each call to the trait's method
only. `InkoTraitNavigationTest.testWithoutReferencesATraitMethodStillIncludesItsImplementationsCalls`
failed without the fix.

### Measurements

`FindUsagesMeasurementIntegrationTest`, on an Apple Silicon Mac, three runs
each, the first of each way with a new Cuttlebone (no checks kept). Found
with `ReferencesSearch` over the project in the background, as IntelliJ
runs it.

- **std** 0.21.1's own `src/` and `test/` (222 files, from
  `git archive v0.21.1`), Find Usages of `Array.size`.
- **idoc** (inko-lang/idoc at d72cd3f: 23 files, 4,188 lines, and 4
  dependencies), Find Usages of `Symbol.name`, a trait's method that the
  IR's types implement and call everywhere (`.name` is idoc's second most
  common member).
- **Before** is the tree before this session (ecbc5be), measured the same
  way in a scratch copy, without Cuttlebone.

| | Run 1 | Run 2 | Run 3 | Usages | Requests |
|---|---|---|---|---|---|
| std, before (no Cuttlebone) | 2,319 ms | 1,327 ms | 1,200 ms | 344, mostly guesses | 0 |
| std, before, with Cuttlebone (cached answers only) | 1,295 ms | 1,216 ms | 1,293 ms | 344 | 0 |
| **std, references once** | **2,149 ms** | **421 ms** | **541 ms** | **93** | 1 |
| std, each candidate's definition | 12,100 ms | 1,127 ms | 1,529 ms | 93 | 1,414 |
| idoc, before (no Cuttlebone) | 512 ms | 499 ms | 438 ms | 64 (54 possible) | 0 |
| **idoc, references once** | **982 ms** | **116 ms** | **117 ms** | **66 (0 possible)** | 5 |
| idoc, each candidate's definition | 2,058 ms | 531 ms | 529 ms | 66 (0 possible) | 258 |

**The choice: `references` once per search**, falling back to each
candidate's definition only where references have no answer.

- **Correct:** both ways agree (93 and 66), by the compiler's ids. Before,
  std's 344 counted every `size` on anything.
- **Fastest:** a cold first search is about as fast as before on std
  (0.93×), and 1.9× before on idoc (within the brief's "about twice"). The
  extra on idoc is the first check of the whole project with its
  dependencies. Later searches, until the code changes, are 2 to 4 times
  faster than before.
- **The fallback** costs what it costs: on std, 12 s for a cold search, a
  check per file with a `size` in it. That's only while the project has an
  error that stops its type check. I left it rather than show nothing,
  since Find Usages mid-edit is still useful.

### Found on the way

- **A field set by name in a constructor**, `count:` in `Helper(count: 0)`,
  is a use of the field: the real server lists it (util.inko:5:11). Parakeet
  missed it, so **renaming a field left constructors behind**.
  `testAFieldOnlyInItsOwnType`'s expectation changed to include it, after I
  checked the real server's answer, and `testRenameAFieldKeepsItsAt` now
  checks `Helper(total: 0)`.
- **The Find Usages handler** (new in step 3) bypassed step 1's gate:
  IntelliJ asks handler factories before `FindUsagesProvider`, so without
  Cuttlebone Find Usages would have run and found only imports.
  `InkoWithoutCuttleboneTest.testFindUsagesOfAMethodIsUnavailable` failed
  without the fix.

## Step 4: the flaky usage-highlighting test

`testUsageHighlightingKnowsAssignments` failed once in eight full runs last
session. I measured, with a throwaway test (not committed), the test's own
steps: a new server, a just-opened file, the usage highlighting question.

| | First question | A second, about the same text |
|---|---|---|
| Idle, 8 runs | 38–61 ms | 11–60 ms |
| 10 busy processes on 10 cores | 41–126 ms | 12–83 ms |
| Beside another Gradle test run, 20 runs | 48–260 ms, rising as that run got going | |

**The cause is the budget.** The test's question is the file's first, so
it pays a whole check, whose cost grows with how busy the machine is. Its
budget, 0.4 s, dates from when every question cost a check, and is the
same as resolve's. Beside one other test run it reached 260 ms; the full
suite with the Plugin Verifier is busier. Usage highlighting now waits up
to 2 s, as inlay hints (also a file's first question, usually) do. After
the first check Cuttlebone answers from the check it kept, so the budget
only matters then. Moving the caret still ends the wait at once.

Two things made it worse, and are fixed too:

- **My own step 2 change** sent the caret's definition before the
  highlights' question. And a second question about the same text wasn't a
  lookup in these measurements, apparently because Cuttlebone doesn't keep
  a check that read a file written just before (docs/lsp.md, Kept checks),
  which a test (or a save) just did. So the highlights waited for two checks. The
  definition is asked after them now.
- **Cancelling a wait cancelled the request.** Moving the caret cancels the
  usage highlighting pass, and Parakeet sent `$/cancelRequest`. Since
  milestone 6 that kills Cuttlebone's worker and every check it kept, so
  the next question pays a first check again, and could itself be
  cancelled. The request now finishes, and its answer is kept for the next
  pass. An edit still cancels it, on the server.

Tests that failed before the fix:
`CuttleboneFeaturesTest.testUsageHighlightingWaitsForAFilesFirstCheck` (a
fake whose answer takes 1 s, and the question order) and
`CuttleboneClientTest.testCancellingTheWaitLeavesTheRequestToFinish` (which
replaces the test that `$/cancelRequest` was sent). The flaky test itself is
unchanged. It passed in every run this session, before the fix (six
`check.sh` runs, the first on the tree before this session) and after it (the step 4 commit's, and the `--full` runs
below), which shows only that it rarely failed, not that it can't now. That
it can't, short of a first check taking over 2 s, rests on the
measurements above.

## Tests

660 tests, from 654.

- **Tests of Parakeet's own reading** (completion, resolve, parameter info,
  usage highlighting, …) now run where that reading applies: with
  Cuttlebone stubbed in-process to say it can't check the code
  (`cuttleboneCantCheckTheCode`).
- **Tests of what changes code** (Find Usages, Rename, Change Signature,
  Inline Method, Introduce Parameter, Move, Safe Delete, setters, Call
  Hierarchy, the intentions and inspections that resolve) get the
  compiler's answers from an in-process stand-in that knows each test's code
  (`InkoTestCase.Compiler`: where each use's definition is, what type is
  where, and where it can't check). Its `references` lists a declaration's
  uses, and has no answer while any of the code can't be checked, as the
  real server's whole-project check does.
- **`InkoIntegrationTest`'s code-changing tests** (changed signatures,
  introduced parameters, moved declarations, renamed setters, inlined
  methods, named arguments, specified types) run against the real
  `cuttlebone` now, and say they're skipped without one. They run on the
  EDT, where nothing waits, so they ask Cuttlebone about every name first,
  as the editor's passes would.
- **Deleted, as they only covered deleted code:** `InkoConstantTypeTest`,
  four of `InkoParameterHintsTest`'s five tests, `InkoExpressionTypeTest`'s
  nine (replaced by four of the new behaviour),
  `InkoIntegrationTest.testConstantTypesFromInkoDoc`,
  `InkoFindUsagesTest.testRenamingInstanceMethodsAlwaysShowsThePreview` (the
  preview was forced for calls of unknown type, which no longer exist), and
  one assertion of `InkoInterpolationTest` about the stated-types reading.
- **Changed by the policy, not to pass:** tests that expected a guess where
  Cuttlebone has no answer for another reason (no status, a status
  Parakeet doesn't know, a timeout) now expect nothing; those that expected
  no answer in code being edited now expect `editing`; the stale definition
  is now used for navigation.

## Requests for Cuttlebone

1. **Kept checks right after a save.** A check that read a file written
   within the clock window isn't kept, so for a moment after every save
   (IntelliJ saves often) each question is a whole check. APFS records
   nanoseconds; is two seconds needed there?
2. **References for a symbol declared outside the project**, asked at its
   declaration with the project's root, would save finding a use first.
3. **Completion after a line that doesn't parse elsewhere in the file**
   was unavailable in the probe even in another method, where `definition`
   answered from a repaired copy. Completion falls back to Parakeet's
   reading there.
4. **`signatureHelp` for constructors** (`Point(x: 1)`), still `null`.

## What's left unverified

- **An interactive IDE.** Everything ran headless, as in earlier sessions.
  I didn't see the setup notification, the conflicts dialog, possible
  usages in the Usage view, or highlights after caret moves.
- **The EDT paths.** Rename's conflicts and actions invoked on a call use
  only answers already given there. That the usage-highlighting pass's
  prefetch makes them available in a real editor follows from the code,
  untested.
- **Usage counts** wait for a whole-project `references` (up to 10 s) in
  their pass. Measured only through the search's own numbers.
- **Safe Delete, Change Signature, Inline Method, Move and Introduce
  Parameter** use fresh answers only, but unlike Rename don't list the
  places Cuttlebone can't check.
- **Extract Method** still leaves placeholders for types the code doesn't
  write; it doesn't guess, so I left it, though Cuttlebone could fill them.
- **Multiple Inko projects in one IntelliJ project** are searched per
  project root, which only the unit tests' single root exercised.
- **2025.3 at runtime, Linux, Windows, Inko 0.20.0, and CI**, which doesn't
  install Cuttlebone, as before. Only the Plugin Verifier ran against
  2025.3. CI now runs no test against a real Cuttlebone, and Parakeet
  requires one.
- **Merging hints in partly answered files** and **bundling or
  downloading Cuttlebone** were out of scope.

## The Plugin Verifier and `check.sh`

Tests: 660, from 654. `scripts/check.sh --full` passed with
`cuttlebone 0.1.0 (Inko 0.21.1)` (milestone 6) from PATH, in 1,280 s on an
Apple Silicon Mac, on this report's tree before it was committed, and again
on the final commit (below). The pre-commit hook's `check.sh` runs took
194–385 s across this session's commits (last session: 125–246 s): the
code-changing integration tests now run against the real server.

The Plugin Verifier reported the same as last session:

- **IDEA 2025.3 (253.28294.334):** "Compatible. 1 usage of deprecated API.
  11 usages of experimental API." (lsp4j's `MarkedString`, and the
  experimental usages that were there before.)
- **IDEA 2026.2.3 (262.10968.63):** "Compatible. 26 usages of deprecated
  API. 2 usages of experimental API."
- **Internal and override-only API:** none in either.
