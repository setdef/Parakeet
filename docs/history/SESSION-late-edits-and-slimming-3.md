# Session: late edits, and slimming Parakeet, part 3 (Cuttlebone 0.15.0)

Two things, in this order. First, the real-world sweep's "refactorings
whose edits land late" ([the last session's final check](SESSION-slimming-2.md#the-final-check)):
a site reported "refused, but changed the code", or "changed nothing" with
the change then appearing during the next site. Then slimming with
Cuttlebone 0.15.0 (its docs/history/MILESTONE-15.md, "What Parakeet can now
delete").

This work is requested, not reviewed. Everything is under Unreleased;
nothing is tagged, published or pushed.

## The numbers

`src/main`'s Kotlin (`find src/main -name '*.kt' | xargs cat | wc -l`):

| | Lines |
|---|---|
| Before (d59d4aa, the last session's report) | 14,722 |
| After (ae04b2f) | 14,506 |
| Net | **−216** |

| Commit | What | `src/main` |
|---|---|---|
| 780bc3e | Wait for saved files to reach the disk before asking Cuttlebone (the late edits, in Parakeet) | +19 |
| 76a0c0f | Wait for the disk after the sweep saves (the late edits, in the sweep) | ±0 |
| f5fba9d | Require Cuttlebone 0.15.0 | ±0 |
| ccc31a2 | Delegate quick fixes (`textDocument/codeAction`) | −126 |
| d9b394b | Delegate completion of names, imports and keywords (`textDocument/completion`) | −17 |
| 14bea11 | Delegate the Structure view, breadcrumbs and sticky lines (`textDocument/documentSymbol`) | −89 |
| ae04b2f | Delegate a constructor's parameter info (`textDocument/signatureHelp`) | −3 |
| (this commit) | This report and README's link to it | 0 |

## 1. The late edits

### What they were

IntelliJ 2026.2 returns from saving a document before the disk has it. With
`vfs.async-content-write.enabled` (on by default; `AsyncableFileSystemWrapper`
in `intellij.platform.ide.impl`), a save updates the document and the VFS's
cached content at once, and a background task writes the file. IntelliJ's
own run configurations, terminal and Git wait for those writes
(`ManagingFS.flushPendingUpdates`, an experimental API new in 2026.x) before
something else reads the disk. 2025.3 has none of it and writes before a
save returns.

The sweep reads each project's copy from disk after saving every document.
So, under load:

- A site's edits weren't on disk yet when the sweep looked: "changed
  nothing". Its `restore()` then saw the original on disk and returned
  early, leaving the document changed and saved as far as IntelliJ knew.
  The write landed during the next site, which was blamed: kvi's "Safe
  Delete at `ReaderWriter`: refused, but changed the code" with `out`
  renamed, and yorickpeterse.com's "Rename at `request`: refused, but
  changed the code".
- Or the next site found the left-over edit in the PSI, so a declaration
  wasn't at the offset it was sampled at: inko-builder's
  NullPointerException in the sweep's `declarationAt`.

How it was found: a check in the sweep (not committed) after each save
compared each document with its file and with the VFS's cached content. In
inko-builder, after "Rename at `indent?` (src/builder/xml.inko:427): changed
nothing", the document and the VFS both had `indent_renamed?`, the
document counted as saved, and the disk had the original. IntelliJ's own
`isSaveNeeded` compares the document with the VFS's content, so no later
save of that document would write it. The disk then caught up with no save
in between. `PARAKEET_PROTOCOL`'s `disk.jsonl` showed the same from the
outside: `indent_renamed?` reached the disk only together with the next
site's `escape_renamed`. It reproduced in 2 of 5 runs of the kvi,
inko-builder and yorickpeterse.com sweeps (Rename and Safe Delete) at a
load average of about 25, and never with the fix.

### Whose it was

Both. The refactorings were complete when they returned, and no refusal
edited: every edit was in the documents during the refactoring call.
What lagged was IntelliJ's write to disk. The sweep reads the disk, so that's
its measurement (fixed in 76a0c0f). But Parakeet reads the disk too, through
Cuttlebone: Cuttlebone reads the files no editor has from disk, and Parakeet
counts such a file as Cuttlebone's once it's saved
(`CuttleboneService.isSynced`). So right after a refactoring changed and
saved one, a question could be answered for the text before the change, as
fresh: a correctness bug in Parakeet on 2026.2 (fixed in 780bc3e).

### The fixes

- **780bc3e**: each request to Cuttlebone first waits for IntelliJ's
  pending writes (`ManagingFS.flushPendingUpdates`, found by reflection:
  2025.3 has no such method). `testASavedFileIsOnDiskBeforeTheNextQuery`
  edits and saves a 4 MB module no editor has twenty times, asking
  Cuttlebone after each, and checks the disk as each request is sent.
  Without the wait, 3 of 3 runs found an older text on disk (edits 7 and 10,
  say). With it, 3 of 3 passed. A first version had the fake Cuttlebone read
  the file when the request arrived, and passed without the fix: the
  write's lag is short (5 of 20 saves weren't on disk right after
  `saveDocument`), so it's checked where Parakeet sends.
- **76a0c0f**: the sweep's `saveAll()` waits for the disk too, and so does
  `restore()` after saving a file it puts back, before it reads it.

## 2. Slimming with Cuttlebone 0.15.0

### The tools

- `inko 0.21.1`, from ivm.
- `cuttlebone 0.15.0 (Inko 0.21.1)`, passed to every check as `CUTTLEBONE`.
  The latest release's `manifest.json` said 0.14.0 until after the last code
  commit. The local build at
  `../cuttlebone/target/release/cuttlebone` was 0.15.0 at the start, but the
  session building 0.16.0 rebuilt it during this one: the first hook run of
  780bc3e reported `cuttlebone 0.16.0`, so it was stopped (its hook put the
  tree back) and rerun. From then on, every check used 0.15.0's release
  build, `../cuttlebone/dist/cuttlebone-0.15.0-inko-0.21.1-aarch64-apple-darwin.tar.gz`
  (SHA-256 88c6ca26…6d25423), extracted into this session's scratch
  directory. By the final check the manifest said 0.15.0, so that ran
  against the published release, downloaded into the scratch directory by
  `ci/install-cuttlebone.sh` (checked against the manifest's size and
  SHA-256), a different binary from the same version. Cuttlebone wasn't
  installed or changed, and Cargo's `cuttlebone.aside` stayed where it was.
- Commits were made from a worktree of this repository in the scratch
  directory, while the work went on in others; every commit ran the hook,
  none bypassed it. The machine ran at load averages of about 25 to 900 on
  10 cores.

### Cuttlebone 0.15.0 or newer (f5fba9d)

`Cuttlebone.MINIMUM` is 0.15.0, refused and offered exactly as 0.14.0 was.
The tests' fixtures moved up a minor version, as the last session's did, so
the four minimum tests test 0.14.0 against 0.15.0; they, and two whose
fixtures then named 0.14.x releases the old minimum took, failed before the
change, and all 69 download and client tests pass after it. Nothing in
`src/main` was there only for 0.14.0.

### Quick fixes: `textDocument/codeAction` (ccc31a2)

Deleted: `InkoImportFix`, `RemoveUnusedImportFix`, `PrefixUnderscoreFix`,
`DiscardResultFix`, `AddMissingFieldFix`, `InkoImplementMethodsFix`,
`ImplementMissingTraitFix`, `RemoveUnreachableFix`, `InkoDocumentFix`, and
the parsing of diagnostic messages behind them.

Replaced by: a lazy quick fix (`AnnotationBuilder.withLazyQuickFix`) on each
diagnostic whose ID Cuttlebone fixes, which IntelliJ computes in the
background after highlighting, for the diagnostics on screen:
`CuttleboneQueries.codeActions` for the diagnostic's range, fresh only, and
a `CuttleboneFix` per action for that diagnostic, offered by Cuttlebone's
title, making its `WorkspaceEdit` in whichever files it names, and only
while the file is as it was asked about. Parakeet declares
`codeActionLiteralSupport`, without which Cuttlebone offers none. Asking
for every diagnostic on every pass would cost Cuttlebone a check per fix
each time; asking only at the caret needs `IntentionMenuContributor`, which
is internal.

What the user sees differently: Cuttlebone's titles ("Remove Stderr from
the import", "Add y: 0", "Implement area", "Remove the unreachable code"),
a missing field with a value of its type, checked stubs, "Make x mutable"
for `invalid-assign` (new), and fixes in files with other errors.

One thing a new test assumed was wrong, and Cuttlebone is right:
`helper + nope` gets no import for `helper`, as the compiler reports only
the first undefined name there, and importing it would reveal the second, a
new error. With the second in another method, the import is offered.

The fixture's `getAllQuickFixes` doesn't compute lazy fixes, and neither
does `availableIntentions` (IntelliJ starts them after a real highlighting
pass), so the tests' `allQuickFixes` runs the computers as IntelliJ does,
taking them from `HighlightInfo` by reflection. Whether IntelliJ shows them
on Alt+Enter is in the checklist below.

### Completion of names, imports and keywords: `textDocument/completion` (d9b394b)

Deleted: Parakeet's names in scope (the file's outline, its imports, the
module's definitions), names from modules not imported yet with their
import, an import's module paths and symbols from the definitions it read,
and keywords and fields outside the fallback.

Replaced by: Cuttlebone's answer in every context, each item with its
kind's icon, where it's from, its signature after the name, Cuttlebone's
groups as priorities, and its `additionalTextEdits` (the import) made when
chosen.

The list that pops up as a name is typed asks once typing pauses
(`CuttleboneQueries.awaitStill`, as usage highlighting does). The first
version asked at each popup, and the typing replay failed it: 191 requests
for 223 keystrokes in std's `json.inko` (107 of them completion, where 18
had been after `.`), over 0.7 a keystroke. With the wait, 133 for 223 and
108 for 196.

