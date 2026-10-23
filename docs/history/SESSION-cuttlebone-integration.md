# Session: connecting to Cuttlebone

Parakeet now asks Cuttlebone, a language server for Inko, wherever it used
to guess at types or targets, and keeps its guesses as the fallback. It
talks to Cuttlebone through IntelliJ's own LSP API
(`com.intellij.modules.lsp`), which owns the process, document sync and
cancellation.

Without Cuttlebone, Parakeet behaves exactly as before: every existing
test runs with no server, and all of them pass unchanged.

This work is requested, not reviewed.

## Commits, oldest first

| Commit | What |
|---|---|
| 1888b96 | The client: settings, the LSP descriptor, the status notification, `CuttleboneQueries` (budgets, cache, cancellation), restarts, the fake server and its tests. `sinceBuild` 253. |
| 92f9415 | Resolve, completion, parameter info and hints, documentation and Expression Type prefer Cuttlebone's answers |
| d5cc723 | Integration tests against the real `cuttlebone`, `check.sh` gating, and a fix for a race the new tests found |
| e224b2b | README and CHANGELOG |
| (this report) | Public API only (below), this report, and its README link |

Tests: 617, up from 587 (13 client tests and 8 feature tests with the fake
server, and 9 integration tests with the real one).

## Who owns each feature

IntelliJ's LSP API can run most features itself. Each feature has one
owner, so nothing produces results twice (no duplicate completion items, no
target choosers). The rule I followed:

- IntelliJ's version wins when Parakeet has nothing to lose.
- Parakeet keeps a feature when IntelliJ's version would go dark where
  Parakeet's heuristic still works. Parakeet's version then asks Cuttlebone
  first.

| Feature | Owner | Why |
|---|---|---|
| Process, restarts, document sync, `$/cancelRequest`, shutdown | IntelliJ | Nothing in Parakeet did this. Restart backoff is Parakeet's, on IntelliJ's API (below). |
| Go to Type Declaration | IntelliJ (`LspGoToTypeDefinitionSupport`) | Parakeet had none, so nothing goes dark that worked before. |
| Go to definition / resolve | Parakeet, asking `textDocument/definition` | IntelliJ's version is an implicit reference beside Parakeet's PSI reference, so both would produce targets (a chooser). Find Usages, Rename, Call Hierarchy and the inspections are built on Parakeet's references and must stay. And Cuttlebone answers `null` with a syntax or declaration error, where the heuristic still resolves. |
| Completion | Parakeet, asking `textDocument/completion` after `.` | Evidence below: a syntax error anywhere in the file makes Cuttlebone's completion `unavailable` (`noHir`). Parakeet's still lists members. Parakeet also completes keywords, locals, imports and unimported names, which Cuttlebone doesn't. |
| Quick documentation | Parakeet, plus hover's type | IntelliJ's hover would show only the type, as a second documentation target beside Parakeet's signature and doc comment. It would also go dark in broken code. |
| Expression Type | Parakeet, plus hover's type | IntelliJ has no Expression Type for LSP. Hover answers for the innermost expression at a position, so Parakeet uses it only when hover's range is exactly the chosen expression. |
| Parameter info and hints | Parakeet, through resolve | Cuttlebone has no `signatureHelp` or `inlayHint`. |
| Find Usages, Rename, refactorings, imports, inspections | Parakeet (PSI), fed through resolve | As asked. IntelliJ's references are off (`LspFindReferencesDisabled`). |
| Diagnostics | Parakeet (`inko check`) | Cuttlebone reports none. IntelliJ's are off. |
| Semantic tokens, formatting, code actions | off | Cuttlebone advertises none of them. They're disabled explicitly anyway, so a future server capability can't add a second owner by surprise. |

**No Parakeet code became redundant.** Before this session, Parakeet had
no LSP client or server process code. Every heuristic is still the
fallback when Cuttlebone has no answer, so none can be deleted.

### Evidence for keeping completion

The real `cuttlebone` 0.1.0, on `x.` in a file with `fn broken( {` further
down:

```
cuttlebone/status: {"status": "unavailable", "reason": {"code": "noHir",
  "message": "the file or a module it imports couldn't be parsed or found, so nothing was type checked"}}
result: null
```

On the same file without the broken function, it's `fresh`, with
`[{"label": "size", "detail": "fn pub size(scale: Float) -> Int", ...}]`.

`CuttleboneIntegrationTest.testWithASyntaxErrorElsewhereCompletionFallsBackToParakeet`
checks both halves:

- Cuttlebone has no answer.
- Parakeet's completion still offers `size`.

Typing `x.` usually happens while something else in the file is
half-written, so delegating completion would often show nothing.

