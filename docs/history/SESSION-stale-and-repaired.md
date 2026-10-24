# Session: stale and repaired answers

Cuttlebone's fifth milestone answers in code being edited. An answer's
`cuttlebone/status` is now one of three usable kinds:

- `fresh`: from the documents as they are
- `fresh` with `"repaired": true`: from the documents with the lines in the
  way blank
- `stale`: from the last version that type checked, mapped to where its text
  is now

Before this session, Parakeet used every `fresh` answer (repaired ones
included, since it never read the flag) and treated `stale` as no answer.
This session:

- rewrites the two integration tests that expected no answer in a file with
  a syntax error
- decides, per feature, whether a stale answer is used, and how it's shown
- keeps builds that never send these fields working as before

This work is requested, not reviewed.

## Commits, oldest first

| Commit | What |
|---|---|
| c416e54 | The two tests now expect Cuttlebone's answers, and the fallback is tested where it still has none |
| 6173137 | `repaired` parsed, and `stale` used or refused per feature, with the unit and integration tests |
| (this report) | README, CHANGELOG and this report |

## The two tests

Both failed because milestone 5 answers from a repaired copy there. I
checked first, with a probe speaking LSP to the `cuttlebone` on PATH,
what it answers in each state before writing the tests:

| State | Inlay hints | Completion at `x.` |
|---|---|---|
| `fn broken( {` at the end of the file | fresh, repaired | fresh, repaired |
| Opened with `fn broken -> Missing {` (no good check yet) | unavailable (`declarationErrors`) | unavailable |
| `let y = [10, 20` unclosed, earlier in the method being completed | | unavailable (`noHir`) |
| After a good check, `import app.nope` added | stale | |
| After a good check, the file's last `}` deleted | fresh, repaired (hover, definition, highlights: stale) | |

So:

- `testWithASyntaxErrorHintsFallBackToParakeet` became
  `testWithASyntaxErrorHintsAreCuttlebones`: the same file, now expecting
  Cuttlebone's parameter names (including `scale`, which Parakeet can't
  tell) and types, a repaired answer with no "possibly outdated" tooltip.
- `testOpenedWithADeclarationErrorHintsFallBackToParakeet` keeps the old
  test's assertions (Parakeet's `default` and `value` hints, no types) in a
  state Cuttlebone can't answer, and asserts that it doesn't.
- `testWithASyntaxErrorElsewhereCompletionFallsBackToParakeet` became
  `testWithASyntaxErrorElsewhereCompletionIsCuttlebones`: exactly `size`,
  with the compiler's signature, from a repaired copy.
- `testAfterASyntaxErrorInTheSameMethodCompletionFallsBackToParakeet`
  keeps the old assertions (no answer, and Parakeet's completion still
  offers `size`) after a syntax error earlier in the same method.

Three integration tests are new, each after a good check followed by
`import app.nope`, which no repaired copy can type check:

- hints are stale, mapped a line down, and their tooltip says "possibly
  outdated"
- resolve doesn't use the stale definition, so `x.size` guesses both
  `size` methods again
- quick documentation shows the stale type marked "(possibly outdated)"

## What each feature does with each answer kind

