# Session: who owns each LSP feature

IntelliJ's LSP API turns on every feature a server advertises unless the
plugin turns it off. Cuttlebone's fourth milestone added diagnostics, inlay
hints, signature help and document highlight, so inlay hints and parameter
info appeared twice. This session:

- turns every IntelliJ LSP feature off unless Parakeet decided otherwise, and
  makes the build fail when an IDE adds one
- decides an owner for each of the new features, and uses what Cuttlebone now
  answers
- pays off last session's debt: the one-second wait for `didOpen`, the
  unverified 2025.3, and Find Usages' unmeasured cost

This work is requested, not reviewed.

## Commits, oldest first

| Commit | What |
|---|---|
| c56dfaa | Every `LspCustomization` customizer set explicitly; `CuttleboneOwnershipTest` |
| 37817cd | Plugin Verifier against IDEA 2025.3, and the incompatibilities it found |
| 59c332f | Knowing when IntelliJ sent a document, instead of waiting 1 s |
| 9c0ad6e | Diagnostics stay Parakeet's; Cuttlebone's turned off |
| d6111c0 | A file's inlay hints in one request, and type hints |
| 9ad9292 | Parameter info shows Cuttlebone's signature |
| 151dc48 | Usage highlighting prefers Cuttlebone's document highlights |
| 6952a6a | Searches don't ask Cuttlebone per occurrence (Find Usages: 55 s → 1.2 s) |
| (this report) | README, CHANGELOG, the measurement's inlay-hints part, and this report |

## First: the `cuttlebone` on PATH is milestone 3

The brief said the `cuttlebone` on PATH is milestone 4. It isn't. The one on
PATH, in Cargo's `bin` directory (installed 15:37 today), advertises only
hover, completion, definition, type definition and references. It has no
diagnostics, inlay hints, signature help or document highlight, and its
`experimental.cuttlebone` has no `diagnostics` key. I found this when
IntelliJ's diagnostics showed nothing in a probe, and captured the server's
stdout to see why.

Milestone-4 builds exist only in the Cuttlebone checkout's `target/`, which
the other session was rebuilding (the debug build was rewritten at 20:21,
mid-session). I didn't install, rebuild or modify anything there:

- I copied `target/release/cuttlebone` (built 20:14, `cuttlebone 0.1.0 (Inko 0.21.1)`,
  sha256 `3deee9a124083c84…`) into my scratch directory.
- I used that copy, through `CUTTLEBONE`, for every milestone-4 result
  below.
- It was built from a tree at 7b4fe74 ("Add the edit-replay corpus"), one
  commit after the milestone-4 report, with the other session's uncommitted
  changes. So it's "milestone 4 or later", not a release.

What follows from this:

- **Parakeet checks what the server advertises** before sending
  `inlayHint`, `signatureHelp` or `documentHighlight`. With milestone 3 it
  uses its own versions, as without Cuttlebone.
- **The integration tests pass against both.** Where milestone 3 can't answer, they
  check the fallback instead, and print which.
- **`scripts/check.sh` uses PATH's milestone 3 by default.** To test the
  milestone-4 paths against the real server, set `CUTTLEBONE` to a
  milestone-4 build. I ran `check.sh --full` both ways (end of this report).

## Off by default

`CuttleboneOwnershipTest.testEveryCustomizerIsSetExplicitly` lists
`LspCustomization`'s getters by reflection (every `get…Customizer`). It
fails for any that Parakeet's customization doesn't override. 2026.2.3 has
26 of them. An IDE upgrade that adds one now fails the build instead of
quietly duplicating a feature.

### 2025.3 has 19 of the 26

The seven 2026.1 added are call hierarchy, code lens, on-type formatting,
optimize imports, rename, selection range and type hierarchy. Overriding
them names classes 2025.3 doesn't have. In one class, that would fail the
JVM's bytecode verification of the whole class on 2025.3, and every
feature would be back on. So there are two classes:

- **`CuttleboneCustomization`** sets the 19 that 2025.3 has.
- **`CuttleboneCustomization261`** extends it with the seven.
- **`create()`** makes the subclass only when `LspRenameCustomizer` exists.

How I checked it:

- **Against 2025.3's own jars,** with `java -Xverify:all`, `create()` made
  the base class, and all 19 getters returned their settings.
- **On 2026.2.3,** where the tests run, the second test checks that the
  subclass is the one created.
- **Only the subclass names those seven classes** (comments and strings
  aside). The same test checks this, because the verifier's exception
  depends on it (below).

