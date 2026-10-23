# Parakeet audit against Inko v0.21.1

The audit target is Inko **v0.21.1**. Separate notes cover `main`
(v0.21.1-47-ge3f181c7).

## How to read this

- **Parakeet paths** are relative to this repository. `P/` is short for
  `src/main/kotlin/com/setdef/parakeet/`.
- **Inko paths** are relative to the root of a checkout of Inko's repository
  (github.com/inko-lang/inko), at `git show v0.21.1:<path>`, unless a finding
  says it's about `main`.
- **How the Inko side was checked:** I read the Inko source. I also checked
  every snippet marked "compiles" with the installed `inko 0.21.1`
  (`inko check`, `inko doc --private`) in a scratch project.
- **How the Parakeet side was checked:** I read the code and traced each
  path. I didn't run the IDE, and I didn't run Parakeet's tests.
- **Scope:** the type heuristics are out of scope (receiver narrowing, call
  resolution, parameter hints). Where a finding touches them, the cause is a
  lexer, PSI, or JSON-shape problem.

Findings are ranked by user impact:

1. wrong behaviour on valid code
2. errors and silent failures
3. syntax used in `std/src` that Parakeet doesn't cover
4. what changes on `main`

---

## 1. Wrong behaviour on valid code

### 1.1 Names inside string interpolation are invisible (High)

**Status: confirmed and fixed in <commit:1.1>.** `InkoInterpolationTest` failed
before the fix (6 of its 7 tests). The lexer now splits an interpolated
string around its code, as Inko's lexer does (`STRING_START`, the code's
tokens, `STRING_MIDDLE`, `STRING_END`), so names in `${…}` are PSI names. See
[SESSION-audit-fixes.md](SESSION-audit-fixes.md).

**What Parakeet assumes.** A string, including any `${…}` inside it, is one
opaque `STRING` token (`P/InkoLexer.kt:84-87` and `P/InkoLexer.kt:158-190`).
Only `NAMES` tokens become `InkoName` PSI (`P/InkoPsi.kt`, `InkoParser`).
The words scanner treats strings as literals (`P/InkoFindUsages.kt`,
`getWordsScanner`).

**What Inko does.** `${` switches the lexer back to normal tokens, so
interpolated code is ordinary expressions: `StringExprOpen`, `Identifier`,
and so on (`ast/src/lexer.rs:531-546` and `ast/src/lexer.rs:1128-1137`). Std
has 132 interpolations that start with a name.

```inko
fn greet(name: String) -> String {
  let greeting = 'Hello'
  '${greeting}, ${name}!'
}
```

**Consequences:**

- Rename `name` or `greeting`: the interpolations aren't updated.
- Find Usages and Highlight Usages miss them.
- Safe Delete sees no usages.
- Inline Variable on `greeting` deletes the `let` and leaves
  `${greeting}` dangling. It removes the declaration after replacing only the
  `InkoUsages.inFile` names (`P/InkoInlineVariable.kt`, `InkoInlineVariableHandler`).
- Extract Method of the last line makes a method that doesn't take `name` or
  `greeting` as parameters (`P/InkoExtractMethod.kt:112-125` only sees
  `IDENTIFIER` tokens).
- Introduce Parameter misses locals that are used only inside a string.

### 1.2 `for` loop variables and destructuring `let` patterns aren't locals (High)

**Status: confirmed and fixed in <commit:1.2>, with 1.3 and section 3's
module-qualified constant patterns.** `InkoLocalPatternsTest` failed before
the fix (all 8 tests). `InkoLocals` now reads `let`, `for` and `match` arm
patterns with one scanner that follows Inko's `pattern()`, and a `for`
pattern's names belong to the loop's body.

**What Parakeet assumes.** `InkoLocals.scan` declares locals for only three
things:

- parameters after `fn`
- `let name` and `let (a, b)` (one level deep)
- identifiers after `case`

See `P/InkoLocals.kt:85-133`. There's no branch for `for`. A `let` whose
pattern starts with a constant, `{`, or `[` falls into `else -> {}`
(`P/InkoLocals.kt:115`). Nested tuples are skipped, because
`parameters()` only takes depth-1 identifiers.

**What Inko does.** Both `for` and `let` take a full pattern
(`ast/src/parser.rs:3034-3054` and `ast/src/parser.rs:2426-2440`, which use
`pattern()` at `ast/src/parser.rs:2774`). Std has 84 `for` loops, 24 `let`
patterns of the constructor, type, or array kind (for example
`std/src/std/net/tls.inko:821` and `std/src/std/log.inko:201`), and tuple
`for` patterns (`std/src/std/mime.inko:185`).

