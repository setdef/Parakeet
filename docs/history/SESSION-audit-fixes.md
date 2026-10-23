# Session: fixing the rest of the audit

This session fixed every remaining finding in sections 1 and 3 of the
[audit](AUDIT.md): 1.1–1.7, 1.9–1.16, and the uncovered syntax. Each was
confirmed by a test that failed before its fix, so none was retracted. It
also made `scripts/check.sh` find Java 21 through mise.

Each finding's status line in AUDIT.md names its commit. Each commit's
message says "Fixes AUDIT.md finding X.Y".

Sections 4 (Inko's `main`) and 5 were out of scope, and so were the type
heuristics.

## Commits, oldest first

| Commit | What |
|---|---|
| 8aba25d | `check.sh` runs Gradle through `mise exec`, and stops if Java isn't 21 |
| e6de6af | 1.1: the code in `${…}` is lexed as code |
| 7479d15 | 1.2, 1.3, and section 3's `mod.CONST` patterns: one pattern scanner for `let`, `for` and `match` |
| bc9a46f | 1.4: keywords are names where Inko's parser takes names |
| 81e590a | 1.5: import build tags and `import extern` |
| 0f5f5da | 1.6: setters and the assignments that call them |
| 767367f | 1.7: `type ref`, and Inko's exact type modifiers |
| de4f8e2 | 1.9: a bare `(self)` imports the module under its last name |
| 8ec0fa4 | 1.10: a trait's required traits, from its declaration |
| 46cb169 | 1.11: `if` expressions vs. guards, impl bounds and build tags |
| dd99a81 | 1.12: Smart Enter adds bodies only where the grammar has them (uses 1.11's check) |
| 603a055 | 1.13: statement boundaries for `-1`, `!x` and a bare `return` |
| 8902b2b | 1.14: constant types from `inko doc`'s signature |
| a09d610 | 1.15: number literals |
| 8332859 | 1.16: Bool actions named the way std names them |
| ee15a32 | Section 3: numeric field names (`@0`) |
| 43dc848 | Section 3: `mut` in `inko doc`'s field signatures |
| 44eee33 | Section 3: an enum's compiler-internal fields |
| f47a97e | Section 3: the Code Style preview is valid Inko |
| (this report) | Commit hashes in AUDIT.md, this report, and its README link |

## `check.sh` and Java

In a shell where mise isn't activated, Gradle used the first `java` on
`PATH` (Java 27 on this machine). `check.sh` now:

- runs every Gradle step through `mise exec --` when `mise` is installed
- checks the Java it will use first, and stops with this message if it isn't 21:

  ```
  check.sh: Gradle needs Java 21, but found 27.
  Install mise (https://mise.jdx.dev) and run 'mise install', or put a Java 21 first on PATH.
  ```

I checked both paths with a clean environment (`env -i`):

- With only `/usr/bin:/bin` on `PATH`, it printed the message above.
- With mise's binary on `PATH` but not activated, the whole check passed.

## How the fixes follow Inko

I read Inko's grammar from `git show v0.21.1:ast/src/lexer.rs` and
`ast/src/parser.rs` (and `compiler/src/docs.rs` for `inko doc`'s JSON). I
didn't modify the checkout. It sits at `main` (v0.21.1-47), so every read
used the tag.

### Fixes that covered several findings

- **InkoLocals' pattern scanner (7479d15)** fixed 1.2, 1.3 and section 3's
  module-qualified constant patterns. The cause was guessing bindings token
  by token. There's now one scanner, following Inko's `pattern()`, for
  `let`, `for` and `match` arms. It handles:
  - identifier and `mut` bindings, with optional type annotations
  - constructor, tuple, array and type patterns
  - or-patterns, wildcards, and `mod.CONST`

  Other rules:
  - A `for` pattern's names belong to the loop's body.
  - `case` only binds names inside a `match` block, and each arm has its
    own bindings.
  - In a type's body, `case` defines a constructor, which binds nothing
    (`define_constructor`).
- **`InkoTokenStream.startsIfExpression` (46cb169)** fixed 1.11, and 1.12's
  `if` cases use it.
  - In Inko's grammar, `if` is also a match guard (`optional_match_guard`),
    an impl's bounds (`optional_type_bounds`), and an import's build tags
    (`build_tags`).
  - All three follow a `case`, `impl` or `import` in the same statement.
- **The lexer's keyword-as-name rule (bc9a46f)** fixed 1.4. It also
  resolved the `noinline` half of section 3's "noinline and keyword method
  names". Inko's parser takes any keyword as a name in three places, and the
  lexer now gives a name token there:
  - a method's name, after the modifiers `define_method` allows, in order:
    `pub`, then `inline`/`noinline`, then `async [mut]`, `move`, `static`,
    `mut` or `extern`
  - a name after `.` (`call_with_receiver`)
  - a named argument's label

  A moving closure's `move` stays a keyword.
- **The lexer's number rule (603a055 and a09d610)** covered two findings in
  two commits:
  - 1.13: `-` before a digit is part of the number (Inko's `minus`)
  - 1.15: only digits after a `.`, and signed exponents

### Each finding's test and fix

**1.1** (`InkoInterpolationTest`: 6 of 7 failed; integration:
`testExtractingInterpolatedCodeStillCompiles`).

- A string with `${…}` is now lexed as `STRING_START`, the code's tokens,
  `STRING_MIDDLE`, and `STRING_END`, as Inko's lexer switches states.
- The lexer's state is how many interpolations are open, so lexing only
  restarts outside strings.
- The token stream pairs a string's first and last parts like brackets,
  so an interpolated string is still one expression.
- These now handle the parts: the quote handler, Extend Selection,
  Introduce Variable/Constant/Parameter, Expression Type, postfix
  templates, and live-template contexts.

**1.2 and 1.3** (`InkoLocalPatternsTest`: 8 of 8 failed; integration:
`testRenamedPatternVariablesStillCompile`). See the shared pattern scanner
above.

**1.4** (`InkoKeywordNamesTest`: 7 of 7 failed; integration:
`testImplementMethodsSeesKeywordNamedMethods`).

- The integration test also failed against the real compiler with the fix
  stashed: Implement Methods offered `next` again.
- Fix: the keyword-as-name rule above. The outline now treats every
  keyword after `fn` as a modifier, since the lexer gives the name as a
  name.

**1.5** (`InkoImportFormsTest`: all 5 that could run on the old code
failed; integration: `testImportsAddedAfterTaggedAndExternImportsStillCompile`).

- An import's range now includes its build tags.
- `import extern "…"` is its own form, with no module.
- New names never go into a tagged import.
- A build tag is a `BuildTag` name, which resolves to nothing.

**1.6** (`InkoSetterTest`: 4 of 4 failed; integration:
`testRenamedSettersStillCompile`).

- `recv.name = x`, with or without spaces, resolves to the setter `name=`.
  When there's no setter, it resolves to the field.
- `InkoSetterReferencesSearcher` finds a setter's calls by the word
  without its `=`. Find Usages, Safe Delete and Rename use that search.
- Renaming keeps the `=` on the declaration and leaves it off the calls.
- The validator accepts `other=` for a setter.

**1.7** (`InkoRefTypeTest`: 3 of 3 failed).

- The outline skips exactly what `define_type` allows: `pub`, then
  `inline`/`copy`/`ref`, then `async`/`enum`/`builtin`/`extern`.
- `let` has its own modifier set.

**1.9** (`InkoImportSelfTest`: 2 of 3 failed; the third guards against
editing the import rewriting `(self)`; integration:
`testNavigatesThroughASelfImport`).

- A bare `self` binds the module's last name
  (`compiler/src/type_check/imports.rs`).

**1.10** (`InkoRequiredTraitsTest`: failed with `inko doc`'s real shape;
integration: `testTypeHierarchyShowsRequiredTraitsFromInkoDoc` failed
against the real compiler with the fix stashed).

- `inko doc`'s JSON has no trait requirements at all, so the index takes
  each trait's header from its declaration in source.
- Reading requirements also skips type parameters, since `Debug[T: Show]`
  doesn't require `Show`.

**1.11** (`InkoIfKindsTest`: its 3 Unwrap tests failed). See
`startsIfExpression` above.

**1.12** (`InkoSmartEnterKindsTest`: the audit's 3 inputs failed; a fourth
checks that an `if` in an arm still gets a body).

- It uses `startsIfExpression` for `if`.
- It leaves `fn extern` alone, whose body is optional
  (`define_module_method`).

**1.13** (`InkoStatementBoundariesTest`: 4 of 5 failed; the fifth checks
that binary operators, `!=` and `throw` still continue a line).

- `-1` is one number.
- A line starting with `!` (but not `!=`) starts a statement.
- A bare `return` at the end of a line ends its statement.

**1.14** (`InkoConstantTypeTest`, with `inko doc`'s `let pub LIMIT: Int`,
failed; integration: `testConstantTypesFromInkoDoc`).

- A constant's type comes from the signature's annotation.
- For definitions read from source, it still comes from the literal.

**1.15** (`InkoNumberLiteralsTest`: 4 of 4 failed).

- Numbers follow `number` and `digits`.
- `10e` is now `10` then `e`, as in Inko.

**1.16** (`InkoBoolActionsTest`: std's own 3 examples were flagged; a
second test checks that predicates still are).

- Methods of `ref` types count as actions, like `mut` and `move` ones,
  since they change shared state without `mut`.
- So do functions that take a `mut` argument to write into.

**Section 3:**

- **Numeric field names** (`InkoNumericFieldsTest`, failed): digits after
  `@`, as Inko's `field` lexes them.
- **Module-qualified constant patterns**: fixed with 1.2.
- **`noinline` and keyword method names, `type ref`**: fixed with 1.4 and
  1.7.
- **`mut` in field signatures** (`InkoFieldSignaturesTest`, failed):
  parameter info strips `let`, `pub`, `mut` and `@`.
- **Compiler-internal enum fields** (`InkoEnumFieldsTest`, failed): built
  from inko doc 0.21.1's real `--private` output, which I generated in a
  scratch project.
  - A type with constructors gets no fields, since enums can't define any.
  - `inko doc`'s `kind` can't be used for this: a `copy enum` is 4, the
    same as any copy type.
- **The Code Style sample**
  (`InkoIntegrationTest.testTheCodeStyleSampleCompiles`, failed with "the
  method 'each' isn't defined for type 'Iter[Int]'"): it uses a `for`
  loop now.

### Existing tests that changed

There was one.

`InkoLexerTest.testStringsWithInterpolationAreOneToken` asserted that an
interpolated string is a single `STRING` token. Finding 1.1 is that this
is wrong.

- It's now `testStringsWithInterpolationSplitAroundTheirCode`.
- It asserts Inko's tokens instead.
- It still checks what it was for: a `}` in a string nested inside an
  interpolation doesn't end the interpolation.

Two assertions in this session's own new tests were narrowed before their
first commit. In neither case did that change whether the finding was
confirmed:

- **`InkoSetterTest`** first asserted that a plain read `c.value`
  resolves only to the getter. It also resolves to the private field
  `@value`, which comes from the out-of-scope receiver narrowing. It now
  asserts that a read doesn't resolve to the setter, which is what 1.6 is
  about.
- **`InkoNumberLiteralsTest`'s last test** first checked the token
  through a copy of Expression Type's literal rule. It now checks the
  token's text directly.

## What's left unverified

- **Extract Method with compound assignment.** I noticed this while
  reading, and didn't run or fix it.
  - `isAssignedAt` only treats `x =` and `x :=` as assignments. So
    extracting `sum += value` probably passes `sum` in as a parameter,
    which Inko doesn't let the method reassign.
  - The audit doesn't list it.
- **Compound setter calls.** `c.value += 1` is `binary_assign_setter` in
  Inko, which calls both the getter and the setter. Parakeet resolves it
  like any other member, not to `value=`. Only `recv.name = x` goes to the
  setter.
- **`x-1`.** It now lexes as `x` and `-1`, as Inko's lexer does. In Inko
  that's a parse error, so Parakeet's operator intentions no longer see a
  subtraction there. I didn't check what `inko check` reports for it.
- **Keyword labels across lines.** A keyword is a named argument's label
  only when `:` follows on the same line. Inko's parser would also take
  `type\n: 1`, but nobody writes that.
- **Section 5's one exception.** `Inko.requiredVersion` still accepts
  repeated spaces in `require inko`, which the compiler rejects. It wasn't
  in this session's list.
- **Sections 4 and 6.** The `main` branch notes, and the audit's
  unverifiable items, are untouched.
- **Platforms.** Everything ran on macOS with `inko` 0.21.1. Nothing ran
  on Linux, or with Inko 0.20.0, the oldest version CI tests.

## How long `check.sh` takes now

On this machine (Apple Silicon, warm Gradle daemon and caches), with 587
tests (511 at the start of the session):

| Run | Time |
|---|---|
| `scripts/check.sh` (from the pre-commit hook, across this session's commits) | 78–89 s, once 106 s |
| `scripts/check.sh` in a shell without mise activated | 83 s |
| `scripts/check.sh --full` | 150 s |

That's about the same as the last session's 78–108 s and 151 s. The new
tests are mostly unit tests, and the new integration tests add only a few
seconds.