Both classes are created as anonymous objects: `LspCustomization` is
`@OverrideOnly`, and the Plugin Verifier reports constructing a named
subclass of it.

## Who owns each feature

The rules were the same as last session:

- one owner per feature
- IntelliJ's version wins when it doesn't make things worse for the user
- Parakeet code that becomes redundant is deleted

| Feature | Owner | IntelliJ setting |
|---|---|---|
| Go to Type Declaration | IntelliJ | `LspGoToTypeDefinitionSupport` (unchanged) |
| Diagnostics | Parakeet (`inko check`) | off. Cuttlebone's are turned off too (`"diagnostics": false`) |
| Inlay hints (types and parameter names) | Parakeet, from one `inlayHint` per file | off |
| Parameter info | Parakeet, asking `signatureHelp` first | off |
| Highlight usages | Parakeet, asking `documentHighlight` first | off |
| Go to definition, completion, hover/documentation, Find Usages, Rename, … | Parakeet, as last session | off |
| Call and type hierarchy, rename, optimize imports, selection range, code lens, on-type formatting, document and workspace symbols, folding, semantic tokens, code actions, commands, formatting, colors, links | Parakeet's own versions, or nothing | off: Cuttlebone advertises none of them yet |
| Symbol kinds | n/a | IntelliJ's default `LspSymbolKindCustomizer()`, set explicitly. It has no "disabled" form, and only matters for the symbol features, which are off. |

### Diagnostics: Parakeet keeps them

**No, IntelliJ's can't replace them.** I measured it with a probe: a
temporary test, not committed, that turned IntelliJ's LSP diagnostics on
and ran the milestone-4 server. The project had `main.inko` open, with an
undefined name, and `broken.inko`, with an undefined name, never opened.
Cuttlebone's stdout was captured.

- **Cuttlebone published diagnostics for both files**, including
  `broken.inko`, without it being opened. For example:
  `{"code":"invalid-symbol","message":"the symbol 'zzz' is undefined",…}`,
  then a `fresh` status for the project.
- **In the open file, every error showed twice:** IntelliJ's (no tool ID)
  and Parakeet's (`InkoCheckAnnotator`). IntelliJ's had no quick fixes.
- **`broken.inko` wasn't marked as a problem file** within 26 s
  (`WolfTheProblemSolver.isProblemFile` stayed false), so it wouldn't turn
  red in the Project view or appear in the Problems tool window.

From IntelliJ's registrations (`intellij.platform.lsp.impl.xml`):

- **Inspect Code:** LSP diagnostics are drawn by a `highlightingPassFactory`
  (`LspHighlightingPass`) for open editors. They aren't an inspection, so
  *Code → Inspect Code* can't list them. Parakeet's
  `InkoCompilerInspection` can.
- **Quick fixes:** IntelliJ's API could add them back. `LspDiagnosticsSupport`
  has `customizeQuickFixes(diagnostic, fixes)` and `createAnnotation`, and
  `code` is the compiler's ID. Only this part of what Parakeet does would
  survive delegating.

The deciding point is that **Cuttlebone is optional.** Without it,
delegating would leave no errors at all. So the annotator, the mirror and
the project-wide check can't be deleted. A second owner while Cuttlebone runs
would need a hand-over whenever it crashes or restarts.

Since nothing reads Cuttlebone's diagnostics, Parakeet sends
`"diagnostics": false`, as Cuttlebone's docs recommend. Without it,
Cuttlebone runs a full check after every edit pause, about 0.2 s on a
std-sized project by its own report.

What delegating would have gained is one compiler pass instead of the mirror
plus `inko check`. Parakeet can't take that gain on its own: it can't read
`publishDiagnostics` through public API, because `Lsp4jClient.publishDiagnostics`
is final. See the requests below.

### Inlay hints: Parakeet owns them, and asks once per file

| | IntelliJ's LSP inlays | Parakeet's |
|---|---|---|
| Switches | one provider, "LSP-based inlays", with `NoSettings`: all on or all off | **Types** and **Parameter names**, each in *Settings → Editor → Inlay Hints* and in the hint's context menu |
| Excluding some calls or methods | no | no (declarative providers have no exclude list; neither had one before) |
| Code that doesn't parse | nothing (Cuttlebone answers `null`) | parameter names from the heuristic; no types |
| Without Cuttlebone | nothing | parameter names from the heuristic |

So Parakeet keeps them. Both providers read the same answer: one
`textDocument/inlayHint` for the whole document, cached until the PSI
changes.