```inko
fn total(items: Array[Int]) -> Int {
  let mut sum = 0
  for (index, value) in items.iter.with_index { sum += value + index }
  let Some(first) = items.first else return 0
  let (a, (b, c)) = (1, (2, 3))
  sum + first + a + b + c
}
```

Compiles. None of `index`, `value`, `first`, `b`, or `c` is a local to
Parakeet, so:

- They're not `InkoDeclaration`s, so Rename and Find Usages don't work on
  them.
- Each use falls through to `InkoNameContext.Identifier`. It then resolves
  to any same-named method on `self` or in the module, which makes it a
  false "usage" of that method in Find Usages, Rename, and Call Hierarchy.
- Extract Method and Introduce Parameter don't see them as inputs. Introduce
  Parameter's "uses a variable callers don't have" check passes when it
  shouldn't.
- Completion and Expression Type don't know them.

### 1.3 Enum constructors turn the next method into a "local variable" (High)

**Status: confirmed and fixed in <commit:1.2>, by the same change as 1.2.**
`case` only binds names inside a `match` block; in a type's body it defines
a constructor, which binds nothing. `InkoLocalPatternsTest` covers both.

**What Parakeet assumes.** In the `case` branch, `InkoLocals` treats every
`IDENTIFIER` up to the next `->` (or `if`) as a binding
(`P/InkoLocals.kt:119-132`), with no notion of a type body. In
`type enum X { case A(Int) … fn area -> Float { … } }`, the scan runs from
the first `case` to the `->` of the first method with a return type. That
method's name is declared as a local. Any `{` and `}` in between are
skipped, which also throws off scope tracking for the rest of the file.

**What Inko does.** `case` in a type body is a constructor definition with
no bindings (`ast/src/parser.rs:1100-1122` and `ast/src/parser.rs:1177-1202`).
Bindings exist only in `match` arms (`ast/src/parser.rs:2735`).

```inko
type pub enum Shape {
  case Circle(Float)
  case Square(Float)

  fn pub area -> Float {
    0.0
  }
}
```

Compiles. `InkoDeclaration.isLocal` (`P/InkoPsi.kt:61`) is then true for
`area`, so:

- its use scope is the current file (`P/InkoPsi.kt:63`)
- rename is in place and only within the file (`P/InkoFindUsages.kt:125-126`)
- Find Usages misses callers in other modules
- the usages code vision skips it
- Find Usages labels it "local variable"

This applies to every enum that defines a method with `->` after its
constructors (31 std files define enums).

### 1.4 Keyword-named methods and keyword names after `.` don't exist for Parakeet (High)

**Status: confirmed and fixed in <commit:1.4>.** `InkoKeywordNamesTest`
failed before the fix (all 7 tests), and
`InkoIntegrationTest.testImplementMethodsSeesKeywordNamedMethods` showed the
real `inko doc` index offering `next` again. The lexer now gives a keyword
as a name where Inko's parser takes one: a method's name (after the
modifiers `define_method` allows, in order), a name after `.`, and a named
argument's label. The outline then finds these methods, including
`fn pub copy` and `fn pub noinline foo`.

**What Parakeet assumes.**

- Every keyword is a `KEYWORD` token, wherever it appears
  (`P/InkoLexer.kt:35-41` and `P/InkoLexer.kt:103`), and keyword tokens never
  become `InkoName`.
- The outline needs an `IDENTIFIER`, `CONSTANT`, or operator after `fn` and
  its modifiers (`P/InkoOutline.kt:111-121`).
- `copy` is in the outline's modifier set (`P/InkoOutline.kt:49`), so
  `fn pub copy[…]` loses its name.
- `noinline` isn't in that set, so `fn pub noinline foo` has no name either.

**What Inko does.**

- A method name may be any keyword, an identifier, a constant, or an integer
  (`ast/src/parser.rs:1000-1031`).
- A name after `.` may be a keyword or an integer
  (`ast/src/parser.rs:2078-2097`).
- A named argument's label may be a keyword (`ast/src/parser.rs:2051-2056`).

Std defines 59 methods named `next`, `or` and `else` on `Option` and
`Result`, `copy` (`std/src/std/fs/path.inko:798`), `true` and `false`
(`std/src/std/test.inko:162` and `std/src/std/test.inko:169`), and two
`noinline` methods. It calls `.next` 105 times.

