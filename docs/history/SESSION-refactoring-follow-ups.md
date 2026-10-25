# Session: refactoring follow-ups

Four follow-ups from [Compound assignments](SESSION-compound-assignments.md)'s
"What's left unverified". For each, this session first wrote a test,
ran it and saw it fail, then fixed it. All four were real.

This work is requested, not reviewed.

## Commits, oldest first

| Commit | What |
|---|---|
| 213267b | Item 1: Extract Method writes the compiler's type for an untyped local |
| 72ee911 | Item 2: usage highlighting's own reading marks assignments as writes |
| 5faae2a | Item 3: Inline and Introduce Variable treat compound assignments as assignments |
| 5d1a828 | Item 4: renaming a getter or setter offers to rename the other too |
| (this report) | This report, and README's link to it |

## The tools

- `inko 0.21.1`, from ivm.
- `cuttlebone 0.9.0 (Inko 0.21.1)`, in Cargo's `bin` directory (milestone
  9). Another session was changing Cuttlebone at the same time. I didn't
  install, rebuild or modify it, and wrote nothing to the Cuttlebone or
  Inko checkouts.
- To see what Cuttlebone answers, I used a small LSP client in my scratch
  directory, as the last session did. It isn't committed.

## Item 1: Extract Method's types for untyped locals

### Was it real?

Yes. For `let mut sum = 0` and a selected `sum += items.size`, Extract Method
wrote `fn extracted(sum: Type, items: mut Array[Int]) -> Type`. `Type` is the
placeholder you Tab to and fill in. `InkoLocals` only records a written type,
or one from `let x = Foo.new` or `let x = Foo(…)`.

### The tests

`InkoExtractMethodTypesTest` (new) runs the Extract Method action against a
stubbed Cuttlebone:

- **`testAnUntypedLocalTakesTheCompilersType`**: with a fresh hover of `Int`
  at `sum`, the method is `fn extracted(sum: Int, items: mut Array[Int]) -> Int`.
  **It failed before the fix**, with `sum: Type` and `-> Type`.