- **On std's `array.inko`** (1,255 lines, 292 parentheses), it was 70 ms
  cold and 15 ms cached, for 154 hints and one request.
- **Before,** parameter hints sent one `definition` request per call, each
  a full check of its own. That was 292 checks here, held to a 2 s budget,
  so most calls got no answer in time.

When there's no fresh answer (a syntax error, or a Cuttlebone without inlay
hints), parameter hints come from the heuristic, and they **no longer ask
Cuttlebone about each call**. Those questions would fail for the same
reason, or cost a check each. Type hints need the compiler, so in broken
code they disappear until it parses again. Parakeet never had them, so
nothing that worked before goes dark.

Tests:

- `CuttleboneFeaturesTest.testHintsComeFromOneRequestForTheWholeFile`: one
  request, cached, and no `definition` requests
- `…testWithoutAnAnswerParameterHintsAreParakeetsAndNothingIsAskedPerCall`
- `CuttleboneIntegrationTest.testHintsForTheWholeFile`: with the real
  server, `index:`, `default:`, `scale:`, `: B` and `: Array[Int]`
- `…testWithASyntaxErrorHintsFallBackToParakeet`: a file that doesn't parse
  still gets `value:` and `default:` from the heuristic

### Parameter info: Parakeet owns it, and asks `signatureHelp`

Delegating would go dark where Parakeet's still works:

- a constructor call `Point(x: 1)`, where Cuttlebone answers `null` (Parakeet
  shows the fields)
- code that doesn't parse elsewhere in the file (Cuttlebone's inserted `)`
  only helps at the call being typed)
- no Cuttlebone at all

So Parakeet's handler asks `signatureHelp` first. With a fresh answer, it
shows Cuttlebone's one signature, for example `fn pub size(scale: Float) -> Int`.
The receiver's type arguments are filled in, and the current parameter
follows Cuttlebone's `activeParameter`, which is right for named arguments.
Otherwise it shows the `inko doc` candidates and counts commas, as before.

**Last session's open question is answered:** IntelliJ calls the handler
in non-blocking read actions on a background thread (`ShowParameterInfoHandler`
and `ParameterInfoController` use `ReadAction.nonBlocking`), so it can wait
for the answer (1.5 s budget).

The real server answered `fn pub size(scale: Float) -> Int` for `x.size(`
with no `)` typed yet (`testParameterInfoHasTheCompilersSignature`).

### Document highlight: IntelliJ does support it

**The brief was wrong here.** IntelliJ's LSP API does support document
highlight:

- `LspDocumentHighlightsCustomizer` exists in 2025.3 and 2026.2.
- `intellij.platform.lsp.impl` registers an `LspHighlightUsagesHandlerFactory`.
- JetBrains' docs list "Highlight Usages" under 2025.3.

So it needed a setting, and it's off (c56dfaa).

IntelliJ's version would go dark in broken code and without Cuttlebone, so
Parakeet keeps usage highlighting, and now prefers Cuttlebone's answer:

- **Matched by the compiler's ids,** so `x.size` on an `A` and on a `B`
  aren't highlighted together, nor a field, an argument and a variable that
  share a name.
- **Assignments count as writes** (`total = 1`), which Parakeet's
  heuristic can't tell from reads. Write and Text (declarations) are shown
  as writes; Read as reads.

The real server marked `let mut total` and `total = 1` as writes, and
`total + 2` as a read (`testUsageHighlightingKnowsAssignments`).

The identifier highlighting pass is a daemon pass, where Parakeet's
questions normally use cached answers only. If it did that here, Cuttlebone's
answer would only show on the next caret move. So this question waits up
to 0.4 s, like resolve. Moving the caret cancels the pass, and
`$/cancelRequest` goes with it. This is one of two exceptions to
"highlighting never waits"; inlay hints are the other.

The refactorings that use `InkoUsages.inFile` (Change Signature, Inline
Variable) still use the heuristic, because they edit PSI elements.

## What was deleted

- `InkoParameterHints.prefetch`, `CuttleboneQueries.prefetchDefinitions` and
  `awaitAll`: the per-call bulk definition requests for hints
- `CuttleboneService.editorsSynced`, `initializedAt` and `SYNC_GRACE_MS`:
  the one-second grace period
- `CuttleboneQueries.isSynced`: replaced by the per-document check below

No diagnostics code was deleted, for the reason above.

## Last session's debt

### `didOpen`: known, not waited for