```inko
import std.iter (Iter)

type Countdown {
  let mut @n: Int
}

impl Iter[Int] for Countdown {
  fn pub mut next -> Option[Int] {
    if @n == 0 { return Option.None }
    @n -= 1
    Option.Some(@n)
  }
}

fn first -> Int {
  Countdown(n: 3).next.or(0)
}
```

Compiles. Consequences:

- `next` is missing from the Structure view, breadcrumbs, the outline, and
  `InkoSourceDefinitions`. It isn't an `InkoDeclaration`, so it can't be
  renamed, found, or deleted.
- **Implement Methods offers `next` again for an impl that already defines
  it.** `InkoTraitImpl.implemented` is built from outline children
  (`P/InkoImplementMethods.kt:25`). Accepting the offer adds a duplicate
  method, which doesn't compile.
- `.next`, `.or`, and `.else` calls have no reference, so they can't be
  navigated to and don't show in usages.
- **Extract Method refuses any selection that contains `x.next`**, saying it
  "uses `next` for a loop outside it" (`P/InkoExtractMethod.kt:83`).
- Named arguments with keyword labels (`f(type: 1)`) are read as positional
  by Change Signature, Inline Method, and the argument-name intentions
  (`P/InkoSignatures.kt:131` and `P/InkoIntentions.kt:129`).
- A keyword-named method directly in a `type` body isn't a method node. Its
  `match` arms then count as constructor definitions
  (`P/InkoSourceDefinitions.kt:74-80`), so `Some` in such an arm becomes a
  declaration rather than a reference (`P/InkoPsi.kt:85`).

### 1.5 Adding an import breaks files that use build tags or `import extern` (Medium–High)

**Status: confirmed and fixed in <commit:1.5>.** `InkoImportFormsTest`
failed before the fix (all 5 tests that could run on the old code). An
import's range now includes its build tags, `import extern "…"` is its own
form that imports no module, and new names are never added to a tagged
import. Build tags are no longer read as imported symbols.
`InkoIntegrationTest.testImportsAddedAfterTaggedAndExternImportsStillCompile`
compiles the result.

**What Parakeet assumes.** An import is
`import path [ (symbols) ]` and nothing more. The regex is at
`P/InkoImports.kt:41`. New imports are inserted right after the last
statement's range (`P/InkoImportFixes.kt:25` and `P/InkoPasteImports.kt:138`).

**What Inko does.**

- An import may end with `if tag and tag` (`ast/src/parser.rs:127-150` and
  `ast/src/parser.rs:240-268`). Std has 40 tagged imports, for example
  `std/src/std/env.inko:26`.
- `import extern "m"` is a separate form (`ast/src/parser.rs:271-309`). The
  regex reads it as a module named `extern`.

```inko
import std.stdio (Stdout)
import std.sys.unix.env (self as sys) if unix

fn main {
  Helper.new   # Alt+Enter → "Import Helper from app.util"
}
```

The result is `import std.sys.unix.env (self as sys)` followed by
`import app.util (Helper) if unix`. The tag now belongs to the new import,
and the old one became unconditional.

When `import extern "m"` is the last import, the result is
`import extern` followed by `import app.util (Helper) "m"`, a syntax error.

The same insertion is used by the auto-import quick fix, completion of
unimported names, imports on paste, Move, Inline Method, Introduce
Parameter, and Implement Trait.

One more problem: adding a symbol to an existing import with a tag puts the
new name under that tag.

### 1.6 Setter methods (`fn value=`) are disconnected from their calls (Medium)

**Status: confirmed and fixed in <commit:1.6>.** `InkoSetterTest` failed
before the fix (all 4 tests). `recv.name = x` (with or without spaces) now
resolves to the setter `name=`, as Inko's `assign_setter` does, and to the
field only when there's no setter. A references searcher finds a setter's
calls by the word without its `=`; renaming keeps the `=` on the
declaration and leaves it off the calls; the rename validator accepts
`other=` for a setter. Find Usages, Safe Delete and Highlight Usages use the
same search.

**What Parakeet assumes.** `value=` is one name token, but only right after
`fn` (`P/InkoLexer.kt:136` and `P/InkoLexer.kt:143-155`). A call site
`x.value = 1` lexes as `value`, `=`. The rename validator rejects `=` in
names (`P/InkoFindUsages.kt:99`).

