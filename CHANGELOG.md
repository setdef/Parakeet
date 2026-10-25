# Changelog

## [Unreleased]

### Added

- **Cuttlebone**, a language server for Inko, gives the compiler's own answers where Parakeet guessed. With `cuttlebone` installed (on PATH, or set in *Settings → Languages & Frameworks → Inko*), Parakeet starts it per project through IntelliJ's LSP support and asks it first: a method call like `x.size` resolves to the method the compiler calls (for go to definition, the documentation popup, parameter info and hints, Find Usages and everything else built on references), member completion after `.` lists exactly the receiver's members, the documentation popup shows the type at the caret ("Type here"), and Expression Type shows types the code doesn't state. Parakeet requires it: without it, what needs the compiler's types is unavailable instead of guessed, and a notification says once how to set it up. Only where Cuttlebone can't check the code being edited (it doesn't parse there, or a declaration has an error) do some features read the code themselves.
- **Go to Type Declaration** (⇧⌘B / Ctrl+Shift+B), from IntelliJ's LSP support, when Cuttlebone is installed.
- With Cuttlebone's fifth milestone, its answers in code being edited: from a copy with the broken lines blank (used like any other answer), or from the last version that type checked ("stale"). Stale answers are shown only where nothing changes code, marked possibly outdated: hints (in their tooltip), parameter info, the type in the documentation popup, and Expression Type; usage highlighting uses them too. Resolving names (and so Rename, the refactorings, quick fixes and imports) and completion never use them.
- With a Cuttlebone that has them: **type hints** for `let` bindings and closure arguments (*Settings → Editor → Inlay Hints → Types*), parameter info showing the compiler's signature with the receiver's type arguments filled in and the right parameter for named arguments, and usage highlighting that tells assignments from reads.

### Changed

- Where Cuttlebone can't check the code being edited, parameter-name hints, Expression Type, and "Specify type explicitly" and "Remove explicit type" show nothing instead of Parakeet's guess (the last two now always use the compiler's type), and go to definition, documentation and the hierarchies use Cuttlebone's stale answer before Parakeet's own reading. What changes code (refactorings, intentions, quick fixes, imports) only uses the compiler's fresh answers.

- Requires IntelliJ 2025.3 or newer (the first with the LSP API in every IDE), and is now verified against it.
- Parameter-name hints ask Cuttlebone once per file instead of once per call.
- Find Usages, Rename, Call Hierarchy, usage counts and the refactorings built on them find uses by the compiler's ids: Cuttlebone's `textDocument/references`, once per search for the whole project (asking about each occurrence had made Find Usages of a common name take a minute in a large project). "Possible usages" only appear in code Cuttlebone can't check, and only in Find Usages; Rename lists such places as conflicts instead of renaming them. Find Usages of a trait's method includes the calls to its implementations, and renaming a trait's method renames the methods implementing it (and the other way round).

### Fixed

- Typing no longer makes Cuttlebone check text nobody will see. Since its seventh milestone, Cuttlebone answers every question for the text it was asked about, even after an edit: Parakeet now cancels the questions an edit leaves nobody waiting for, and usage highlighting (which ran on every keystroke, for the name being typed) asks once typing pauses. Typing a method into std's `json.inko` at about 7 characters a second asked Cuttlebone about 330 questions, each a check of its own, and now about 90 (about 60 checks); hints and parameter info are as quick as before, and usage highlighting shows once typing pauses.
- Usage highlighting waits up to 2 s, as inlay hints do, instead of 0.4 s: a file's first question pays its whole check, which on a busy machine took longer than 0.4 s, so the highlights didn't show (and a test failed now and then). Moving the caret no longer cancels the question it was waiting for, which made Cuttlebone discard every check it had kept.
- Renaming a field renames it where a constructor sets it by name (`Point(x: 1)`), and Change Signature no longer renames a named argument of a call to another method in the same file.
- Every LSP feature IntelliJ would run for Cuttlebone, other than Go to Type Declaration, is off, so none appears twice (inlay hints and parameter info did), and Cuttlebone's own diagnostics are turned off, as Parakeet's come from `inko check`.

## [0.12.0] - 2026-10-24

### Fixed

- Names inside string interpolations (`"${name}"`) are code again: Rename, Find Usages, Safe Delete, Inline Variable and Extract Method no longer miss them, and Inline Variable no longer leaves an interpolated use dangling.
- `for` loop variables and destructuring patterns (`let Some(x) = … else`, `let (a, (b, c))`, `let [a, b]`, `let { @a = b }`, `mod.CONSTANT`) are local variables, so their uses no longer resolve to same-named methods. Patterns are read the way Inko's parser reads them, in `let`, `for` and `match` arms alike.
- After an enum's constructors, the next method is no longer taken for a local variable, so Rename and Find Usages work on it across the project.
- Methods named with keywords (`next`, `or`, `else`, `copy`, `true`, `false`) have names: they appear in the Structure view, can be renamed and found, calls like `iter.next` resolve, Implement Methods no longer offers `Iter.next` again for an `impl` that has it, and Extract Method accepts selections calling them. Keyword labels in named arguments are labels.
- Adding an import no longer breaks a file whose last import has build tags (`import a (b) if unix`) or is an `import extern`, and names are only added to imports without build tags.
- Setters (`fn value=`) are connected to the assignments that call them (`c.value = 2`): they have usages, can be renamed (from either side) and aren't deleted by Safe Delete while still used.
- `type ref` types, and types with any other modifiers Inko allows, appear in the Structure view, with their methods under them instead of as module functions.
- `import std.fmt (self, Format)` imports the module as `fmt`, so `fmt.` works for navigation, imports on paste and Move.
- Type Hierarchy shows the traits a trait requires.
- Unwrap/Remove no longer treats a match arm's guard, an `impl`'s bounds or an import's build tags as an `if`, and Complete Current Statement no longer adds bodies after them or to `fn extern` signatures.
- Statements end where Inko's parser ends them: a line starting with `-1` or `!x`, and the line after a bare `return`, are statements of their own (for Move Statement, Extract Method, Introduce Variable, Inline Method and Complete Current Statement).
- Number literals lex as Inko's lexer reads them, so `pair.0.1` and `10e+2` work with Expression Type, Introduce Variable, Extend Selection and the operator intentions.
- Expression Type knows the types of constants from dependencies and the standard library.
- Fields named with digits, like a tuple's `@0`, are fields in the outline and completion.
- Parameter info and parameter-name hints for constructors of types with `mut` fields show the right names, and an enum's compiler-internal fields no longer appear in completion or parameter info.
- The Bool-method inspection no longer flags methods the standard library deliberately names without `?`, such as `AtomicBool.compare_and_swap`.
- Errors are underlined in the right place on lines with emoji, accented letters or other characters made of several code points (Inko counts columns in grapheme clusters).
- One diagnostic with a control character in its message no longer drops every other diagnostic from the same check.
- Checking and indexing never sync dependencies or touch the network, so diagnostics no longer disappear offline, and the banner says whether a project's dependencies were never synced or have changed since.
- The Code Style preview is valid Inko.

## [0.11.0] - 2026-10-23

### Added

- Intentions on ⌥⏎ / Alt+Enter: flip a comparison, `==`, `!=`, `and`, or `or` (with parentheses where Inko's left-to-right grouping needs them); invert an `if` and swap its blocks; replace an `if`/`else if` chain comparing a name to literals with a `match` (adding `case _` where needed), and back; add or remove argument names, keeping the call valid; and specify a `let`'s type when the value's type is known, or remove it when it matches. Each is described with a before/after example in *Settings → Editor → Intentions → Inko*.
- Move (F6) for a top-level type, trait, constant, or function, with the caret on or in it, to another module (created if new). A type takes its `impl` blocks along. Modules importing it import it from the new module (qualified `module.Name` uses become imports), the old module imports it if it still uses it, and the moved code brings the imports it needs. A private declaration used elsewhere, code needing the old module's private names, and a name the target already defines are reported first.
- Generate (⌘N / Alt+Insert) inside a type: **Constructor** (`fn static new` taking every field, as the standard library writes it) and **Implement Trait** (pick a trait, get its `impl` with stubbed required methods and the import). Implement and Override Methods were already there inside an `impl`.
- Expression Type (⌃⇧P / Ctrl+Shift+P): the type of the expression at the caret where the code states it: literals, comparisons, casts, arithmetic on numbers, annotated or assigned locals, `self`, fields, constructors, and the return types in method signatures. Otherwise it says the type is unknown rather than guess, including for methods called on a value of unknown type and return types using a type parameter.
- Completion offers names from modules that aren't imported yet (their public types, traits, constants, and functions, below names in scope) and adds the import when you choose one.
- Imports on paste: pasting Inko code into another module adds the imports it needs, following *Settings → Editor → General → Auto Import → Insert imports on paste* (always, ask with a checklist, or never).
- Errors across the whole project: after saves and when a project opens, `inko check` runs on every module, marking files with errors as problem files for the Project view and Problems tool window. *Code → Inspect Code* lists every module's errors and warnings through the new "Inko compiler errors and warnings" inspection.

### Fixed

- Removing a file's last import (with Optimize Imports or a quick fix) no longer leaves a blank line at the top of the file.

## [0.10.0] - 2026-10-23

### Added

- Change Signature (⌘F6 / Ctrl+F6) for methods, from the method or a call: rename it, change its return type, and add, remove, reorder, rename, or retype parameters. Calls follow: arguments move with their parameters (positional or named; anything after a named argument stays named, as Inko requires), new parameters get the value you enter (Inko has no default arguments), and empty parentheses are dropped. Like RubyMine and PyCharm, calls whose receiver type isn't known open in the preview so you can exclude them. Removed parameters still in use, clashing names, and calls that can't be mapped are reported first. Trait methods and methods implementing them aren't supported.
- Introduce Parameter (⌥⌘P / Ctrl+Alt+P): an expression in a method becomes its last parameter, and every call passes the expression instead, importing what it names in other modules. Expressions using locals, fields, or `self` are refused, and a name private to the module is reported, since importing it wouldn't compile.
- Introduce Constant (⌥⌘C / Ctrl+Alt+C): `let NAME = …` after the module's constants or imports, replacing the expression (optionally everywhere in the file), for the values Inko constants can hold.
- Extract Method (⌥⌘M / Ctrl+Alt+M): statements or an expression become a method of the same kind, right after the original, with variables flowing in as parameters and out as results (several as a tuple). Types come from what's written or obvious; others are placeholders to Tab through after naming the method in place. Owned values are passed as `mut` borrows so the caller can keep using them. Code that returns, throws, uses `try`, or breaks out of an outer loop is refused.
- Inline Method (⌥⌘N / Ctrl+Alt+N) on a method or a call: replaces calls with the body, keeping or removing the method and its imports. Arguments that would be evaluated more than once are bound to a `let`, clashing locals are renamed, and the result is parenthesized where Inko's operators would regroup it. Recursive, `fn async`, and trait methods, and bodies using `return`, `throw`, or `try`, aren't inlined.

### Fixed

- A bare call inside a type, like `helper` in an instance method, now resolves to the type's own method first (a static one in static methods), as Inko does, so go to definition, Find Usages, and Rename find it.
- Introduce Variable no longer offers part of an operator chain as an expression: `60 - 60` in `total - 60 - 60` is part of `(total - 60) - 60`, so replacing it changed what the code computed.

## [0.9.0] - 2026-10-22

### Added

- Inspections for Inko's style guide, as weak warnings with quick fixes, for what `inko fmt` doesn't fix: names in the wrong case (`PascalCase` types and constructors, `snake_case` methods, fields, and variables, `SCREAMING_SNAKE_CASE` constants), Bool methods not ending in `?` or prefixed with `is_`, redundant argument names (`start: start`), and empty parentheses in calls (`pop()`). Names a trait or `extern` dictates, Bool-returning `mut`/`move` methods (actions, like std's `insert -> Bool`), and the standard library and dependencies are left alone. `# noinspection Name` on the line above suppresses one.
- Implement Methods (⌃I / Ctrl+I) and Override Methods (⌃O / Ctrl+O) in `impl Trait for Type`: stubs for the trait's required or default methods, with the impl's type arguments in place of the trait's parameters, calling `panic('not implemented')` so they compile until filled in.
- Postfix completion: `.if`, `.match`, `.let`, `.not` (`!`), `.return`, and `.par` after an expression like `numbers.get(0).ok`.
- Go to Implementation (⌥⌘B / Ctrl+Alt+B) for traits and trait methods, including the project's implementations of standard library traits, and Go to Super Method (⌘U / Ctrl+U) from an impl's method, an impl, or a type to what it implements.
- Go to Test (⌘⇧T / Ctrl+Shift+T) between `src/app/util.inko` and `test/app/test_util.inko`, and Create Test when there's none.
- Extend Selection (⌥↑ / Ctrl+W) through strings, arguments, brackets, statements, blocks, and declarations.
- Unwrap/Remove (⌘⇧⌫ / Ctrl+Shift+Delete) for `if`, `else`, `while`, `loop`, `for`, `recover`, and bare blocks.
- Copy Reference (⌥⇧⌘C / Ctrl+Alt+Shift+C): `app.util.Helper.new`.
- Quick fixes for more of `inko check`'s errors: add a missing field, implement missing methods, implement a missing required trait, and remove unreachable code.
- Renaming a directory of Inko modules updates their imports.

### Fixed

- Breadcrumbs no longer loop forever in operator methods like `fn ==`.

### Changed

- CI no longer caches unpacked IDEs, which nearly filled GitHub's 10 GB cache budget.

## [0.8.0] - 2026-10-22

### Added

- Introduce Variable (⌥⌘V / Ctrl+Alt+V): puts a selected expression, or one of the chains at the caret (`foo`, `foo.bar(1)`, `foo.bar(1).baz`), in a `let`, optionally replacing its other uses in the method, named in Inko's style and renamed in place.
- Inline Variable (⌥⌘N / Ctrl+Alt+N), following Inko's syntax: the value is parenthesized where its operators would otherwise change meaning (Inko's binary operators share one precedence, and `and`/`or` bind tighter). Patterns, `let … else`, `let _`, and reassigned variables aren't inlined, and inlining a variable used more than once asks first.
- Type Hierarchy (⌃H / Ctrl+H): a type's traits, and a trait's required traits, requiring traits, and implementing types.
- Moving an Inko file or directory updates the imports of the modules it moves, and warns when a module would leave `src/` and `test/`.
- Surround With (⌥⌘T / Ctrl+Alt+T): `if`, `if`/`else`, `if !`, `while`, `loop`, `for … in`, `match`, a closure, `recover`, a block, or parentheses.
- Move Statement Up/Down (⇧⌘↑/↓ / Ctrl+Shift+↑/↓), moving whole statements, blocks, and declarations (with their comments) within their block.
- Complete Current Statement (⇧⌘⏎ / Ctrl+Shift+Enter): closes brackets and adds a body after `if`, `while`, `fn`, `type`, and the like.
- Safe Delete (⌘⌫ / Alt+Delete): warns about usages, then deletes the whole declaration with its comments.
- Call Hierarchy (⌃⌥H / Ctrl+Alt+H): a method's callers and callees.
- "N usages" above declarations (up to "99+ usages"), in the IDE's shared Usages code vision.
- External Documentation (⇧F1) opens standard library definitions on docs.inko-lang.org for the project's Inko version.
- File | New | Inko File: a module, a test file, or a program with `Main`.
- Run configurations have Release build (`--release`), Target (`--target`, listing `inko targets`), and Statically link C libraries (`--static`), for the commands that accept them, including test runs.
- Inko in Markdown code fences (```` ```inko ````) is now tested (the Markdown plugin already supported it).

### Fixed

- Names with `$` (for C functions, like `readdir$INODE64`) and setter names ending in `=` (`fn value=`) are now one name.
- An unclosed `(` or `[` no longer pairs with a later `}`.
- The Sync button no longer stays on `inko.pkg` files with Windows line endings.

## [0.7.0] - 2026-10-22

### Added

- Find Usages (⌥F7 / Alt+F7) and Rename (⇧F6 / Shift+F6) for types, traits, methods, fields, constants, enum constructors, modules, and local variables and parameters, across the project. Renaming a module's file updates its imports. Like RubyMine and PyCharm, calls whose receiver type isn't known are listed as possible usages, and renaming a method called on values always shows the preview so you can untick them. Locals rename in place; standard library and dependency declarations can't be renamed.
- The standard library and each project's dependencies under External Libraries, searchable with Go to File and Find in Files. `dep` and `build` are excluded from the project (once, so un-excluding sticks).
- `inko.pkg` support: highlighting, commenting, and errors for lines `inko` would reject. Tools → Inko Dependencies (and the editor's context menu) syncs, updates, adds, and removes dependencies with `inko pkg`. A floating Sync button appears on `inko.pkg` while it differs from what was last synced (like Gradle's "Load changes"), and Inko files show a banner until dependencies are synced.
- Optimize Imports (⌃⌥O / Ctrl+Alt+O): removes the imports `inko check` reports as unused, and sorts imports with `inko fmt`.
- Rerun Failed Tests in the test runner.

### Fixed

- `@field` resolved to same-named fields in other types in the module; it now means the enclosing type's field.
- The standard library and dependencies were checked with `inko check` as if they were the project's code when opened.

## [0.6.0] - 2026-10-22

### Added

- Inko code style (Settings | Editor | Code Style | Inko), defaulting to two-space indentation like `inko fmt`, and smart indentation when typing: Enter indents inside blocks (including between braces), and closing brackets line up with the line that opened them.
- Auto-import: on "the symbol 'X' is undefined", Alt+Enter offers to import `X` from each module that defines it (your project's first, then dependencies, then the standard library), adding to an existing import of that module when there is one.
- Quick fixes for compiler warnings: remove an unused import (or just the unused symbol from it), rename an unused local to `_name`, and discard an unused result with `let _ =`.
- Gutter icons linking traits and implementations: `impl Trait for Type` and its methods point to the trait and its methods; traits and their methods point to the types implementing them; types point to the traits they implement.
- Parameter info (⌘P / Ctrl+P) in calls: the callee's parameters with the current argument highlighted, for functions, methods (listing each candidate when the type isn't known), enum constructors, and `Type(…)` (its fields).
- Parameter-name hints before positional arguments (`greet(name: 'Alice')`), only when it's certain what's called; toggle them in Settings | Editor | Inlay Hints.
- Highlighting the occurrences of the name at the caret that mean the same thing (and ⌘⇧F7 / Ctrl+Shift+F7): a local in its own scope, a field in its type, and other names by what they resolve to, with declarations marked as writes.
- Reporting errors: the IDE's error dialog can open a GitHub issue in your browser, prefilled with the error and your versions, for you to review and submit.
- Local variables and parameters (including closure parameters and `case` bindings): Go to Declaration and completion, scoped by blocks.
- Method calls on a local with a written or obvious type (`x: Foo`, `let x = Foo.new`, `Foo(…)`), or on `self`, now complete and navigate to that type's methods and fields, instead of every type's.
- A banner when `inko.pkg` requires a newer Inko (`require inko X`) than the project uses, offering to switch to an installed ivm version that's new enough, or to install one.

### Fixed

- Installing Inko from the missing-inko banner could freeze the IDE while ivm fetched its list of versions.
- Opening a project logged "Read access is allowed from inside read-action only" from Parakeet's startup indexing.
- Go to definition, quick documentation, Go to Class/Symbol, and completion no longer go stale while the project doesn't compile, or before a file is saved: definitions in edited or unsaved files are read from source, and a project that doesn't compile still gets the standard library and its dependencies.

## [0.5.0] - 2026-10-22

### Added

- Test results in the IDE's test runner: each test file and test with pass/fail, failures with expected and actual values (Click to see difference), jumping to a test's source, and running a single test from the tree. Tests build in a mirror of the project in the IDE's system directory, so nothing is added to the project.
- A banner on Inko files when Parakeet can't find `inko` (or the project's ivm version isn't installed), offering to install it with ivm or choose a toolchain.
- Install and remove ivm versions from Settings | Languages & Frameworks | Inko.

## [0.4.0] - 2026-10-22

### Added

- Quick documentation (F1 or hover): a definition's signature, where it's defined, and its doc comment.
- Inko types, traits, methods, and constants in Go to Class, Go to Symbol, and Search Everywhere.
- A Structure view, breadcrumbs, and sticky lines for each file's types, traits, impls, methods, fields, and constants.
- Code folding for blocks, comment runs, and imports.
- Completion for keywords, names in scope, module paths and symbols in imports, members after `.`, and fields after `@`.
- Live templates: `fn`, `fnp`, `type`, `typep`, `trait`, `impl`, `match`, `main`, `tests`, and `test`.
- TODO and FIXME highlighting in comments, and the TODO tool window.
- Spell checking in comments and strings.

### Fixed

- Go to definition landed on doc comments, or the top of the file for built-in types like `String`.
- Reformat Code did nothing for Inko files that weren't saved to disk.

## [0.3.1] - 2026-10-22

### Fixed

- The New Project wizard now picks a valid main module name (e.g. `from_intellij` for "From IntelliJ").

## [0.3.0] - 2026-10-22

### Added

- An Inko file type and icon.
- Go to definition, using the definitions `inko doc` reports.
- Run and test from the gutter, run configurations for `inko run`, `test`, `build`, and `check`, and clickable `file.inko:line` links.
- A New Project wizard using `inko init`.
- A per-project toolchain: auto-detect, ivm's default, any ivm-installed version, or a custom executable.

## [0.2.0] - 2026-10-22

### Added

- Compiler errors and warnings as you type, from `inko check`.
- Reformat Code with `inko fmt`.

## [0.1.0] - 2026-10-22

### Added

- Syntax highlighting, comment toggling, and bracket matching, from the inko-lang/vscode TextMate grammar.