- **`testAStaleTypeIsNotWritten`**, **`testWithoutAnAnswerTheTypeIsAPlaceholder`**
  (Cuttlebone can't check the code), and **`testAPartlyInferredTypeIsNotWritten`**
  (`Array[?]`): each still writes the placeholder. They passed before the fix
  as well. They pin down that today's behaviour stays where there's no fresh,
  complete answer.

`InkoIntegrationTest.testExtractedUntypedLocalsTakeTheCompilersTypes` uses the
real `cuttlebone` and `inko`. It extracts code that reads `let mut sum = 0` and
`let scale = 2.5`, checks the method is
`fn extracted(sum: Int, items: mut Array[Int], scale: Float) -> Int`, and that
`inko check` passes. Without Cuttlebone's types it fails on the existing
"Placeholder types won't compile" check.

### The fix

- `InkoExtractMethod.plan` takes `bindingType`, the compiler's type for the
  local at an offset. The order is: a written type, then the compiler's, then
  what `InkoLocals` reads from `Type.new` and `Type(…)`, then the placeholder.
- The handler fills it from `InkoCompilerTypes.ofBinding`, which is what
  "Specify type explicitly" uses. It's Cuttlebone's hover at the local's name,
  fresh only, and never a type with a `?`. The plan is now worked out under a
  modal progress, off the EDT, because nothing waits for Cuttlebone on the
  EDT. So the question can wait for its answer (1.5 s) instead of finding
  only cached answers.

### Code deleted

None. No code guessed a local's type from a literal: `InkoExtractMethod`'s
comment said literals counted, but nothing did that for locals. The comment
now says what the code does. The literal guessing that does exist,
`InkoIntroduceParameter.inferType`, works out the type of the *value* that's
extracted (`-> Int` for `1 + 2`, `-> String` for `'…'`), not of locals. It's
also what Introduce Parameter uses, and it's today's behaviour where
there's no fresh answer, so it stays.

## Item 2: assignments as writes in usage highlighting's own reading

### What Cuttlebone marks

I asked the real Cuttlebone for `documentHighlight` in a module with every
kind of assignment:

| Code | Cuttlebone's kind |
|---|---|
| `let mut x = 1`, a parameter `x: Int` | Write |
| `x = 2`, `x += 3`, `x := 4` | Write |
| `@n = 1`, `@n += 2`, `@n := 3` | Write |
| `let mut @n: Int`, `fn value -> Int` (declarations) | Text (which Parakeet shows as a write) |
| `c.value = 2`, `c.value += 1`, in the setter's highlights | Write |
| `c.value += 1`, in the getter's highlights | Write |
| `x + y`, `@n`, `c.value` | Read |

### Was it real?

Yes. Usage highlighting reads the code itself only where Cuttlebone can't
check it (the code doesn't parse there, or a declaration has an error).
There, only declarations were writes, and every assignment was a read.

### The tests

Each of these failed before the fix, with the assignments as reads:

- `InkoHighlightUsagesTest`:
  - **`testALocalsAssignmentsAreWrites`**: `=`, `+=` and `:=` are writes.
    `x == 5` and `x <= 6` stay reads.
  - **`testEveryCompoundAssignmentIsAWrite`**: all twelve operators.
  - **`testAFieldsAssignmentsAreWrites`**.
- `InkoCompoundSetterTest`:
  - **`testInCodeBeingEditedHighlightingMarksSetterCallsAsWrites`**: from
    `c.value = 2`, both setter calls are writes. From the read `c.value`, the
    getter's highlights have `c.value += 1` as a write and `c.value` as a read.

I first wrote the setter test in `InkoHighlightUsagesTest`. There it failed
for another reason: that test's fake `inko doc` doesn't document `Counter`,
so the setter didn't resolve at all. I moved it to `InkoCompoundSetterTest`,
whose project resolves it, like the existing highlighting test there.

`CuttleboneFeaturesTest.testInCodeBeingEditedUsageHighlightingIsParakeets`
asserted the old reading, `«total» = 1`, as a read. That is the behaviour this
item replaces, so I changed its expected `total = 1` to a write. It's the only
existing expectation this session changed.

### The fix

- `InkoNameContext.isReplaced` (`:=`) and `isAnyAssignment`, next to the
  existing `isAssigned` and `isCompoundAssigned`.
- `InkoHighlightUsagesHandler` marks an occurrence that's assigned as a write.
- `InkoUsages.inFile` still marks only declarations. Inline Variable uses it
  for a variable's uses, and must still see the assignments among them, to
  refuse them.

## Item 3: Inline Variable and Introduce Variable

### Was it real?

Yes, for both. I compiled what each made with the installed `inko`:

- **Inline Variable** refused `n =` and `n :=` but not `n += 2`. Inlining
  `let mut n = 1` with `n += 2` after it made `1 += 2`, and `inko check`
  rejects that: `error(invalid-syntax): '+=' can't be used here`.
- **Introduce Variable** left out `x = …` occurrences but not `x += …`.
  Introducing a variable for `sum` replaced `sum += 1` with an assignment to
  the new variable, which is immutable:
  `error(invalid-assign): the variable 'sum2' is immutable and can't be
  assigned a new value`.
- **Introduce Variable** also offered occurrences past an assignment to what
  the expression reads. With every occurrence replaced, a read after
  `sum += 1` (or `x = 2`) got the value from before it. That compiles, but
  it's wrong, and the same thing happens in a loop whose body assigns it.
  This comes from the same gap, so I fixed it here too.

Its old assignment check also missed `x =1` with no space after the `=`. The
shared check below fixes that, but no test covers it.

### The tests

These failed before the fix:

- `InkoInlineVariableTest.testRefusesCompoundAssignedVariables`: all twelve
  operators are refused. It made `1 += 2` before.
- `InkoIntroduceVariableTest.testACompoundAssignedOccurrenceIsNotOne`: all
  twelve.
- `InkoIntroduceVariableTest.testOccurrencesAfterAnAssignmentAreNotOnes`:
  compound and plain assignments. The test also checks that occurrences
  before the assignment are still offered.
- `InkoIntroduceVariableTest.testOccurrencesInALoopThatAssignsAreNotOnes`:
  `foo(n * 2)` before `while … { bar(n * 2); n *= 2 }`.

`InkoInlineVariableTest.testComparisonsLikeCompoundAssignmentsAreNotAssignments`
(`<=`, `>=`, `!=`) passed before the fix and still does.

`InkoIntegrationTest`, compiled with the real `inko`:

- **`testIntroducedVariablesBesideCompoundAssignmentsStillCompile`**: with
  every occurrence, only the first `sum + items.size` is replaced. The result
  compiles.
- **`testInlinedVariablesBesideCompoundAssignmentsStillCompile`**: inlining
  `step` into `sum += step` and `sum *= step` compiles, and inlining `sum` is
  refused.

### The fix

- Extract Method's check for an assignment after a name moved to
  `InkoTokenStream.assignmentAt`, so all three refactorings share one.
- Inline Variable uses it.
- Introduce Variable uses it. It also drops an occurrence when an assignment
  to a variable or field the expression reads lies between it and the
  selected expression, or when a `loop`, `while` or `for` that assigns one
  holds only one of the two.

### Code deleted

Inline Variable's own assignment check, and Introduce Variable's
`isAssignedTo`.

## Item 4: renaming a getter and setter together

### Was it real?

Yes. Renaming one accessor of a pair that a compound assignment uses was a
conflict, and that conflict was the only way out. The second rename then
searched code that no longer type checked after the first, so it could only
list the site as a conflict.

### The tests (in `InkoCompoundSetterTest`)

- **`testRenamingTheSetterOffersToRenameTheGetter`** and
  **`testRenamingTheGetterOffersToRenameTheSetter`**: answering Yes renames
  both declarations, `c.value += 1`, `c.value = 2` and `c.value`, with no
  conflict. Each search resolves the candidates (references have no answer
  here). **Both failed before the fix** with the conflict.
- **`testAGetterWithoutASetterIsRenamedAlone`**: no offer when there's no
  pair. The test dialog would fail the test if one were shown.

In `InkoIntegrationTest`, **`testRenamedAccessorPairsStillCompile`** runs
against the real `cuttlebone`: renaming the getter, with Yes, renames
`bump.inko`'s compound assignment, plain assignment and read, and the project
compiles. Before the fix, this was a conflict.

Five existing tests rename only one accessor of a pair:

- the three rename tests in `InkoCompoundSetterTest`
- `InkoSetterTest.testRenamingASetterRenamesItsAssignments`
- `InkoIntegrationTest.testRenamingOneAccessorOfACompoundAssignmentIsAConflict`

They now answer the new question with No, and their expectations are
unchanged.

### The fix

`InkoInstanceMethodRenameProcessor.prepareRenaming` looks for the other
accessor of the pair: `value=` for `value`, or `value` for `value=`. It looks
beside the method, in the same `type` or `impl`. If it finds one in the
project, it asks "Rename the setter `value=` to `amount=` too? …". On Yes, it
adds that accessor, and its trait methods and implementations, to the same
rename. IntelliJ finds every usage of everything in a rename before changing
anything, so all the answers are fresh: they come from Cuttlebone's checked
`references`, or from each candidate's `definition`, through the existing
search. `findCollisions` already skipped the conflict when the other accessor
is renamed too. It asks only on the EDT. A rename started from anywhere else
renames what it was asked to.

## What's left unverified

- **Extract Method in the IDE.** In the tests, the modal progress may run on
  the EDT, where only cached answers count. The unit tests' stub answers at
  once, and the integration test asks Cuttlebone ahead of time, as the other
  integration tests do. Neither shows the modal progress waiting for a slow
  answer.
- **Extract Method's value types** (`-> Int` for an extracted expression)
  still come from `inferType` or the placeholder, not from Cuttlebone's type
  for the expression. Asking it would be the same kind of change.
- **In-place rename.** Renaming a member in the editor (not in the dialog)
  should go through the same `prepareRenaming`. It has no test.
- **Accessors apart.** A getter in another `impl` block, or a trait's default
  getter beside a type's setter, isn't offered. A getter named `ready?` pairs
  only with a setter named `ready?=`. A field (`@value`) isn't renamed with
  its accessors, as Java's fields are.
- **Introduce Variable's occurrences** don't account for a method call that
  changes what the expression reads (`items.push(1)` between two
  `items.size`), as before. A loop is found by its keyword and its first `{`,
  so a closure with braces in a `while` condition would confuse it.
- **`x =1`**, which the shared assignment check now treats as an assignment
  in Introduce Variable, has no test.
- **Platforms.** Everything ran on macOS with `inko` 0.21.1 and
  `cuttlebone` 0.9.0.

## Notes on running the checks

- I ran single test classes with `--no-configuration-cache`, as the last
  report advised.
- `CuttleboneFeaturesTest.testUsageHighlightingWaitsForAFilesFirstCheck`
  failed once, in the pre-commit hook's run on this report's first commit
  attempt, while the other session was using the machine. It's the timing
  test the last report saw fail the same way: it asserts the order of two
  requests to a fake Cuttlebone that waits 1 s, and the second hadn't been
  sent yet. The commit only changed docs. The test passed alone three times
  and in every other run. No other timing limit failed.

| Run | Time |
|---|---|
| `scripts/check.sh` from the pre-commit hook, items 1 to 4 | 605 s, 348 s, 479 s, 492 s |
| `scripts/check.sh --full`, on this report's tree | 411 s (the tree as committed, but for this number) |

The suite has 701 tests, all passing.