**What Inko does.** The parser appends `=` to the method name
(`ast/src/parser.rs:1016-1020`). It also parses `recv.name = value` as an
assign-setter call of that method (`ast/src/parser.rs:2099-2101` and
`ast/src/parser.rs:2210`). `inko doc` names the method `value=`, as seen in
the scratch project's JSON. Std defines 54 setters, for example
`timeout_after=`.

```inko
type pub Counter {
  let mut @value: Int

  fn pub mut value=(v: Int) {
    @value = v
  }
}

fn bump(c: mut Counter) {
  c.value = 2
}
```

Compiles. Consequences:

- Find Usages of `value=` finds nothing.
- Safe Delete deletes a setter that's still called.
- It can't be renamed to `other=`.
- Go to Declaration from `c.value = 2` can't reach the setter.

### 1.7 `type ref` types disappear, and their methods become module functions (Medium)

**Status: confirmed and fixed in <commit:1.7>.** `InkoRefTypeTest` failed
before the fix (all 3 tests). The outline now skips exactly the modifiers
Inko's `define_type` allows (`pub`, `inline`/`copy`/`ref`,
`async`/`enum`/`builtin`/`extern`), so a `ref` type is a type and its
methods are its own. `let` has its own modifier set (`pub`, `mut`).

**What Parakeet assumes.** After `type`, the outline skips modifiers from a
set that doesn't include `ref` (`P/InkoOutline.kt:49` and
`P/InkoOutline.kt:98-101`). So the name isn't found, no type node is opened,
and the `fn`s in its body become top-level `METHOD` roots.

**What Inko does.** `type pub ref X` is an atomically reference-counted
type (`ast/src/parser.rs:1048-1051`). Examples are `std.sync.AtomicInt` and
`AtomicBool` (`std/src/std/sync.inko:666`) and `std.net.tls.ClientConfig`
and `ServerConfig`.

```inko
type pub ref Shared {
  let @count: Int

  fn pub count -> Int {
    @count
  }
}
```

Compiles. Consequences:

- `Shared` is missing from the Structure view, breadcrumbs, and Generate.
- `count` is listed and indexed (`InkoSourceDefinitions`) as a module
  method.
- `self` and `@field` resolution inside the type fail
  (`InkoLocals.selfType` returns null).
- Copy Reference, Move, and Safe Delete treat the methods as top-level
  functions.

### 1.8 Compiler columns are grapheme clusters, but Parakeet reads them as UTF-16 offsets (Medium)

**Status: confirmed and fixed in 5f27654.** See
[SESSION-guardrails-and-compiler-output.md](SESSION-guardrails-and-compiler-output.md).

**What Parakeet assumes.** `offset = lineStart + column - 1`
(`P/Inko.kt:52-57`). This is used for diagnostics
(`P/InkoCheckAnnotator.kt:42-46`), quick-fix anchors, Inspect Code
(`P/InkoProjectCheck.kt`), and `inko doc` locations.

**What Inko does.** The lexer advances columns by
`graphemes(true).count()` (`ast/src/lexer.rs:518-520`).

```inko
fn pub a -> Int {
  let s = '😀😀é' + nope
  1
}
```

`inko check` reports `nope` at column 19. Its UTF-16 column is 22. So the
error underlines `+ n`, three characters to the left. Fixes that insert text
at the reported start use the same wrong offset, for example
`DiscardResultFix` (`P/InkoQuickFixes.kt:82`).

### 1.9 `import a.b (self)` is taken to bind the name `self` (Medium–Low)

**What Parakeet assumes.** The module alias is the local name of the `self`
entry (`P/InkoImports.kt:15-18`). For a bare `(self)`, that local name is
`"self"`.

**What Inko does.** `(self)` without `as` imports the module under its last
segment (`compiler/src/type_check/imports.rs:57-63` and
`compiler/src/type_check/imports.rs:78-82`). The style guide recommends
`import std.fs.file (self, ReadOnlyFile)` (`docs/source/references/style.md`,
Imports).

```inko
import std.fmt (self, Format)

fn show(v: Int) -> String {
  fmt.fmt(v)
}
```

Compiles. Consequences:

- `fmt.` isn't recognised as a module alias, so `fmt.fmt` can't be
  navigated to and member completion is wrong.
- Imports on paste don't carry the import over.
- Move doesn't rewrite `fmt.` qualifiers.

### 1.10 Type Hierarchy can't find required traits from `inko doc` (Medium–Low)