IntelliJ tells listeners about `didOpen` only through internal API. But the
descriptor's `startServerProcess()` is public and open. Parakeet now returns
IntelliJ's own process handler (the same class and reader options as
IntelliJ's default), with one difference: `getProcessInput()`, the stream
IntelliJ writes to the server through, is wrapped by `CuttleboneInputTap`.

- **The tap** passes every byte on. When a whole message has been written,
  it records the document version that `didOpen` or `didChange` carried,
  and forgets it at `didClose`.
- **A document is synced** when the version sent is its current one,
  `LspServer.getDocumentVersion(document)` (public, and what IntelliJ
  sends).
- **A document never sent** counts as synced when it's saved, since
  Cuttlebone reads it from disk.

This is exact where the grace period guessed:

- An edited file can be asked about about 25 ms after the server starts,
  instead of 1 s.
- A `didChange` that hasn't gone out yet is caught too, which the grace
  period missed.

`CuttleboneClientTest.testAnEditedFileIsAskedAboutAsSoonAsItsTextIsSent`
failed with the grace period. `CuttleboneInputTapTest` covers messages
split anywhere, several messages in one write, and non-ASCII bodies.

### IDEA 2025.3 in the Plugin Verifier: 0.11.0 wasn't compatible

`build.gradle.kts` verifies against IDEA 2025.3 (253.28294.334, the first
2025.3) and 2026.2.3. The first run found that **0.11.0 doesn't work on
2025.3**, which `sinceBuild` 253 claims. Compiled against 2026.2, it had
five problems:

- **`CuttleboneQueries.target`** called `LspServer.getDescriptor()` with
  2026.2's return type, `LspClientDescriptor`, which 2025.3 doesn't have. It
  would throw `NoSuchMethodError` for every location Cuttlebone returns. It
  now finds the file from the URI itself.
- **Kotlin's compatibility stubs:** it added overrides of 2026.1's new
  default methods to `CuttleboneSupportProvider`, which call methods 2025.3
  doesn't have (three problems). `@JvmDefaultWithoutCompatibility` stops it.
- **The plugin's version for error reports:**
  - 2025.3 marks `ErrorReportSubmitter.getPluginDescriptor` override-only.
  - 2026.2 marks `PluginManager.getPluginByClass` and
    `PluginManagerCore.getPlugin` internal.
  - So the build writes the version into a resource, and
    `InkoIssueReportTest.testReportsThePluginsVersion` checks it.

**`verification/ignored-problems.txt` has one entry:** the seven 2026.1-only
customizer classes, which only `CuttleboneCustomization261` names. It's
never created on 2025.3 (see above), and `CuttleboneOwnershipTest` fails if
anything else names them. The verifier matches the problem's short
description ("Access to unresolved class …"), which doesn't say which
class refers to it. That's why the test is needed.

**The deprecated `LspServer*` names stay.** They work in both IDEs, and the
`LspClient*` ones don't exist in 2025.3.

### Find Usages: 55 s with Cuttlebone, now 1.2 s

I measured on std 0.21.1's own `src/` and `test/` as the project: 222
files and 92,777 lines, taken with `git archive v0.21.1` from the Inko
checkout, which I read only. The query was Find Usages of `Array.size`
(`ReferencesSearch` over the project scope, in the background, as IntelliJ
runs it).

| | Run 1 | Run 2 | Run 3 | Usages | Requests |
|---|---|---|---|---|---|
| Without Cuttlebone | 2,401 ms | 1,268 ms | 1,169 ms | 344 | 0 |
| With Cuttlebone, before | **54,869 ms** | 14,520 ms | 6,603 ms | 321, 150, 127 | 1,139, 0, 0 |
| With Cuttlebone, after (6952a6a) | 1,298 ms | 1,141 ms | 1,099 ms | 344 | 0 |

Why it was slow:

- Resolving each candidate occurrence sent a `definition` request and
  waited up to 0.4 s, and Cuttlebone answers one at a time.
- Requests that ran out of budget carried on, so runs 2 and 3 waited on the
  queue, and their results changed as answers arrived.
- While the queue lasted, every hover and completion waited behind it.

**The fix:** searches use Cuttlebone's cached answers only. That covers
`InkoReference.isReferenceTo` (which Find Usages, Rename, Call Hierarchy,
usage counts and Safe Delete use) and the Usage view's "possible usage"
check. Go to definition and everything else still ask.

**The cost:** searches give the results they give without Cuttlebone,
including "possible usages" for receivers of unknown type, except where an
answer is already cached. Getting last session's exactness for searches
without one check per occurrence needs either `textDocument/references`
(out of scope) or a cheaper Cuttlebone (request 1).