## How it works

### The client (`Cuttlebone.kt`)

- **Settings:** *Settings → Languages & Frameworks → Inko → Cuttlebone →
  Executable*. Empty means `cuttlebone` on `PATH`, then Cargo's `bin`
  directory (where ivm is also looked for). The page shows the status.
- **Lazy start:** IntelliJ calls `CuttleboneSupportProvider.fileOpened`
  when an Inko file in the project opens. The first call runs a precheck
  on a pooled thread; the server starts after it passes.
- **The precheck:** is there an executable, and an `inko`?
  `cuttlebone --version` prints the Inko version it links, which is
  compared with `inko --version`. A missing executable or a version
  mismatch never starts a process, so IntelliJ never reports an error for
  it.
- **Initialization options:** `inko` (the project toolchain's
  executable) and `statusNotifications: true`. The client offers only
  `utf-16` as the position encoding, which is IntelliJ's offsets.
- **`cuttlebone/status`:** received by a `Lsp4jClient` subclass. It's
  matched to its response by method and URI. lsp4j handles the
  notification and then the response on its reader thread, in order. A
  `thenApply` on the request's own future runs on that thread as the
  response arrives, so it takes the status belonging to that response.
- **Saying so once:** a status change (not found, refused, crashed,
  running) is logged to `idea.log` once and shown on the settings page.
  There are never popups.
- **Restarts:** IntelliJ reports a crash through the descriptor's
  `LspServerListener.serverStopped(shutdownNormally = false)`. A server that
  crashes after initializing is restarted with `stopAndRestartIfNeeded`. (`startServersIfNeeded` doesn't replace
  a crashed server; the test caught that.) The restarts wait 1, 2, 4, 8 and
  16 s, up to 5 in a row, then stop until the settings change.
- **A refused `initialize`:** a server that stops before initializing
  refused the session, so it isn't retried.
- **Shutdown:** IntelliJ's LSP API sends `shutdown` and `exit` when the
  project closes. A test checks the same path through `stopServers`.

### Asking questions (`CuttleboneQueries.kt`)

- **Only fresh answers count.** Anything other than a `fresh` status means
  no answer, and so does a fresh `null` (nothing resolves there). The
  caller then uses its heuristic.
- **Budgets:**
  - resolve: 0.4 s
  - completion, documentation and Expression Type: 1.5 s
  - parameter hints: 2 s in all, for the whole file at once
- **Out of budget:** the request carries on, and its answer is cached for
  the next call.
- **Caching:** answers are kept per server process, method, document and
  offset, until the project's PSI modification count changes. That covers
  any edit, in this file or a module it imports. Requests in flight are
  shared, so asking again never sends a duplicate.
- **What never waits:** highlighting passes (a `DaemonProgressIndicator`),
  the EDT and write actions. They use cached answers only.
- **Parameter hints:** the hints pass is a highlighting pass, so it asks
  about every call in the file first, then waits for the answers together.
- **Cancellation:** each request runs IntelliJ's `sendRequestSync` on a
  pooled thread, under a progress indicator of its own. When IntelliJ
  cancels whatever is waiting (a `ProcessCanceledException`), Parakeet
  cancels that indicator, and IntelliJ sends `$/cancelRequest`.
  - The request must hand IntelliJ lsp4j's own future. A `thenApply`
    copy is cancelled without telling lsp4j. My first version did that,
    and the cancellation test caught it.
  - IntelliJ's coroutine `sendRequest` doesn't send `$/cancelRequest`
    when its coroutine is cancelled (tested), so it isn't used.
- **Unsaved text:** a file with unsaved changes is only asked about when
  it's open in an editor and the server initialized at least 1 s ago (the
  race below). Saved files are read from disk by Cuttlebone, so they can
  always be asked about. Unsaved files that aren't open in an editor are
  never asked about.
- **Mapping answers:** a returned location is mapped to Parakeet's own
  element at that offset: the declaration's name, or the file for a
  module's empty range at 0:0. Then it's matched to the `inko doc`
  definition whose name is written there (`InkoResolver.definitionAt`).
  Names never decide which symbol is meant.

### The race the tests found, and only public API

`CuttleboneClientTest.testAnEditReachesTheServerBeforeTheNextQuery` failed
against the first version. The fake received
`[initialize, initialized, textDocument/definition, textDocument/didOpen]`.

IntelliJ reports the server as running before it sends `didOpen`. A query
on an edited file in that gap was answered from the text on disk.