**What Parakeet assumes.** A trait's supertypes are parsed out of its
signature after `:` (`P/InkoTypeHierarchy.kt:45-50` and
`P/InkoTypeHierarchy.kt:54`). The test feeds the fake signature
`"trait pub Debug: Show"` (`src/test/kotlin/com/setdef/parakeet/InkoTypeHierarchyTest.kt:35`).

**What Inko does.** `inko doc` writes `trait{pub} Name[Params]` with no
requirements (`compiler/src/docs.rs:523` and `types/src/format.rs:316-321`).
For `trait pub Debug: Show {}`, the scratch run produced
`'trait pub Debug'`.

**Consequence.** Trait-requires-trait edges only appear for files read from
source (unsaved or non-compiling). In a project that compiles, they're
missing. That contradicts what the README promises.

### 1.11 Unwrap/Remove treats match guards and conditional impls as `if` statements (Low–Medium)

**What Parakeet assumes.** `governingKeyword` walks back from a `{` to the
nearest keyword in `CONSTRUCTS`, which includes `if`
(`P/InkoUnwrap.kt:56` and `P/InkoUnwrap.kt:93-115`).

**What Inko does.** `if` is also:

- a match guard (`ast/src/parser.rs:2966-2980`)
- an impl bound list (`ast/src/parser.rs:1266-1307`), as in
  `impl Equal for Array if T: Equal` (`std/src/std/array.inko:1061`)

```inko
fn f(x: Option[Int]) {
  match x {
    case Some(n) if n > 1 -> {
      print(n)   # Unwrap/Remove offers "Unwrap 'if...'"
    }
    case _ -> {}
  }
}
```

Choosing that option replaces `if n > 1 -> { print(n) }` with `print(n)`,
which leaves `case Some(n) print(n)`.

Inside `impl Foo for Bar if T: X { … }`, the same option deletes the bound
and the braces.

### 1.12 Complete Current Statement adds a body after the wrong `if` (Low)

**What Parakeet assumes.** The last "bodied" keyword on the line gets
` { … }` (`P/InkoStatementEditing.kt:62` and
`P/InkoStatementEditing.kt:116`). The same `if` confusion as 1.11 applies.

**What Inko does.** See 1.5 for build tags and 1.11 for guards.

**Failing input:**

- `import std.stdio (Stdout) if uni|x` with ⇧⌘⏎ gives
  `import … if unix {\n  \n}`.
- On `case Some(n) if n > 1 -> foo(n)`, it appends a block after the arm.
- On `fn extern ceil(value: Float64) -> Float64`, it adds a body, which
  turns a C binding into an empty Inko function (`ast/src/parser.rs:847-904`, where a body is optional only for `extern`).

### 1.13 Statement boundaries disagree with the parser in three cases (Low)

**What Parakeet assumes.** A line continues the previous statement when any
of these hold (`P/InkoTokenStream.kt:146-153`):

- it starts with any `OPERATOR` token, including `-` and `!`
- the previous line ends in `return`, `throw`, `try`, `ref`, `mut`, or
  `move`

**What Inko does.**

- `-1` is a single integer token, because `-` followed by a digit is part of
  the number (`ast/src/lexer.rs:890-893`). It's not a binary operator, so
  `binary()` stops (`ast/src/parser.rs:1623-1658`).
- `!x` starts a new `Not` expression (`ast/src/parser.rs:3008-3019`).
- A bare `return` only takes a value on the **same line**
  (`ast/src/parser.rs:2569-2608`).

```inko
fn sign(x: Int) -> Int {
  if x > 0 { return 1 }
  -1
}
```

Parakeet merges `-1` into the `if` statement. This affects Move Statement,
Extract Method's statement mode, where Introduce Variable puts its `let`,
Inline Method's body statements, and Smart Enter.

### 1.14 Constant types from `inko doc` are never recognised (Low)

**What Parakeet assumes.** A constant's signature is `let pub X = value`, and
the value after `=` gives its type (`P/InkoExpressionType.kt:205`). The tests
fake that shape, for example
`src/test/kotlin/com/setdef/parakeet/InkoCompletionTest.kt:35`.

**What Inko does.** `inko doc` writes `let pub LIMIT: Int`
(`compiler/src/docs.rs:354`). The scratch run confirmed this.

```inko
let pub LIMIT = 10

fn twice -> Int {
  LIMIT * 2
}
```