| Feature | fresh | fresh, repaired | stale | none (or a status Parakeet doesn't know) |
|---|---|---|---|---|
| Resolve: go to definition, quick documentation's target, Find Usages, Rename, Call Hierarchy, usage counts, refactorings, quick fixes, auto-import, parameter-name hints' fallback | used | used | **not used**: Parakeet's guess | Parakeet's guess |
| Completion after `.` | used | used | **not used** (Cuttlebone never sends one) | Parakeet's guess |
| Parameter-name and type hints | used | used | used, tooltip "possibly outdated" | parameter names: Parakeet's guess; types: none |
| Parameter info | used | used | used, "(possibly outdated)" after the signature | Parakeet's candidates |
| Quick documentation, "Type here" | used | used | used, "(possibly outdated)" after the type | no "Type here" |
| Expression Type | used | used | used, "(possibly outdated)" after the type | the types the code states |
| Highlight usages | used | used | used, unmarked | Parakeet's |
| Go to Type Declaration (IntelliJ's own) | used | used | used, unmarked | nothing |

### Why

- **Repaired answers are fresh.** Cuttlebone computes them from the
  documents as they are, except for blank lines outside anything the answer
  depends on, and marks them `fresh`. Its rule for anything that changes
  code is "fresh only", and a repaired answer is fresh. So Parakeet uses
  them everywhere and doesn't mark them: there's nothing outdated about
  them. The flag is parsed (`Answer.repaired`) so tests and anyone
  debugging can tell, not to change behaviour.
- **Resolve refuses stale definitions.** One resolve serves both Go to
  Declaration and everything that changes code: Rename, Change Signature,
  Move, Safe Delete, Inline, quick fixes and auto-import all act on what a
  name resolves to. Cuttlebone's rule is that those use fresh answers only.
  Navigation could take a stale definition safely, but splitting resolve in
  two would let ⌘-click disagree with Rename about the same name, and
  Cuttlebone only gives a stale definition where no repaired copy checks
  (214 of 19,902 hovers on its corpus). Not worth it, for now.
- **Completion refuses stale answers.** Choosing an item inserts it into
  the code. Cuttlebone never sends a stale completion; Parakeet refuses one
  anyway, in case a later build does.
- **Hints, parameter info, documentation and Expression Type use stale
  answers, marked.** They only show text. Cuttlebone guarantees a stale
  answer holds for the documents as they are (its positions are in
  unchanged text, in units no edit since could change), so it's better than
  Parakeet's guess, which for types is nothing at all. The brief asked for
  them to be marked as possibly outdated, so they are, where IntelliJ has
  somewhere to put it: a hint's tooltip (the hint itself stays the same
  text, so it doesn't jump as you type), and text after the signature or
  type elsewhere. Expression Type's text is never written into code:
  "Specify type explicitly" doesn't use Cuttlebone.
- **Usage highlighting uses stale highlights, unmarked.** Cuttlebone only
  gives them when the list is complete, highlighting changes nothing, and
  there's no text to mark.
- **Go to Type Declaration** is IntelliJ's own LSP feature, which doesn't
  know about `cuttlebone/status`, so it navigates to whatever Cuttlebone
  answers, stale or not. That's read-only navigation to a location
  Cuttlebone mapped into unchanged text, so I left it.
- **Any other status is no answer.** Cuttlebone says clients must treat a
  status they don't know as no usable answer, so a future status changes
  nothing until Parakeet decides about it.

### How

`CuttleboneQueries` keeps every answer it receives with its status, and
each question says whether it takes a stale one (`stale = true` for hover,
signature help, inlay hints and document highlight; definition, type
definition and completion don't). The cache is per method, so hover's two
users (documentation and Expression Type) share one policy, and definition
is never asked for by anything that takes stale answers.

## Older Cuttlebone builds

A build before milestone 5 never sends `repaired` (parsed as false) and
never sends `stale`, so Parakeet behaves exactly as before with it. The
fake Cuttlebone used by the unit tests sends `repaired` only when a test
sets it, so every existing unit test runs against what an older build
sends, and `testAFreshAnswerWithoutTheRepairedFieldIsNotRepaired` checks it
directly. A build without status notifications at all still gives no
usable answers, as before.

## Left unverified

- **The integration tests need milestone 5.** The rewritten tests expect
  answers in broken code, so against a milestone-4 `cuttlebone` they'd
  fail. Nothing in `initialize` says which milestone a build is (its
  version is still `0.1.0`), so they can't branch the way the milestone-3
  checks do. The `cuttlebone` on PATH is milestone 5, so `check.sh` passes.
- **Stale parameter info, Expression Type and usage highlights** are tested
  only against the fake. Against the real server, only stale hints,
  documentation and the refused definition are. Cuttlebone's own corpus
  found stale signature help only once in 442 tries, and I didn't find an
  edit that gives one in this test project.
- **Go to Type Declaration with a stale answer** is untested: it's
  IntelliJ's code path.
- **How the markers look** in a running IDE: I didn't launch one. The
  tooltip goes through the declarative inlay API's `tooltip` argument,
  checked in the unit tests by what the providers pass to it.
- **Hints in a partly answered file.** A stale or repaired hints answer
  covers only the units that hold: the method being edited has no hints
  after the edit. Parakeet takes Cuttlebone's answer as the whole file's,
  so there, it shows no parameter-name hints instead of its own guesses.
  Merging the two needs to know which units Cuttlebone answered for, which
  it doesn't say.
- **A flaky test.** `CuttleboneIntegrationTest.testUsageHighlightingKnowsAssignments`
  failed once in eight full runs (check.sh and the pre-commit hook) (Parakeet's highlights instead of
  Cuttlebone's), and passed alone three times in a row. Usage highlighting
  waits 0.4 s, and a cold check under the full suite's load probably took
  longer. I didn't change the test or the budget.

## Checks

`scripts/check.sh --full` passed on the final commit, with
`cuttlebone 0.1.0 (Inko 0.21.1)` (milestone 5) from PATH.
