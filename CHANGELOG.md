# Changelog

## [Unreleased]

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