- **First fix (d5cc723):** wait for `LspServerManagerListener.fileOpened`.
- **Why it was replaced:** `check.sh --full`'s Plugin Verifier then
  reported `LspServerManagerListener` and
  `LspServerManager.addLspServerManagerListener` as `@ApiStatus.Internal`.
  The listener was also how crashes were detected.
- **What IntelliJ does, measured with a probe:**
  - it calls the provider's public `fileOpened` before the server exists
  - it sends `didOpen` for the files open in editors about 25 ms after
    `initialized`
  - it sends `didOpen` for later files as they open, and those are
    unmodified when they open
- **The final version uses public API only:**
  - Crashes come from `LspServerListener.serverStopped`.
  - An edited file counts as synced once the server has been initialized
    for 1 s.
  - The cost: edited files fall back to guesses for that first second.
  - The race test checks both halves: no question before, and `didOpen`
    and `didChange` reach the server before the question after.
- **The verifier's internal-API report** has no usage from this session's
  code after the change (see the end of this report).

## Latency

Measured in the headless test IDE (the only IDE I could run), with the
real `cuttlebone` 0.1.0 and `inko` 0.21.1, on an Apple Silicon Mac. The file
is small: `main.inko` and a 2-type module, plus std's prelude.
`CuttleboneIntegrationTest.testLatency` prints them.

| Question, as Parakeet sees it | Time |
|---|---|
| Resolve a name, cold (includes the definition request) | 42, 86, 48 ms |
| Hover, cold | median 38 ms (36–39) |
| Type definition, cold | median 39 ms (37–41) |
| Any of them, cached | 0.2–1.4 ms |
| `cuttlebone` over raw stdio, outside the IDE, same file | 38–46 ms per query |

So IntelliJ's LSP layer and Parakeet's mapping add almost nothing. The
check is the cost. Cuttlebone's own reports put a file importing all of std
at about 120 ms. That's within resolve's 0.4 s budget, though a hover
queued behind other checks may not be.

Not measured:

- typing in a large project in an interactive IDE
- Find Usages over many files (see the risks below)

## How the fallback behaves

| Situation | What happens |
|---|---|
| No `cuttlebone` | Status "Cuttlebone isn't installed", no process, every feature guesses as before. Tested. |
| A different Inko version | Precheck refuses: "cuttlebone … works with Inko 0.19.0 only, but the project uses Inko 0.21.1." No process. Tested. |
| `initialize` refused anyway | Status "refused", not retried, opening more files doesn't retry. Tested. |
| Crash | Restart with backoff; questions in the meantime get no answer. Tested (one crash). |
| Hang | Each question gives up at its budget (300 ms in the test returned within 1.5 s). Tested. |
| Slow reply | The question falls back; the late answer is cached and used next time without asking again. Tested. |
| `unavailable` (syntax error, declaration error, timeout, …) | The heuristic, unchanged. Tested with the fake and, for completion, with the real server. |
| Cancelled by IntelliJ | `$/cancelRequest` reaches the server. Tested. |

## Requests for Cuttlebone

None of these are worked around in Parakeet.

1. **Answers in code that doesn't parse, or has a declaration error** (the
   `stale` status docs/lsp.md reserves). This is the main reason Parakeet
   keeps its heuristics, and the reason completion can't be delegated.
2. **One check for many questions.** Each query type checks from scratch
   (~40 ms here, ~120 ms with all of std). Parameter hints need one
   definition per call, so a file with 100 calls costs 100 checks. Either
   of these would fix it:
   - caching the typed program per document version, so queries between
     edits share one check
   - a whole-file request: `textDocument/inlayHint` with parameter names
     (and types), or `textDocument/semanticTokens`-style output
3. **`textDocument/signatureHelp`**, with the receiver's type arguments
   filled in, as completion's `detail` already does. IntelliJ's LSP API
   could then own parameter info, and it would show
   `get(index: Int) -> Result[ref Int, OutOfBounds]` instead of `inko doc`'s
   `Result[ref T, OutOfBounds]`.
4. **The type of a range, not just a position.** Expression Type asks
   about a chosen expression, but hover answers for the innermost one.
   Parakeet asks at two positions and only accepts an exact range match.
   A way to ask for "the expression with exactly this range" would make
   Expression Type exact for every candidate (for example
   `textDocument/hover` accepting a range, as some servers do, or a
   documented custom request).
5. **The position in `cuttlebone/status`.** The notification has the
   request's `id`, but lsp4j-based clients (IntelliJ's included) never see
   ids. Parakeet matches by method and URI, which relies on lsp4j calling
   the handler in order. Echoing `position` would let clients match
   exactly.
6. **`textDocument/implementation`**, for trait methods. It's needed for
   Find Usages and Go to Implementation through Cuttlebone (next section).