### The Structure view, breadcrumbs and sticky lines: `textDocument/documentSymbol` (14bea11)

Deleted: `InkoStructureViewFactory`, `InkoStructureViewModel` and its tree
elements, and `InkoBreadcrumbsProvider`.

Replaced by: IntelliJ's own LSP support, turned on
(`LspDocumentSymbolSupport`): its Structure view (for any language), its
breadcrumbs collector (after the PSI one, which now has nothing to collect
for Inko), and its sticky lines, from the same collector
(`computeStickyLineInfos`, in 2025.3 too). Cuttlebone 0.15.0's document
symbols leave out only a method whose body has an unclosed brace, where
they had left out everything after it.

**What `InkoOutline` is still for** (the request said to keep what the
refactorings need, and say what): which method, type, trait or `impl` an
offset is in, an `impl`'s trait and type, a declaration's extent, and a
type's members, for Extract Method, Inline Method and Variable, Introduce
Variable, Parameter and Constant, Change Signature, Move, Safe Delete
(`InkoDeclarations.extent`), Rename's processors (a trait method's
parameter, a getter's setter), the style inspections, the trait gutter
icons pointing up, Implement Methods and Generate, the call and type
hierarchies' roots, statement moving, imports on paste, Expression Type,
and the completion fallback's fields. Cuttlebone has no answer for most of
these (they're Parakeet's own refactorings), and the rest run while code is
typed.