`FindUsagesMeasurementIntegrationTest` repeats all of this, and the inlay
hints measurement above, when `PARAKEET_MEASURE_STD` names std's directory.
Otherwise it's skipped, so `check.sh` doesn't run it.
`CuttleboneFeaturesTest.testFindUsagesNeverAsksPerOccurrence` failed before
the fix.

## Requests for Cuttlebone

1. **Cheaper repeated questions.** Every query type checks from scratch, so
   N questions cost N checks, run one at a time. Find Usages over a common
   name was 1,139 of them. Any of these would let searches be exact again:
   - a typed program cached per document version
   - a batch `definition` for many positions in one document
   - `references` (out of scope here)
2. **`stale` answers in code that doesn't parse** (still reserved in
   docs/lsp.md). Type hints, the compiler's parameter names, signatures and
   highlights all disappear while a file has a syntax error.
3. **Pull diagnostics** (`textDocument/diagnostic`, or `workspace/diagnostic`
   for every file). IntelliJ plugins can't read `publishDiagnostics` through
   public API (`Lsp4jClient.publishDiagnostics` is final). With a request,
   Parakeet could take Cuttlebone's diagnostics while it runs, instead of
   mirroring the project and running `inko check`: one compiler pass instead
   of two.
4. **For docs/lsp.md's section on clients that turn everything on:** in
   IntelliJ, `publishDiagnostics` for files that aren't open doesn't mark
   them as problem files (measured above), so "keep the diagnostics of files
   that aren't open" doesn't happen there.
5. **`signatureHelp` for constructors** (`Point(x: 1)`), which is `null`
   now. Parakeet shows the fields from `inko doc` instead.
6. Last session's requests 4 (the type of a range), 5 (the position in
   `cuttlebone/status`) and 6 (`textDocument/implementation`) are still
   open.

## What's left unverified

- **An interactive IDE.** As last session, everything ran headless. I didn't
  watch hints render, parameter info pop up, or usage highlights follow the
  caret.
  - The tests call the hint functions, the parameter info handler and the
    highlight handler directly, on background threads, as IntelliJ does.
    They don't run the declarative collectors through the editor's
    rendering. `InkoParameterHintsTest` checks both providers are
    registered for Inko.
- **Waiting in the identifier highlighting pass.** That the pass waits, and
  that moving the caret cancels it, follows from how Parakeet's queries
  handle `ProcessCanceledException` (tested last session). I didn't watch it
  in an editor.
- **2025.3 at runtime.** The Plugin Verifier and the `-Xverify:all` check of
  the customization ran against 2025.3. The tests didn't, so the stdin tap,
  the descriptor and the rest of the client never ran there.
- **The milestone-4 binary** is a snapshot of an unreleased build from a tree
  another session was changing. Its answers matched docs/lsp.md where tested.
- **A big project's inlay hints and usage highlighting** cost what one query
  costs (about 100 ms on a std-sized project, by Cuttlebone's numbers), but
  weren't measured beyond `array.inko`.
- **Linux, Windows, Inko 0.20.0** (as before), and **CI**, which doesn't
  install Cuttlebone and so never exercises any of the milestone-4 paths.
- **The two unverified bugs in SESSION-audit-fixes.md** were out of scope,
  and are untouched.

## The Plugin Verifier and `check.sh`

Tests: 635, up from 617. I ran `scripts/check.sh --full` on the final tree
twice, on an Apple Silicon Mac:

| Run | Cuttlebone | Result | Time |
|---|---|---|---|
| `CUTTLEBONE=<the milestone-4 copy> scripts/check.sh --full` | milestone 4 or later | passed | 367 s |
| `scripts/check.sh --full` | PATH's (milestone 3) | passed. The three milestone-4 integration tests printed that they checked Parakeet's fallback instead | 518 s |

The pre-commit hook's `check.sh` runs took 125–246 s across this session's
commits (last session: 111–158 s).

The Plugin Verifier reported:

- **IDEA 2025.3 (253.28294.334):** "Compatible. 1 usage of deprecated API.
  11 usages of experimental API." The deprecation is lsp4j's `MarkedString`,
  as last session. The experimental usages were there before, and only
  2025.3 marks them.
- **IDEA 2026.2.3:** "Compatible. 26 usages of deprecated API. 2 usages of
  experimental API." That's down from 30. The `LspServer*` names make up
  most of them, and stay while 2025.3 is supported.
- **Internal API:** neither IDE reports any.
- **Override-only API:** neither IDE reports any. 2025.3 used to report the
  error reporter's.
