# Session: Find Usages and the refactorings on real-world code

Until this session, the refactorings and searches had only run on test
fixtures. The real-world projects' tests (SESSION-hand-test-fixes.md) checked
resolution, hovers and completion. This session adds a sweep that runs Find
Usages and every refactoring on copies of those projects, against the real
`inko` and Cuttlebone, and fixes what it found. Nothing is tagged, pushed or
published, and 0.13.0's entry in CHANGELOG.md is unchanged: every fix is
under Unreleased.

This work is requested, not reviewed.

## The tools

- `inko 0.21.1`, from ivm.
- `cuttlebone 0.11.0 (Inko 0.21.1)`, the one Parakeet downloaded into
  JetBrains' shared data directory
  (`parakeet/cuttlebone/cuttlebone-0.11.0-inko-0.21.1/`), passed to every
  check as `CUTTLEBONE`. The `cuttlebone.aside` in Cargo's `bin` directory stayed where it
  was. I didn't install or change Cuttlebone, or Inko's checkout: I only
  read their sources, to check Inko's rules (the prelude, private names) and
  how Cuttlebone notices files changing on disk.
- The real-world projects: Yorick Peterse's (`../yorickpeterse/`) and
  inko-lang's idoc and website (`../inko-lang/`). Each test copies one into
  its temporary directory, and only reads the original.

## What the sweep does

`RealWorldRefactoringsIntegrationTest`, run by `scripts/check.sh --full`
(and only with `-PrealWorld`, like `RealWorldProjectsIntegrationTest`, whose
setup it now shares through `RealWorldTestCase`). It has one test per
project that type checks with Inko 0.21.1: openflow, kvi, inko-markdown,
inko-syntax, inko-builder, inko-wobsite, yorickpeterse.com, idoc and
website. A project that isn't there, can't be synced (offline), or doesn't
type check is skipped with a message. shost, clogs and
code-formatting-in-inko don't type check with 0.21.1, so they aren't in it.

- **Find Usages**, as the IDE runs it (Parakeet's handler, project scope),
  for a sample of each kind of declaration: types, traits, a trait's
  methods, methods implementing one, other methods, functions, fields,
  constants, enum constructors and locals (up to three of each, spread
  through the project). What it finds must be exactly what Cuttlebone's
  `textDocument/references` lists. The one allowed difference is the
  documented one: a trait's method also counts the calls to the methods
  implementing it. None of the results may be a possible usage.
- **The refactorings**, each at a sample of sites: Rename (a declaration of
  each kind), Safe Delete (used declarations, which it should refuse, and up
  to four unused ones, which it should delete), Extract Method (a statement,
  and a member call), Inline Variable, Inline Method, Introduce Variable,
  Introduce Constant (literals), Introduce Parameter, Change Signature
  (adding a parameter, and swapping the first two), and Move (to a new
  module beside the source, `<module>_moved`). Each runs as the IDE runs it,
  headless: the handler or processor, with the IDE's own conflicts as
  refusals. Then the copy must pass `inko check`, and `inko test` where
  the project has tests. Then the copy is put back as it was, through the
  IDE.
  - A refactoring may refuse, if it says why, and must change nothing then.
  - Tests that fail on the untouched copy may fail after a refactoring.
    inko-wobsite has one, `Files.matching`, which depends on the order the
    file system lists a directory in.
  - Extract Method's placeholder type `Type`, which its template asks you
    to fill in where the compiler's type can't be written, counts as asking,
    not as broken code.
- Nothing may log an error, as in the other real-world tests.

`PARAKEET_SWEEP_ONLY` (comma-separated parts of names, like `Rename,Inline`)
runs only some of it, which helps when chasing one failure.

## What it found, all fixed test first

Each fix has its own commit and CHANGELOG entry. Each started with a test
that I ran and saw fail. Where a fix was split from another made at the same
time, I checked the split-off test still failed without its own half.

### From Find Usages

| Commit | Found in | What was wrong |
|---|---|---|
| bd6725d | kvi | A `let`'s variable was in scope in its own value: in `let arguments = arguments` (std.env's `arguments`), renaming the local renamed the call too (`let args = args`). |
| 244c122 | inko-wobsite | A closure type in a method's header (`builder: uni fn (…) -> …`) ended the outline's header search, so the method had no body, and its match arms' `case Error(e)` became enum constructors the type declares. |
| 08773c7 | idoc, five projects' constructors, inko-builder | A named argument's label (`Page(module: module)`) and a call (`escape(v)`) were taken for a local of the same name: renaming the local renamed them, and Find Usages of the field or method missed them. |
| 01eb007 | inko-syntax | A trait's search counted the uses of the types implementing it. In inko-syntax each lexer's `type Lexer` implements the trait `Lexer`, so renaming the trait would have renamed every lexer. |
| 992fde0 | inko-syntax | Uses under an import's alias (`Lexer as LexerTrait`) weren't searched for. Rename now renames the imported name and leaves the alias. |
| f2ce502 | kvi, yorickpeterse.com | Cuttlebone lists `Self` as a use of the type, and Parakeet didn't. Find Usages lists it now; Rename and the rest leave `Self` alone. |