**Consequence.** Expression Type and Specify Type say "Unknown" for
`LIMIT` whenever the definition comes from `inko doc`.

### 1.15 Two number-literal forms lex differently (Low)

**What Parakeet assumes.** A number continues through letters, digits, `_`,
and `.` followed by a digit (`P/InkoLexer.kt:89-93`).

**What Inko does.**

- After a `.`, only digits are lexed (`ast/src/lexer.rs:623` and
  `ast/src/lexer.rs:761-770`), so nested tuple access `pair.0.1` works
  (added in 0.21.0).
- Exponents may be signed (`ast/src/lexer.rs:736-746`).

```inko
fn f -> Int {
  let pair = ((1, 2), 3)
  pair.0.1 + 10e+2.to_int
}
```

Compiles. Parakeet lexes `0.1` as a float, so `pair.0.1` reads as a float
member of `pair`. It lexes `10e+2` as `10e`, `+`, `2`. Expression Type,
Introduce Variable, Extend Selection, and the operator intentions all work
on those wrong tokens.

### 1.16 The Bool-method inspection flags names std uses on purpose (Low)

**What Parakeet assumes.** Any non-`mut`, non-`move` method returning `Bool`
should end in `?` (`P/InkoStyleInspections.kt`,
`InkoBooleanMethodNameInspection`).

**What Inko does.** Std has `fn pub load -> Bool` and
`fn pub compare_and_swap(…) -> Bool` on `ref` types
(`std/src/std/sync.inko:702`, `std/src/std/sync.inko:825`, and
`std/src/std/sync.inko:857`), and `fn pub decode(…) -> Bool`
(`std/src/std/uri.inko:285`). The style guide states the rule, but std
breaks it for actions that report success.

This is only a weak warning. I'm noting it so the rule can exempt names
like these.

---

## 2. Errors and silent failures

### 2.1 Every check and index rebuild may run a full `inko pkg sync` in a throwaway mirror (Medium)

**Status: confirmed and fixed in abe590c.** See
[SESSION-guardrails-and-compiler-output.md](SESSION-guardrails-and-compiler-output.md).

**What Parakeet assumes.**

- `inko check` and `inko doc` are read-only.
- The mirror links `dep/` only if it exists (`P/InkoMirror.kt:80`), and the
  mirror is deleted after each run.
- `inko check` runs per keystroke (`P/InkoDiagnostics.kt:34`), after saves
  (`P/InkoProjectCheck.kt:71`), and `inko doc` runs on every save
  (`P/InkoDocIndex.kt:150`).

**What Inko does.** `check`, `build`, and `document` all call `prepare()`
(`compiler/src/compiler.rs:259`, `compiler/src/compiler.rs:312`, and
`compiler/src/compiler.rs:377`). `prepare()` calls `sync_if_needed(cwd/dep)`
(`compiler/src/compiler.rs:780-797`). That syncs whenever
`dep/hash != blake3(inko.pkg)` (`compiler/src/pkg/sync.rs:22-31` and
`compiler/src/pkg/sync.rs:229-245`).

I confirmed this with a scratch project that had a dependency and no
`dep/`: `inko check --format json src/app.inko` printed
`Downloading … v1.0.0` and created `dep/` in the working directory.

**Consequences.** While `dep/` is missing or stale (a fresh clone, or a
saved `inko.pkg` edit before syncing):

- every keystroke check and every index rebuild re-installs every package
  into a temporary directory
- with no network, or if a package or tag is missing, `prepare` fails with a
  plain-text error instead of JSON. `InkoCheck.parse` then returns nothing
  (`P/InkoDiagnostics.kt:40-46`), so diagnostics silently disappear.
- the persistent test mirror re-syncs on every run, because its `dep` link
  is replaced each time (`P/InkoMirror.kt:51-55`)

The same silent failure happens when `inko.pkg` requires a newer Inko
(`compiler/src/compiler.rs:780-794`). The banner covers that case, but the
annotator doesn't explain it.

### 2.2 One malformed diagnostic drops all of them (Low–Medium)

**Status: confirmed and fixed in bc5b679.** See
[SESSION-guardrails-and-compiler-output.md](SESSION-guardrails-and-compiler-output.md).

**What Parakeet assumes.** `inko check --format json` output is valid JSON.
If parsing fails, Parakeet returns no diagnostics (`P/InkoDiagnostics.kt:40-46`).

