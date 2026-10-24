# Session: declaration errors, and 0.12

Cuttlebone's eighth milestone (`cuttlebone 0.8.0 (Inko 0.21.1)`, on PATH)
answers in projects with a declaration error, wherever the error can't
affect the answer. This session:

- updates the one test that failed against it
- checks every remaining fallback against the real server again, and
  decides each one
- fixes a bug that milestone 8 exposed: possible usages shown as certain
- records the state of the release/0.12 branch

This work is requested, not reviewed.

## Commits, oldest first

| Commit | What |
|---|---|
| 44530dc | Step 1: hints in a project opened with a declaration error are Cuttlebone's; a project opened with an import of a missing module still has none |
| a8853b0 | Step 2: every fallback kept, with its states checked again; possible usages marked per search; README and comments describe milestone 8 |
| (this report) | This report, README's link to it, and the "Certified Shovelware" badge |

## The `cuttlebone` on PATH

The one in Cargo's `bin` directory, which prints `cuttlebone 0.8.0 (Inko
0.21.1)`. I didn't install, rebuild or modify it, and I didn't write to the
Cuttlebone or Inko checkouts. From Cuttlebone's checkout I read only
docs/history/MILESTONE-8.md and docs/lsp.md.

## Step 1: the test

`CuttleboneIntegrationTest.testOpenedWithADeclarationErrorThereAreNoHints`
failed against milestone 8 with "code being edited": the inlay hints
answer was `fresh`, not `editing`. It's now two tests:

- **`testOpenedWithADeclarationErrorHintsAreCuttlebonesWhereTheErrorCantMatter`**:
  the same project (`fn broken -> Missing`), plus `calls_broken`, which
  names `broken`. The answer is fresh and not repaired. The hints are
  Cuttlebone's everywhere except `broken` and `calls_broken`, so neither
  `twice(3)` nor `let v = broken` gets a hint.
- **`testOpenedWithAnImportOfAMissingModuleThereAreNoHints`**: the state
  that still has no answer anywhere in a file. `import app.nope` stops
  type checking (`noHir`), and there's no good check yet for a stale
  answer to come from. The answer is `editing`, and there are no hints of
  either kind. That's what the old test checked, in the state where it
  still holds.

## Where milestone 8 answers

I ran the same LSP client as last session (a small one in my scratch
directory, not committed), asking `definition`, `hover`,
`documentHighlight`, `signatureHelp`, `inlayHint`, `completion` and
`references` in the integration tests' project (`util.inko`'s `A.size`
and `B.size`, and `main.inko` calling `x.size(2.0)` on a `B`).

| State | Answers |
|---|---|
| Healthy | all fresh |
| **Opened with a declaration error in main** (`fn broken -> Missing`; last session: all unavailable) | fresh in every other method; inlay hints fresh, `covered` leaving out `broken` and `calls_broken`; `references` of `twice` unavailable (`declarationErrors`), since `calls_broken` names it; `references` of `size` and `make` fresh; completion fresh |
| Inside `broken` itself, and in a method naming it | `definition`, `hover`, `documentHighlight`, `signatureHelp` and `completion` all unavailable (`declarationErrors`) |
| **Opened with a declaration error in util** (`A.size(unit: Missing)`; last session: all unavailable) | in `run`, which calls a `size`, all unavailable (`declarationErrors`): names, not ids, decide (MILESTONE-8.md); in `twice` and `uses`, fresh; `references` of `size` unavailable, of `twice` fresh |
| Opened with a declaration error in a type main doesn't use | all fresh, references included |
| After a good check, the same error added to util | as when opened with it: unavailable in `run`, not stale (the changed signature's name counts), fresh elsewhere |
| After a good check, the error added to main | as when opened with it |
| Opened with `import app.util (Nope)` | all fresh, references included |
| Opened with `import app.nope` | all unavailable (`noHir`), completion too |
| After a good check, `import app.nope` added | `stale` (as last session); references unavailable (`noHir`) |
| A syntax error earlier in `run` (opened, or after a good check) | after it in `run`, all unavailable (`noHir`), completion too; other methods fresh from a repaired copy |
| A syntax error in another method | all fresh from a repaired copy, completion included; references unavailable (`noHir`) |
| A file nothing imports doesn't parse | `definition` fresh; `references` unavailable (`noHir`) |
| A file nothing imports has a declaration error | all fresh, references included |

So two of last session's states are gone: whole files don't go dark when
a project opens with a declaration error, and references aren't dark
project-wide for one. What's left with no answer:

1. **After the edit point** in a method with a line that doesn't parse
   (`noHir`), as before.
2. **A file importing a module that doesn't exist**, before any good
   check (`noHir`), as before.
3. **The method or item with a declaration's error, and every unit naming
   what it's in** (`declarationErrors`). This is new in its narrowness,
   and it's still where code is being written: a new method whose return
   type isn't defined yet has no completion, navigation or highlighting in
   its body, nor do its callers.
4. **References**, wherever a file in the project doesn't parse, or a
   unit in case 3 mentions the name.

## Step 2: each fallback's decision

Every fallback is used only when Cuttlebone's answer is `editing` (reasons
`noHir`, `declarationErrors`, `untyped`, or only a stale answer where a
question doesn't take one). No code treats `declarationErrors` apart from
the others, so the question for each was whether its states still occur.

| Fallback | Last session's states | Against milestone 8 | Decision | Lines deleted |
|---|---|---|---|---|
| Resolve's reading of the code, for navigation, documentation and hierarchies (`InkoResolver.resolution`) | opened with a declaration error, after the edit point, an error elsewhere | after the edit point (1), the error's own method and those naming it (3) | **kept** | 0 |
| Completion after `.` (`InkoCompletion.member`) | after a line that doesn't parse, in any method of the file | only after it in the same method (1, `testAfterASyntaxErrorInTheSameMethodCompletionFallsBackToParakeet`); another method is answered (`testWithASyntaxErrorElsewhereCompletionIsCuttlebones`, already there). New: in the error's method and those naming it (3). Completion is needed most while typing | **kept** | 0 |
| Parameter info's candidates (`InkoParameterInfoHandler`) | as resolve | (1) and (3), where a call is being typed | **kept** | 0 |
| Usage highlighting (`InkoUsages.inFile`) | after the edit point, a file whose imports have a declaration error | (1), and (3) only in the methods naming the broken item, not the whole file | **kept** | 0 |
| Find Usages' per-candidate `definition`, and possible usages; Rename's conflicts (`InkoSearch`, `InkoCompilerReferences`) | references unavailable for any error in the project | references unavailable only in case 4. Then the candidates in answered code are still fresh by `definition`, and only those in cases 1 to 3 are possible | **kept**, and the bug below fixed | 0 |
| Parameter-name and type hints | none (deleted last session) | | | |
| Expression Type | none: it says why there's no type | | | |

No fallback is deleted. Each was kept for states that remain, and
deleting any would take something away while code is being typed, which is
why it was kept. Milestone 8 shrinks where they apply (whole files became
single methods) without removing a state.

### A bug milestone 8 exposed: possible usages shown as certain

`InkoCompilerReferences.covers` decided whether a name in the Usage view
was a possible usage. It said no to every name in a project once *any*
search had references there, with the code unchanged. Before milestone 8
that was right: references answered only when the whole project type
checked, so no code anywhere was unchecked. Milestone 8 answers references
for each name separately (in the first table, `make`'s and `size`'s while
`twice`'s are unavailable). So after usage counts or a search found one
name's references, Find Usages of another name, in code Cuttlebone can't
check, marked its guesses as certain usages.

Now a name counts as checked only if a search's references list it.

- **The test**, `InkoFindUsagesTest.testReferencesForOneDeclarationDontMakeAnothersUsagesCertain`,
  failed without the fix (`main.inko:7` was `null`, not "Possible usage").
  It finds `helper`'s usages (references answered), then `value`'s
  (references unavailable, `x.value` unchecked).
- **The stand-in** for the compiler (`InkoTestCase.Compiler`) gained
  `answersReferences`: a declaration whose references are given while
  other code can't be checked, as milestone 8 does. Every other test's
  stand-in answers as before.
- `InkoSearch.kt`: +12 −11. The `answered` map is gone.

### Docs

README's Find Usages paragraph and "Answers in code being edited" now say
which states still have no answer. So do the comments in `InkoSearch` and
one test comment in `CuttleboneFeaturesTest` (its test is unchanged).
README: +8 −4.

## The release/0.12 branch

I didn't touch the branch, its worktree or the 0.12.0 changelog entries.
I only read them.

- **release/0.12** is at 94ffec0 "Prepare 0.12.0", one commit on bb41c28
  (the audit-fixes report, before the Cuttlebone work). It sets `version =
  0.12.0`, adds the 0.12.0 changelog entry (fixes only), and changes one
  README line. Its worktree is clean, and the branch is pushed.
- **It's already published on GitHub.** The `v0.12.0` tag (on 94ffec0) is on
  origin, its Release workflow succeeded (2026-10-24 23:33 UTC), and the
  GitHub Release has `parakeet-0.12.0-signed.zip` and `updatePlugins.xml`.
  The repository's `releases/latest/download/updatePlugins.xml` serves
  0.12.0 now.
- **Not on the Marketplace.** There's no `PUBLISH_TOKEN` secret, so the
  workflow skipped it, as README describes.

### What's left for you

1. **Check the release by hand** if you want to: install 0.12.0 through the
   plugin repository URL in an IDE. I didn't open an IDE.
2. **The Marketplace**, if you want it there: upload the first version by
   hand at plugins.jetbrains.com, then add a `PUBLISH_TOKEN` secret (README,
   Releasing).
3. **Bring 0.12.0 back to main.** Main still says `version = 0.11.0`, its
   CHANGELOG has no 0.12.0 entry, and README's "Versions 0.1.0 through
   0.11.0 are requested" is the line the release branch changed. Merging
   release/0.12 (or cherry-picking 94ffec0) will conflict in CHANGELOG.md
   and README.md, and main's version must stay above 0.12.0 for its next
   release. I left this alone, as the brief asked.
4. **Push main.** It's 17 commits ahead of origin/main (this session's 3
   included). I haven't pushed anything.
5. **The "shovelware" topic.** The badge's page suggests tagging the
   repository with it in GitHub's About settings. That changes the public
   repository, so I left it to you.

## Requests for Cuttlebone

1. **Names, not ids**, decide what a declaration's error affects
   (MILESTONE-8.md lists this as a decision to revisit). In the probe, an
   error in `A.size`'s signature took every answer away from `run`, which
   only calls `B.size`. Ids would leave Parakeet's fallback less to do.
2. **An import of a module that doesn't exist** stops every answer in the
   file until a good check (`noHir`), where `import app.util (Nope)` (a
   missing symbol) is answered fresh. A repaired copy with the import line
   blank would answer the rest of the file.
3. From last session, still open: kept checks right after a save, and
   references for a symbol declared outside the project.

## What's left unverified

- **An interactive IDE**, as in earlier sessions: everything ran headless.
  I didn't see the Usage view's possible usages after the fix.
- **The fallbacks in the newly narrow state** (3) are covered by the
  existing tests of Parakeet's reading, which run with Cuttlebone stubbed
  to say `declarationErrors`. No integration test reaches a method with a
  broken signature against the real server.
- **Usage counts beside Find Usages**: the bug's real-world path (counts
  first, then a search for another name) follows from the code. The test
  runs two Find Usages.
- From before: 2025.3 at runtime, Linux, Windows, Inko 0.20.0, and CI,
  which doesn't install Cuttlebone.

## Tests and `check.sh`

Tests: 671, from 669. The pre-commit hook's `check.sh` passed on both step
commits, in 353 s and 309 s, with `cuttlebone 0.8.0 (Inko 0.21.1)` from
PATH. `scripts/check.sh --full` passed in 515 s on an Apple Silicon Mac, on
this report's tree before it was committed, with the same `cuttlebone`.

The Plugin Verifier reported the same as last session:

- **IDEA 2025.3 (253.28294.334):** "Compatible. 1 usage of deprecated API.
  11 usages of experimental API."
- **IDEA 2026.2.3 (262.10968.63):** "Compatible. 26 usages of deprecated
  API. 2 usages of experimental API."
- **Internal and override-only API:** none in either.
