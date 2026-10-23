# Changelog

## [Unreleased]

### Added

- Inko code style (Settings | Editor | Code Style | Inko), defaulting to two-space indentation like `inko fmt`, and smart indentation when typing: Enter indents inside blocks (including between braces), and closing brackets line up with the line that opened them.
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