**What Inko does.** It builds the JSON with Rust `{:?}` formatting
(`compiler/src/presenters.rs:103-116`), which escapes control characters as
`\u{1}`. That isn't valid JSON.

```inko
fn pub a -> String {
  '\<U+0001>'
}
```

Here `<U+0001>` stands for a literal control character. `inko check` emits
`"message": "the escape sequence '\\\u{1}' is invalid"`, and Python's
`json.loads` rejects it.

**Consequence.** For this file (and, in the project-wide check, for the whole
project), no errors or warnings are shown. That includes the syntax error
itself.

This is an Inko bug, but Parakeet could fall back to the text format or fix
up `\u{…}` escapes.

---

## 3. Syntax used in `std/src` that Parakeet doesn't cover

- **Numeric field names.** `let @0: Uint8` (`std/src/std/int.inko:79`) and
  the tuple fields in `std/src/std/tuple.inko:61`. Inko's field lexer
  accepts any identifier bytes after `@`, including digits
  (`ast/src/lexer.rs:772-782`). Parakeet requires a letter or `_`
  (`P/InkoLexer.kt:95`), so `@0` lexes as two operator and number tokens.
  These fields aren't fields to the outline, completion, or
  `InkoDefinitionSite`.
- **Constant patterns with a module qualifier.** *Status: confirmed and fixed
  in <commit:1.2>, by the fix for 1.2 (`testModuleQualifiedConstantPatternsBindNothing`).* `case mod.CONST ->`
  (`ast/src/parser.rs:2803-2817`). Parakeet's `case` scan declares `mod` as
  a binding (`P/InkoLocals.kt:124-128`), because only the name after the dot
  is excluded.
- **`noinline` and keyword method names** (see 1.4), and **`type ref`**
  (see 1.7). *Status: fixed with 1.4 (<commit:1.4>) and 1.7 (<commit:1.7>).*
- **Field signatures from `inko doc` include `mut`.** The format is
  `let pub mut @x: T` (`compiler/src/docs.rs:626`). `InkoCalls.parameters`
  strips `let ` and `pub ` but not `mut ` (`P/InkoParameterInfo.kt:49`). So
  `Type(…)` parameter info shows `mut @count: Int`, and the derived names
  keep `@`.
- **Compiler-internal enum fields under `--private`.** `inko doc --private`
  lists them, for example `let @tag: Uint8` and `let @1: <unknown>` on
  `type enum Shape` (seen in the scratch run). Parakeet indexes them as real
  fields.
- **Invalid code sample.** The Code Style preview calls `Iter.each`
  (`P/InkoCodeStyle.kt:42`), which doesn't exist in v0.21.1
  (`std/src/std/iter.inko`).

---

## 4. What changes against `main`

- **`type extern(packed) Foo`** is new syntax (parser diff around
  `define_type`, `main:ast/src/parser.rs:1069-1088`). Parakeet's outline
  sees `extern` and then `(`, finds no name, and drops the type
  (`P/InkoOutline.kt:98-101`).
- **`inline [ … ]` arrays** (`main:ast/src/parser.rs:1762` and
  `main:ast/src/parser.rs:1908-1955`):
  - Postfix templates stop at the space before `[` (`P/InkoPostfixTemplates.kt:84`).
    `inline [1, 2].let` becomes `inline let value = [1, 2]`.
  - Introduce Variable on `[1, 2]` leaves `inline size`
    (`P/InkoIntroduceVariable.kt:27`).
  - `inline [3 * x]` is parsed by Parakeet as multiplication.
- **Skipped tests.** On `main`, `Reporter.skipped` is required and
  `Test.skip(reason)` exists (`git diff v0.21.1..HEAD -- std/src/std/test.inko`).
  Parakeet detects `skipped` and reports `testIgnored` without the reason
  (`P/InkoTestRunner.kt:63`). That doesn't break anything, but the reason
  (`test.skipped`) is lost.
- **Integer type arguments in signatures.** These now appear, as in
  `InlineArray[Int, 3]` (`main:types/src/format.rs`, `TypeRef::Int`).
  Nothing in Parakeet breaks, but `inferType` and `typeName` can't produce
  them.
- **No change** on `main` to the doc JSON (`compiler/src/docs.rs`), the
  diagnostics JSON, the `inko` CLI options, the manifest, or the
  `inko test` main generator.

---

## 5. Things I checked that agree with Inko

- **Keywords.** The keyword set is identical: `P/InkoLexer.kt:35-41` versus
  `ast/src/lexer.rs:982-1066`. Identifiers allow `$` and a trailing `?`
  (`ast/src/lexer.rs:1162-1176`).