### Parameter info's own reading of a constructor's fields (ae04b2f)

Deleted: the branch that showed a type's fields for `Point(x: 1)` where
Cuttlebone had no signature, and the type among the fallback's candidates.
Cuttlebone 0.15.0's signature help answers for a constructor: `Point(x:
Int, y: Int)`, in the type's order, with the argument's parameter by name
too. `InkoCalls.parameters` still reads a type's fields for the intention
that adds or removes argument names, which reads the definitions (below).

### Not done: `InkoDefinitionIndex` and `InkoSourceDefinitions`

The request listed them last, through workspace symbols with partial
results, implementations and the type hierarchy. They aren't deleted.
About fifteen features read them, and most need more of a definition than
a workspace symbol has:

- **The resolver**'s exact answers for an `import`'s module and symbols, and
  its reading of the code where Cuttlebone has no answer, need a module's
  file and a symbol's declaration in it; a workspace symbol has the
  location, but not the module of a declaration whose container is a type
  (`std.string.String`) apart from the type, and modules themselves
  aren't symbols.
- **Quick documentation** shows a definition's signature and doc comment;
  a workspace symbol has neither (its `detail` doesn't exist; document
  symbols' `detail` has the signature, hover the doc comment, each a
  request per declaration).
- **Implement and Override Methods, Generate → Implement Trait**: a trait's
  required and default methods. Cuttlebone's `invalid-implementation` fix
  stubs the missing required ones (the quick fix above), but there's no
  answer for the default ones (Override Methods) or for a trait's methods
  before the `impl` is written.
- **The intentions** that add or remove argument names, **the redundant
  argument name inspection** and the parameter info fallback: a callee's
  parameters, from its signature, and a type's fields.
- Whether a definition is `static`, `pub`, or required: workspace symbols
  have the kind (Method for both static and instance methods), and nothing
  else.

Rebuilding those from document symbols and hover, a request per
declaration, would be the index again, asked of Cuttlebone. Requests 1 and 2
below would let them go.

### The open correctness failure: `testSavedChangesAreKnown`

InkoDefinitionIndexTest's `testSavedChangesAreKnown` failed once, in the
first hook run of the completion commit, at a load average of about 330:
right after a save, the index didn't know the saved definition. The last
session saw it too, and left it open. It isn't a timing check. My lead is
part 1's: the index reads a saved file from disk (`Files.readString`), and
IntelliJ 2026.2's VFS event for the save, which makes the index read again,
comes before the disk has it, so it could read and keep the text before the
save until the next change. A test of that (`testAChangeSavedJustNowIsKnown`,
40 saves and a read of the index after each) passed 3 of 3 without a fix,
with a 4 MB file and with a small one, so I couldn't show it, and didn't
commit an untested fix. The fix would be the same wait (`flushPendingUpdates`)
at the start of `InkoDefinitionIndex.symbols`, or reading saved files through
the VFS, whose content is current.

## Fallbacks kept

- **Completion**, where Cuttlebone has no answer for the code being edited
  (the method being typed mentions what changed): the locals and parameters
  in scope, keywords and the file's fields, which typing needs and Parakeet
  reads from the file alone; and members by name after `.`, from the
  definitions, which goes with them.
- Nothing else new. Quick fixes have no fallback (without Cuttlebone's
  answer, none is offered: they change code), nor do the Structure view and
  breadcrumbs (without Cuttlebone, there are none: they're for reading), nor
  constructor parameter info in code Cuttlebone can't check.

## Requests for Cuttlebone

1. **A declaration's signature, doc comment, and whether it's static,
   public, or a trait's required method, with its workspace symbol** (or
   in document symbols, for a client that asks for a module's): then
   `InkoDefinitionIndex` and `InkoSourceDefinitions` could go, and quick
   documentation, the intentions and inspections that name arguments, and
   the resolver's imports would read Cuttlebone's.
2. **A trait's default methods** (and its required ones before an `impl` is
   written): a code action stubbing the default methods for Override
   Methods, or the list in a trait's document symbols.
3. **Modules as workspace symbols** (or a request for a module's file), so
   an `import`'s path resolves without Parakeet working out where modules
   live.
4. Still open from the last session: implementations, hierarchies and file
   renames past a declaration error elsewhere (its request 2), diagnostics
   past a module that doesn't parse (8), batch answers for the gutter icons
   (7), a status for every file rename (4) and statuses by request (5); the
   session building 0.16.0 is on these.

## The final check

`scripts/check.sh --full` on ae04b2f's code with this report in place,
against the published Cuttlebone 0.15.0, at load averages of about 300 to
800:

- **Tests**: 794, of which 2 failed, both CuttleboneDownloadOfferTest's
  tear-downs (`testACuttleboneOnPathThatDoesntRunIsSkippedForTheDownload`
  and `testCuttleboneOnPathComesBeforeADownload`: the temporary directory
  went while a fake still wrote its log, the race the last session saw
  under load). The class passed rerun alone, 17 of 17. `check.sh` stops at
  a failed step, so the steps after it were run as it runs them:
- **Typing replay**: passed, 119 and 81 requests in std's `json.inko`
  (under 0.7 a keystroke).
- **Plugin build**: passed.
- **Real-world projects**: all 21 passed (12 opened, 9 swept). Three
  skipped themselves, as before (clogs and shost need `std.sandbox`,
  code-formatting-in-inko's `Format` is undefined, with Inko 0.21.1; their
  checkouts here aren't on the commits ci/real-world-projects.txt pins).
  No site "changed nothing" or "refused, but changed the code"; 40
  refused for want of Cuttlebone's answer (inko-syntax 7, inko-markdown 9,
  yorickpeterse.com 5, website 13, inko-builder 6), counted apart, as
  before.
- **Plugin Verifier**: Compatible against 2025.3 (IU-253.28294.334) and
  2026.2.3 (IU-262.10968.63), with experimental API usages reported, as
  `withLazyQuickFix` adds one.

Each commit's hook passed. Hook runs that failed on load and passed rerun
alone, named in their commits where they were committed: the typing tests'
`testUsageHighlightingDoesntAskAboutTextThatChangesMeanwhile` (14bea11, at
a load average of 800 to 900). The completion commit's first two hook runs
failed for real: the typing replay, over its limit (fixed, above), then
`testSavedChangesAreKnown` (open, above); the third passed.

## Hand-testing checklist

With Cuttlebone 0.15.0 for Inko 0.21.1, in IntelliJ 2025.3 and 2026.2:

**Late edits (2026.2)**
- [ ] Rename a method used in a module no editor has open, then Find Usages
  of the new name at once: every use is found, the other module's included.
- [ ] Safe Delete something used only in a closed module, right after a
  Rename there: it refuses, listing the use.

**Cuttlebone 0.15.0 or newer**
- [ ] A `cuttlebone` 0.14.0 on `PATH` (nothing set, nothing downloaded):
  "Parakeet needs Cuttlebone" offers the download; the settings page says it
  skipped the one on `PATH`, and why. The same 0.14.0 in the setting is
  refused, saying why.

**Quick fixes (Cuttlebone's)**
- [ ] Alt+Enter on an undefined name another module defines: "Import X from
  M", in a file with another error (in another method) too.
- [ ] Alt+Enter on an unused import (one of several names, and the only one),
  an unused variable, an unused result, an assignment to an immutable
  variable, `Point(x: 1)` missing `y`, an `impl` lacking methods, a missing
  required trait, and code after a `return`: each offers Cuttlebone's fix, and
  the result type checks.
- [ ] The fixes appear without a long pause on a busy machine, and not for
  an error elsewhere on screen while typing.

**Completion (Cuttlebone's)**
- [ ] Typing a name: locals, the module's names, imported ones and keywords;
  a public name from a module not imported yet adds its import when chosen.
- [ ] `@` in a method: the type's fields. `import std.` and `import std.string
  (`: modules and symbols.
- [ ] In a method with a declaration error nearby (`fn broken -> Nope {}`),
  completion still offers the locals and keywords.
- [ ] No noticeable lag while typing names (the popup waits for a pause).

**Structure view, breadcrumbs, sticky lines (Cuttlebone's document symbols)**
- [ ] ⌘7 shows types, traits, `impl` blocks, methods, fields and constants;
  clicking goes there.
- [ ] Breadcrumbs and sticky lines follow the caret through a type and its
  method.
- [ ] Typing `if x {` in a method without its `}`: the type and what follows
  stay in the Structure view.

**Parameter info**
- [ ] ⌘P in `Point(` and in `Point(y: 2, x: |)`: `Point(x: Int, y: Int)`,
  with the right parameter highlighted.

