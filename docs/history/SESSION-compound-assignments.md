# Session: compound assignments

Two bugs that [Fixing the rest of the audit](SESSION-audit-fixes.md)
noticed by reading, under "What's left unverified", and never confirmed.
For each, this session first wrote a test against Inko's real behaviour,
then fixed it once the test failed. Both were real.

This work is requested, not reviewed.

## Commits, oldest first

| Commit | What |
|---|---|
| fce9f64 | Bug 1: Extract Method returns a compound-assigned local, as it does a reassigned one |
| ef31e15 | Bug 2: a compound assignment to a member is a use of its getter and its setter |
| (this report) | This report, and README's link to it |

## The tools

- `inko 0.21.1`, from ivm.
- `cuttlebone 0.8.0 (Inko 0.21.1)`, in Cargo's `bin` directory (milestone
  8). I didn't install, rebuild or modify it.
- I read Inko's `ast/src/lexer.rs`, `ast/src/parser.rs` and
  `compiler/src/hir.rs` at the `v0.21.1` tag with `git show`, and wrote
  nothing to the Inko or Cuttlebone checkouts.
- To see what Cuttlebone answers, I used a small LSP client in my scratch
  directory. It isn't committed.

## Bug 1: Extract Method and `sum += x`

### Was it real?

Yes. Extract Method treated only `x =` and `x :=` as assignments, because
Parakeet's lexer makes each operator character its own token, so `+=` is
`+` then `=`. Extracting `sum += items.size` made:

```inko
fn extracted(sum: Int, items: mut Array[Int]) {
  sum += items.size
}
```

and the call was a bare `extracted(sum, items)`. `inko check` rejects the
method:

```
error(invalid-assign): the variable 'sum' is immutable and can't be assigned a new value
```

Even if it compiled, the caller would never see the new sum.

### What Inko does