- **Precedence.** One precedence for binary operators, left to right; `as`
  at that level; `and`/`or` looser (`ast/src/parser.rs:1592-1658`), as the
  README says.
- **CLI options:**
  - `check --format json [FILE]`: diagnostics go to stderr, and the end
    column is inclusive.
  - `doc --private --dependencies` writes `build/docs/*.json` plus
    `$meta.json`, which Parakeet ignores (`compiler/src/config.rs:154` and
    `compiler/src/docs.rs`).
  - `fmt -` uses stdin and stdout.
  - `print std` and `print target` exist.
  - `targets` marks the native target as `(native)`.
  - Per-command flags match `InkoCommand`: run takes `--release` and
    `--static`; test takes `--release` and `--target`; check takes
    `--target`; build takes all three (`inko/src/command/*.rs`).
- **Doc JSON keys** used by `P/InkoDefinitions.kt`: `name`, `file`,
  `documentation`, `constants`, `methods`, `types[*].constructors|fields|static_methods|instance_methods|implemented_traits`,
  `traits[*].required_methods|default_methods|implementations`, `location`,
  `public`, and `type`.
- **Quick-fix diagnostic IDs and messages** all match
  `compiler/src/diagnostics.rs`: `invalid-symbol`, `unused-symbol`,
  `unused-result`, `missing-field`, `invalid-implementation`,
  `missing-trait`, and `unreachable`.
- **Manifest parsing** matches `compiler/src/pkg/manifest.rs:200-282`:
  `split(' ')`, comments after trimming, the 40-character checksum, and
  version rules. `dep/hash` is `blake3` of the raw `inko.pkg` bytes
  (`compiler/src/pkg/sync.rs:202-211`). Dependency import names are the URL
  tail without `inko-` (`compiler/src/pkg/manifest.rs:73-90`), resolved by
  module head (`compiler/src/state.rs:104`).

  The one exception: `Inko.requiredVersion` (`P/Inko.kt:65`) accepts
  repeated spaces, which the compiler rejects.
- **Test runner.** Parakeet's generated program uses the same imports and
  calls, and the same `Filter.from_string(arg0)`, as
  `generate_main_test_module` (`inko/src/command/test.rs:178-211`).
  Child-process tests still work, because `Tests.run` handles `CHILD_VAR`
  itself (`std/src/std/test.inko:677`). Parakeet doesn't parse `inko test`
  output at all: it reports through TeamCity messages from its own
  `Reporter`.
- **Live templates, file templates, and surround templates**
  (`src/main/resources/liveTemplates/Inko.xml`) all expand to valid Inko.
  One inconsistency: `trait` expands to `trait pub`, while `fn` and `type`
  stay private.
- **Import sorting.** `inko fmt` sorts consecutive imports by path
  (`compiler/src/format.rs:578-606`), which Optimize Imports relies on.

---

## 6. What I couldn't verify

- **No Parakeet code was run.** All Parakeet-side behaviour comes from
  reading the code. In particular, I didn't confirm these in an IDE:
  - the IDE's reaction to 1.3 (scope corruption past skipped braces)
  - how `DefaultWordsScanner` splits `value=` (1.6)
- **Gson behaviour.** I didn't check that Gson's `JsonParser.parseString`
  rejects `\u{1}` (2.2). Python's parser does, and Gson's should, which would
  surface as a `JsonSyntaxException` that gets caught.
- **Symlinked `dep` in the persistent mirror.** I didn't test what
  `remove_dir_all` does to the symlinked `dep` there (2.1). Rust documents
  that it removes the link without following it.
- **`main`.** I didn't build or test Parakeet's runner against `main`. The
  section 4 notes come from source diffs.
- **Platforms.** I didn't check Windows, CRLF source files, or tabs in
  column math. Tabs count as one column in both, so they should be fine.
- **`inko fmt` import sorting.** I didn't check whether a comment between
  imports splits a sort group the way Optimize Imports assumes.
- **Trait locations.** `inko doc` reported trait locations as line 1,
  column 1 in the scratch run. `InkoDefinitionSite` searches by name, so it
  copes, but I didn't confirm whether this happens for every trait.
- **Undocumented compiler behaviour.** Anything Inko does only in compiler
  passes I didn't read: the type checker and MIR, which matter only for the
  out-of-scope type heuristics.