7. **Diagnostics** (`publishDiagnostics`). Parakeet mirrors the project and
   runs `inko check` for errors. One compiler pass for both would halve
   the work.

## Find Usages and Rename through `textDocument/references` (out of scope)

What it would take:

- **Trait methods.** References are matched by symbol id, so a trait
  method's references don't include calls to its implementations, and an
  implementation's don't include calls through a `T: Trait` bound. Find
  Usages (and above all Rename) on a trait method would have to:
  - ask for references to the trait method
  - find every implementation, from `inko doc`'s implementations (Type
    Hierarchy's data) or a future `textDocument/implementation`
  - ask for references to each, and take the union
  - rename all the declarations together
- **Errors anywhere in the project.** References check the whole project,
  so a syntax or declaration error anywhere makes them unavailable. The
  current word-index search would have to stay as the fallback, and the
  "possible usage" marking with it.
- **Scope.** References only search the project's `src/` and `test/`.
  That's enough for Rename, since std and dependencies can't be renamed,
  but Find Usages on a std method would still need the index.
- **Cost.** ~270 ms for std-sized projects per request, uncached.

## What's left unverified

- **An interactive IDE.** Everything ran headless (`BasePlatformTestCase`
  with IntelliJ's real LSP client). I didn't run `./gradlew runIde` or
  watch typing, Ctrl-click, completion or the status bar widget by hand.
- **Parameter info's thread.** If IntelliJ calls
  `findElementForParameterInfo` on the EDT, it only sees cached answers
  (usually there, from the hints pass). I didn't check which thread it
  uses. Expression Type, documentation and completion run in the
  background (Expression Type's handler uses a non-blocking read action).
- **Find Usages' cost.** Resolve now waits up to 0.4 s per name that isn't
  cached, and Find Usages resolves every candidate occurrence. On a
  common method name across a large project that could be seconds slower
  than before, though more exact. I didn't measure it.
- **Changing the toolchain or executable in the settings** restarts
  Cuttlebone (`settingsChanged`). This isn't tested.
- **The oldest supported IDE.** `sinceBuild` is now 253, and the Plugin
  Verifier only checks 2026.2.3, as asked (IDEA Community 2025.2 was
  dropped). Nothing checks 2025.3 itself.
- **More than one crash in a row,** and the restart limit, are untested.
  Only one crash and its restart are.
- **Files outside the project's content** (std files opened from External
  Libraries) aren't synced by IntelliJ's LSP client. They're saved, so
  Cuttlebone reads them from disk, but questions asked from them weren't
  tested.
- **Linux, Windows, and Inko 0.20.0.** Nothing ran there. CI skips
  Cuttlebone's integration tests (`-PnoCuttlebone`), since it doesn't
  install Cuttlebone.
- **`check.sh`'s skip and require paths.** These were run in isolation
  with an empty `HOME` and a bare `PATH`. They skip with the message, and
  `--full` exits 1 with its message. Gradle's `-PnoCuttlebone` exclusion
  was checked separately. A whole `check.sh` run without `cuttlebone` on
  this machine wasn't done, since it's installed.
- **The two unverified bugs in SESSION-audit-fixes.md** were out of scope
  and are untouched.

## The Plugin Verifier

`check.sh --full` on the final tree, against IntelliJ IDEA 2026.2.3:
"Compatible. 30 usages of deprecated API. 2 usages of experimental API."
There are no internal API usages.

**28 of the deprecated usages are this session's,** and they're one thing:
2026.2 deprecates the whole `LspServer*` family in favour of `LspClient*`
names. The deprecated classes are `LspServerSupportProvider`,
`ProjectWideLspServerDescriptor`, `LspServerManager` and `LspServer`.

I kept the `LspServer*` names, because `sinceBuild` is 253 and I couldn't
check that the `LspClient*` ones exist in 2025.3 without downloading it. If
they do, the migration is a rename:

- `LspIntegrationProvider`
- `ProjectWideLspClientDescriptor`
- `LspClientManager`
- `LspClient`

The 29th is lsp4j's `MarkedString`, which `InkoHover` reads for servers that
send the older hover format. The other 2 deprecations, and both
experimental usages, were there before.

## How long `check.sh` takes now

On this machine, with the 617 tests:

| Run | Time |
|---|---|
| `scripts/check.sh` (from the pre-commit hook, across this session's commits) | 111–158 s |
| `scripts/check.sh --full`, final tree | 199 s |

That's up from 78–89 s and 150 s last session. Most of the difference is
`CuttleboneClientTest` (about 35–40 s), which starts a JVM for each launch
of the fake server.