Inko's lexer has twelve compound assignments: `+= -= *= /= %= **= <<= >>=
>>>= |= &= ^=`. The parser reads `x += y` on a variable as
`binary_assign_variable`, the same as `x = x + y`. A method can't hand a
changed local back through a reference: Inko has no references to
variables, only to values, and `Int` and the other value types are copied
anyway. The only way a method can give a caller a new value is to return
it. That's already what Extract Method does for a plain reassignment
(`let mut x = x` in the method, `x` returned, `x = extracted(x)` at the
call), so a compound assignment now goes the same way.

I also checked closures, since a closure body runs more than once: Inko
rejects assigning a captured variable in a non-moving closure ("variables
captured by non-moving closures can't be assigned new values"), so that
case can't come up.

### The tests (each failed before the fix)

In `InkoExtractMethodTest`:

- **`testACompoundAssignedVariableIsPassedAndReturned`**: `sum += items.size`
  becomes `sum = extracted(sum, items)`, and the method copies `sum` into a
  `let mut`, updates it, and returns it as `-> Int`.
- **`testEveryCompoundOperatorAssigns`**: all twelve operators are treated
  the same way. `x <= 2`, which looks like `<<=` without the second `<`,
  stays a comparison and is extracted as a `Bool` value.
- **`testAVariableAssignedInALoopIsReturned`**: in `while sum < 10 { sum += 3 }`,
  nothing after the selection reads `sum`, but the loop's condition reads
  it on the next pass. Before the fix, the update was lost and the loop
  never ended. The same happened with a plain `sum = sum + 3`.
- **`testRefusesTwoCompoundAssignedVariablesNeededBack`**: `a += 1` and
  `b -= 1` with both used afterwards is refused with the existing reason:
  "The code after the selection needs `a`, `b` back, including reassigned
  variables, and Inko can't assign several variables at once."

In `InkoIntegrationTest`:

- **`testExtractedCompoundAssignmentsStillCompile`**: the real `inko check`
  compiles the result for `sum += items.size` and for `n *= 2` in a
  `while` loop.

### The fix

- `isAssignedAt` now joins adjacent operator tokens and checks them against
  Inko's compound assignments (now `InkoTokens.COMPOUND_ASSIGNMENTS`).
- A local assigned inside a `loop`, `while` or `for` that the local is
  declared outside of counts as needed back, even with nothing after the
  selection.

## Bug 2: `c.value += 1` and the setter `value=`

### What Inko does

The parser reads `recv.name += x` as `binary_assign_setter`
(`call_with_receiver`, `ast/src/parser.rs`). The compiler lowers it
(`compiler/src/hir.rs`, `binary_assign_setter`) to an `AssignSetter` for
`name`, whose value is a call to `+` on a getter call `recv.name`. Both
calls take the same name token, so the site calls the getter `value` and
then the setter `value=`.

### What Cuttlebone answers there

I asked the real Cuttlebone about `c.value = 2`, `c.value += 1` and
`c.value` in a project with `fn pub mut value=` and `fn pub value`:

| Question | Answer |
|---|---|
| `definition` at `c.value += 1` | the setter `value=` only |
| `references` of `value=` | `c.value = 2` and `c.value += 1` |
| `references` of `value` | `c.value += 1` and `c.value` |
| `documentHighlight` at `c.value += 1` | the setter's: both assignments, as writes |
| `documentHighlight` at `c.value` | the getter's: `c.value += 1` as a write, `c.value` as a read |

### Was it real?

Yes, but only where Parakeet decides instead of Cuttlebone's `references`.
With `references` answering, Find Usages of either accessor already found
the site. The new integration test confirmed that first. Elsewhere:

- **Each candidate resolved through `definition`.** This happens on the
  EDT, where only cached answers are used, and when a file in the project
  doesn't parse. Cuttlebone's definition at the site is the setter, so
  Find Usages, Rename and Safe Delete of the **getter** missed it.
- **In code Cuttlebone can't check.** Parakeet's own reading knew only `=`
  as a setter call, so it resolved the site to the getter (and the field
  `@value`). Go to definition disagreed with Cuttlebone, and Find Usages
  of the **setter** and usage highlighting missed the site.
- **Rename**, everywhere: renaming either accessor renames the site, which
  then calls the other accessor by the new name, which doesn't exist.

### The tests (each failed before the fix)

`InkoCompoundSetterTest` (new) uses a fake Cuttlebone that gives the
answers in the table above:

- **`testWithoutReferencesSearchesCountACompoundAssignmentForTheGetterAndTheSetter`**:
  with no answer from `references`, Find Usages of both the setter and the
  getter find `c.value += 1`.
- **`testWithoutReferencesSafeDeletingAGetterOnlyACompoundAssignmentUsesWarns`**:
  Safe Delete of a getter used only by `c.value += 1` warns about that
  usage.
- **`testRenamingTheSetterSaysACompoundAssignmentAlsoCallsTheGetter`** and
  **`testRenamingTheGetterSaysACompoundAssignmentAlsoCallsTheSetter`**: each
  is a conflict, for example "`c.value += 1` at main.inko:4 also calls
  `value=`, which isn't renamed, so it would call `amount=` instead."
- **`testInCodeBeingEditedACompoundAssignmentResolvesToTheSetter`**:
  Parakeet's reading resolves the site to `value=`, as Cuttlebone does.
- **`testInCodeBeingEditedFindUsagesCountsACompoundAssignmentForTheGetterAndTheSetter`**.
- **`testInCodeBeingEditedHighlightingCountsACompoundAssignmentForTheGetterAndTheSetter`**:
  from the site, the setter's uses; from a read, the getter's uses, which
  include the site. These are the same sets Cuttlebone gives.

`testRenamingAnAccessorAnywayRenamesTheCompoundAssignment` (which passed
before the fix too) checks that going ahead despite the conflict renames
the site.

`InkoIntegrationTest`, against the real `inko` and `cuttlebone`:

- **`testACompoundAssignmentIsAUseOfTheGetterAndTheSetter`**: a background
  search, as Find Usages runs, of each accessor. First with `references`
  answering, which passed before the fix. Then with a module that doesn't
  parse, so each candidate is resolved, where the getter's search failed
  before the fix.
- **`testRenamingOneAccessorOfACompoundAssignmentIsAConflict`**: renaming
  the getter on the EDT is a conflict. Before the fix, there was no
  conflict: the rename missed the site, which would then call a getter
  that no longer exists. With the conflict, nothing is renamed and the
  project still compiles.

### The fix

- `InkoNameContext.InstanceMember` has `compound`. A compound assignment
  is also `assigned`, so Parakeet's reading resolves it to the setter (or
  the field, when there's no setter).
- `InkoResolver.getters` gives the getter a compound site also calls. It's
  the setter's type's method of that name, else its field, else Parakeet's
  reading of the plain member. Searches (`InkoSearch.isReferenceTo`, and
  the unchecked names Rename lists) and usage highlighting's own reading
  count it next to what the site resolves to. Highlighting from the site
  itself stays the setter's, as Cuttlebone has it.
- `InkoInstanceMethodRenameProcessor.findCollisions` lists the conflict,
  unless the other accessor is being renamed in the same rename.

I chose a conflict over renaming the other accessor automatically. It
says what's wrong without renaming more than was asked, and IntelliJ lets
you go ahead anyway.

## What's left unverified

- **Renaming both accessors.** IntelliJ renames one declaration at a time,
  so renaming a getter and setter pair means going through the conflict
  twice. After the first rename the code doesn't type check, so on the
  second, the site may be in code Cuttlebone can't check, and Rename only
  lists it. Offering to rename the other accessor too (as IntelliJ does
  for Java getters and setters) would be better. I didn't build it.
- **Renaming a field** used through `c.value += 1` with a setter beside it
  goes through IntelliJ's default rename, which has no such conflict.
  Neither that case nor a field-only `c.value += 1` has a test.
- **A getter that isn't beside the setter**, such as a trait's default
  method when the type defines the setter, falls back to Parakeet's reading
  of the plain member. It has no test.
- **`c.value := x`** (`replace_setter`) isn't handled. It's out of scope.
- **Extract Method types.** `let mut sum = 0` without a type still gives a
  placeholder parameter type: `InkoLocals` only records written types,
  though `InkoExtractMethod`'s comment says literals count. The integration
  tests write `: Int`, as the existing one does.
- **The loop fix was only compiled, not run.** The unit test checks the
  call assigns the value back.
- **Other token-based assignment checks** (Inline Variable, Introduce
  Variable) may also miss compound assignments. I didn't look into it.
- **Usage highlighting's own reading** still marks assignments as reads,
  as before. Cuttlebone marks them as writes.
- **Platforms.** Everything ran on macOS with `inko` 0.21.1.

## Notes on running the checks

- One run of `./gradlew test --tests …` reused the configuration cache and
  reported `compileTestKotlin` up to date after a test file had changed,
  so the new tests didn't run. With `--no-configuration-cache` they were
  compiled. `scripts/check.sh`'s runs did include the new tests.
- `CuttleboneFeaturesTest.testUsageHighlightingWaitsForAFilesFirstCheck`
  failed once in `scripts/check.sh` while the machine was busy: it asserts
  the order of two requests to a fake Cuttlebone that waits 1 s. It passed
  alone, and in every later run, including both pre-commit runs.

| Run | Time |
|---|---|
| `scripts/check.sh` (the one with the flaky failure) | 189 s |
| `scripts/check.sh` from the pre-commit hook, for each fix | 347 s, 328 s |
| `scripts/check.sh --full`, on this report's commit | 497 s (the tree as committed, but for this number) |

That's much slower than the last sessions' 78–108 s, because another
session was using the machine at the same time. Of the hook's runs, the
typing replay took about 2½ minutes. The suite has 681 tests, all
passing.