### From the refactorings

| Commit | Refactoring | Found in | What was wrong |
|---|---|---|---|
| ae8dcc6 | Inline Method | kvi | `Self` in the body stayed `Self` outside the type. It's the type's name there now; for a generic type, or one the call's module doesn't import, it refuses. |
| 6f5034c | Inline Method | kvi | Inlining every call removed a method implementing a trait's method (`Config.clone`). It's kept, with a conflict saying why. |
| a949229 | Extract Method | kvi | An extracted expression's value got the placeholder `Type` where Cuttlebone had the type. |
| 343b7f8 | Introduce Parameter, Change Signature | idoc | They changed `fn async main` and a test module's `fn pub tests`, which the runtime and `inko test` call. |
| dd9eb06 | Inline Variable | idoc | A value using `try` was moved into a closure, where `try` would leave the closure. |
| d62cb91 | Extract Method | idoc | It wrote the compiler's types (`Path`, `Test`) without importing them. It imports a type the index has one of; otherwise it's the placeholder. |
| 4916598 | Introduce Parameter, Extract Method | inko-wobsite | `Map.new` gave the type `Map`, without its type arguments. Both now use the compiler's type first. |
| 3c0d0fc | Extract Method | yorickpeterse.com, inko-syntax | In `impl Trait for Type`, the new method went into the impl, which can only have the trait's methods. It's a function after the impl now; code using `self` there is refused. |
| e3143fb | Extract Method | inko-syntax | Code from a `fn move` method went into a plain `fn`, which can't call `self`'s `mut` methods. |
| 9a86c7d | Rename | inko-syntax, yorickpeterse.com | Renaming a parameter of a trait's method, or of an implementation, broke it: Inko requires the trait's parameter names. It's a conflict now. |
| f6c997b | Rename, Go to Implementation | inko-syntax, openflow | Implementations in `test/` (test doubles) weren't found, since `inko doc` documents only `src/`. Renaming the trait's method left them. |
| e7f153b, dc10dca | Inline Method | inko-syntax, website | Through the method's module (`bytes.whitespace?(b)`), the module's own names were left unqualified (`SPACE`), and then (after e7f153b) what the body imports from elsewhere (`Site`) wasn't imported. |
| 9ba45f8 | Inline Variable | inko-syntax, openflow | A value that calls a method was moved past code that changes what it reads (`@buffer.offset += 1`), which changed what the program did. |
| daf4a22 | Rename, Change Signature | inko-wobsite | A C function (`fn extern fnmatch`) was renamed, and the tests didn't link. |
| 55f7b08 | Move | inko-markdown | The moved code imported a private name from a module in another root namespace (`markdown.parser`'s `Parser` into `markdown_moved`). Move checks Inko's rule now: a private name is visible within its root namespace. |
| 1f69c37 | every feature adding imports | inko-markdown | Adding a name to an import written over several lines, as `inko fmt` writes a long one, gave `percent_encode,, Heading)`. |
| 2dcf334, 27b5ea8 | Inline Variable | five projects | A value that makes something (`ByteArray.new`, `try read(client)`, `[]`, `new`), used more than once, became several values. The dialog warned about evaluating it at each use, but the code it made was still wrong. It refuses now. |
| 3cf049b | Inline Method | yorickpeterse.com | It imported `Self` (`import site.filters (Videos, Self)`). |
| 68c3914 | Introduce Constant | website | The new constant went after the first line of `let MENU = [`, inside its value. |
| 8852795 | Extract Method | inko-builder | A closure parameter became `mut fn (…)`. |
| 7107bda | Extract Method | inko-builder | It re-indented the lines of a string written over several lines, changing the string. |
| d089ea2 | Introduce Variable | openflow | A variable for `Snapshot.of(state).rooms.get('bathroom')` (an `Option[ref …]`) kept a borrow of a value dropped at the end of its statement, which panicked when the tests ran. It refuses when the compiler's type is a borrow and the expression starts by making a value. |
| fc928f0 | Inline Method, Introduce Parameter | website | The moved code names a module (`layouts.page`), which wasn't imported where it went. |

### The sweep's own commits

| Commit | What |
|---|---|
| 9ff7a8c | The real-world tests' setup, shared (`RealWorldTestCase`), and `check.sh --full` runs every `RealWorld*IntegrationTest` |
| cf79a08 | The sweep, and a Change Signature test for a call without parentheses in the method's own file |
| ea57005, 02695bd, ea95b0b, d089ea2 (its sweep part), 6de340c, f74a777 | Fixes to the sweep itself: one background read, snapshots of only `.inko` files (the IDE's `x.inko~` files come and go), a baseline for tests that fail before any refactoring, the placeholder type counted as asking, the copy put back through the VFS, and between sites: templates finished, editors closed, and a fresh Cuttlebone ([below](#open-stale-answers-within-one-cuttlebone)) |
| (this commit) | This report, and README's link to it |

## Results

With Inko 0.21.1 and Cuttlebone 0.11.0, at f74a777, all nine projects
pass, in about 10 minutes for the nine (Gradle runs them side by side).

| Project | Find Usages declarations | Refactorings applied, type checking and testing | Refused, saying why | Asks for a type |
|---|---|---|---|---|
| openflow | 27 | 33 | 33 | 2 |
| kvi | 24 | 30 | 27 | |
| inko-markdown | 26 | 39 | 34 | |
| inko-syntax | 28 | 42 | 32 | |
| inko-builder | 28 | 39 | 33 | |
| inko-wobsite | 24 | 30 | 34 | |
| yorickpeterse.com | 19 | 24 | 35 | 3 |
| idoc | 28 | 30 | 35 | 1 |
| website | 24 | 30 | 29 | 3 |

By refactoring, over all nine:

| Refactoring | Applied | Refused | Asks for a type |
|---|---|---|---|
| Rename | 121 | 19 | |
| Safe Delete | 16 | 79 | |
| Extract Method | 39 | 6 | 9 |
| Inline Variable | 6 | 21 | |
| Inline Method | 17 | 25 | |
| Introduce Variable | 26 | 1 | |
| Introduce Constant | 27 | 0 | |
| Introduce Parameter | 8 | 28 | |
| Change Signature | 26 | 76 | |
| Move | 11 | 37 | |

Most refusals are by design: a used declaration for Safe Delete (with the
IDE's search in comments and strings on, as its dialog has it), a method
implementing a trait's (Rename, Change Signature, Inline Method), a trait's
method (Change Signature), and private declarations for Move. Twenty of
Change Signature's are the sweep's own: "swap the first two parameters" on a
method with fewer than two. Introduce Parameter refuses most often because
the sample's expressions are in `fn pub tests` or `fn async main`. Inline
Variable refuses most of its sites now: the sample favours values that
compute something and are used more than once.

## What's left unverified, and what you may want to decide

- <a id="open-stale-answers-within-one-cuttlebone"></a>**Open: stale answers
  within one Cuttlebone.** This is the most important thing left. With one
  Cuttlebone for a whole project's sweep, a search after earlier sites had
  changed a file and put it back sometimes found no uses past the
  declaration in that file. Rename and Move then missed them, Change
  Signature and Introduce Parameter left calls unchanged, and Safe Delete
  deleted kvi's `run`, still used by `match run` below it. The offsets fit
  answers for the file's text before it was put back. It happened on files
  an earlier site had changed through an editor (the file opened at the
  start, or one Extract Method or Inline Variable had opened), and not when
  the same refactorings ran alone (`PARAKEET_SWEEP_ONLY`). Closing editors
  when putting the copy back moved the failures to other files. Restarting
  Cuttlebone between sites made every project pass, so the stale state
  lives in what one Cuttlebone keeps, or in what Parakeet keeps per server
  (CuttleboneQueries' answers, InkoCompilerReferences' uses, both cleared
  when PSI changes). Parakeet waits until Cuttlebone has the queried
  document's latest version (`isSynced`), and Cuttlebone 0.11.0's check of
  files on disk looks sound (size, times to the nanosecond, inode). So I
  don't know which it is. In the IDE, the same could happen to a refactoring
  after an undo, or after another refactoring, in the same session. It needs
  a focused test against the real Cuttlebone: change a file through its
  document, change it back, and compare `textDocument/references` with the
  text. The sweep restarts Cuttlebone between sites, so it doesn't catch
  this.
- **Once, a Rename changed nothing** (idoc's `Constructor`, an enum
  constructor). Run again, it renamed. `InkoRenameVeto` vetoes a rename
  silently while `Cuttlebone.canSearch` is false, which a busy Cuttlebone
  could cause. Not confirmed.
- **The trait-method difference.** Find Usages of a trait's method counts
  its implementations' calls, which Cuttlebone doesn't. The sweep allows
  these only where Cuttlebone resolves each extra use to a method of that
  name.
- **Conservative refusals.** Inline Variable now refuses a computed value
  whenever any statement runs between the `let` and its last use, even one
  that can't change it. Move still says a private declaration can't be
  imported from the target even when the target is in the same root
  namespace, where Inko allows it.
- **Extract Method's placeholder.** Where the compiler's type can't be
  written (no answer, `?` in it, or a type the index has several of), the
  template still asks for a type. The sweep counts those as asking.
- **Cuttlebone 0.12.0's hover.** It puts the type first, then the
  declaration and doc comment, separated by `\n\n---\n\n`. Parakeet reads
  the hover's whole text as the type (`InkoHover.type` only strips the code
  fence), so with 0.12.0, everything that uses the compiler's type would
  get the declaration and docs too: Extract Method's and Introduce
  Parameter's types, "Specify type explicitly", Expression Type, the
  documentation popup's "Type here", and Introduce Variable's borrow check.
  This session tested 0.11.0 only and didn't change that.
- **The build cache served stale test classes.** Three times, after a
  function returning `Result` got a parameter with a default value, the
  test task ran test classes compiled against the old signature
  (`NoSuchMethodError`), even after deleting the local classes. Gradle's
  build cache (`org.gradle.caching = true`) restored them. The pre-commit
  check could then pass or fail on stale code. I worked around it with
  `--rerun-tasks`. Turning the cache off for `compileTestKotlin`, or
  finding why its key doesn't change, is for you to decide.
- **Load.** Another session kept this machine at load averages of 100 to
  400 for hours. Five pre-commit checks failed on the typing replay's
  latency limits or `CuttleboneClientTest.testStopsWithShutdownAndExit`'s
  30 s wait, and passed when rerun unchanged (one of them I ran on the
  previous commit too, where it passed alone). One commit's check (08773c7)
  ran without `CUTTLEBONE`; I ran Cuttlebone's integration tests and the
  typing replay for it right after, and they passed.
- Nothing was tried in a real IDE: every check here is headless, and the
  sweep accepts each refactoring's defaults (templates accepted as they are,
  conflicts treated as refusals, every occurrence replaced).
- The sweep's samples are small (two or three of each kind per project), so
  other code shapes may still break a refactoring. A site sampled from
  `test/` is often inside `fn pub tests`, which limits what Introduce
  Parameter and Change Signature can try there.

## Checklist for testing by hand

- [ ] Rename a trait's method that a test double in `test/` implements:
      the double's method is renamed too.
- [ ] Rename a parameter of a method in `impl ToString for …`: a conflict
      says Inko requires the trait's parameter names.
- [ ] Extract Method on code in a trait's `impl`: the new function goes
      after the impl. On code using a type the module doesn't import (a
      `Path`, say): the import is added.
- [ ] Inline Variable on `let x = ByteArray.new` used twice: it refuses,
      saying each use would make its own.
- [ ] Find Usages of a type: its `Self` uses are listed; Rename leaves them.
- [ ] After a Rename and an undo in an open file, rename something else in
      that file: every use in the file is renamed (the [open
      issue](#open-stale-answers-within-one-cuttlebone)).
