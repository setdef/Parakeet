# Parakeet for Inko

[![Certified Shovelware](https://justin.searls.co/img/shovelware.svg)](https://justin.searls.co/shovelware/)

Parakeet is unofficial, community-made support for the
[Inko](https://inko-lang.org) programming language in JetBrains IDEs. It's
tested in IntelliJ IDEA, and should work in the others (RubyMine, WebStorm,
RustRover, etc.), though they haven't been tried yet.

This plugin is not affiliated with or endorsed by the Inko project. Please don't
report problems with this plugin to the Inko project; open an
[issue here](https://github.com/setdef/Parakeet/issues) instead. When Parakeet
causes an error, the IDE's error dialog can do that for you: **Open a GitHub
Issue** opens one in your browser, filled in with the error and your versions,
for you to review and submit.

## How this code came to exist

I use three words for how code comes to exist. Code I wrote, I typed by hand, with nothing generated. Code I wrought, an AI agent generated while I iterated with it, and I reviewed every change before committing it, so I own every diff. Code I requested, an agent built with little or no review from me, the way a dark factory runs without lights.

**Versions 0.1.0 through 0.12.0 are requested.**

## Features

- An **Inko** file type for `.inko` files, with its own icon
- Syntax highlighting, from the TextMate grammar in
  [inko-lang/vscode](https://github.com/inko-lang/vscode)
- Line comment toggling (`#`), bracket matching, and auto-closing brackets and quotes
- Two-space indentation as you type, like `inko fmt` (*Settings → Editor → Code
  Style → Inko*)
- Compiler errors and warnings as you type, from `inko check`, with **quick
  fixes** (Alt+Enter): import an undefined name from the module that defines
  it, remove an unused import, rename an unused local to `_name`, discard an
  unused result with `let _ =`, add a missing field, implement missing methods
  or a missing required trait, and remove unreachable code
- **Errors across the whole project**: after saves and when a project opens,
  `inko check` runs on every module, so files with errors are marked in the
  Project view and the Problems tool window even when they aren't open, and
  *Code → Inspect Code* lists every module's errors and warnings
- **Intentions** (Alt+Enter anywhere): flip a comparison, `==`, `!=`, `and`, or
  `or`; invert an `if`; replace an `if`/`else if` chain over literals with a
  `match`, and back; add or remove argument names; and specify or remove a
  `let`'s type
- **Style-guide inspections** for what `inko fmt` doesn't fix, with quick
  fixes: names in the wrong case, Bool methods not ending in `?` (or prefixed
  with `is_`), redundant argument names like `start: start`, and empty
  parentheses in calls like `pop()`. They're weak warnings, configurable in
  *Settings → Editor → Inspections → Inko*, and `# noinspection Name` on the
  line above suppresses one.
- *Code → Reformat Code* (⌥⌘L / Ctrl+Alt+L) with `inko fmt`. To format on save,
  turn on *Settings → Tools → Actions on Save → Reformat code*.
- **Go to definition** (⌘-click / Ctrl-click, ⌘B / Ctrl+B) for types, traits,
  constants, functions, methods, fields, modules, and local variables and
  parameters, including the standard library and dependencies. A method call
  goes to the method the compiler calls, from [Cuttlebone](#cuttlebone).
- **Go to Type Declaration** (⇧⌘B / Ctrl+Shift+B)
- **Find Usages** (⌥F7 / Alt+F7) and **Rename** (⇧F6 / Shift+F6) across the
  project, by the compiler's answers, including renaming a module's file, and
  a trait's method with the methods implementing it
- **Highlight usages** of the name at the caret within the file
  (⌘⇧F7 / Ctrl+Shift+F7), with assignments as writes when Cuttlebone answers
- **Parameter info** (⌘P / Ctrl+P) in calls, and parameter-name hints before
  positional arguments
- **Type hints** for `let` bindings and closure arguments that don't write a
  type. Types and parameter names each have a switch in
  *Settings → Editor → Inlay Hints*
- **Trait gutter icons** linking traits, their implementations, and the
  methods that implement them, with **Go to Implementation** (⌥⌘B /
  Ctrl+Alt+B) and **Go to Super Method** (⌘U / Ctrl+U)
- **Implement Methods** (⌃I / Ctrl+I) and **Override Methods** (⌃O / Ctrl+O)
  in `impl Trait for Type`, stubbing the trait's methods
- **Generate** (⌘N / Alt+Insert) in a type: a **Constructor** (`fn static new`
  from every field) and **Implement Trait** (an `impl` for a chosen trait, with
  its required methods stubbed and the trait imported)
- **Expression Type** (⌃⇧P / Ctrl+Shift+P): the compiler's type
- **Go to Test** (⌘⇧T / Ctrl+Shift+T) between `src/app/util.inko` and
  `test/app/test_util.inko`, and Create Test
- **Quick documentation** (F1, or hover): a definition's signature, where
  it's defined, and its doc comment, and the type at the caret ("Type here"); ⇧F1 opens standard library definitions on
  docs.inko-lang.org
- **Go to Class / Go to Symbol** (⌘O / ⌥⌘O) and Search Everywhere for Inko
  types, traits, methods, and constants
- **Structure view**, breadcrumbs, and sticky lines for each file's
  declarations
- **Code folding** for blocks, comment runs, and imports
- **Optimize Imports** (⌃⌥O / Ctrl+Alt+O): removes unused imports and sorts
  the rest
- **Refactorings**: Change Signature (⌘F6 / Ctrl+F6), Extract Method (⌥⌘M /
  Ctrl+Alt+M), Inline Method and Inline Variable (⌥⌘N / Ctrl+Alt+N),
  Introduce Variable (⌥⌘V / Ctrl+Alt+V), Introduce Parameter (⌥⌘P /
  Ctrl+Alt+P), Introduce Constant (⌥⌘C / Ctrl+Alt+C), Safe Delete (⌘⌫ /
  Alt+Delete), Move (F6) for a top-level type, trait, constant, or function
  to another module, and moving files or directories and renaming directories
  (which updates their modules' imports)
- **Type Hierarchy** (⌃H / Ctrl+H) for traits and the types implementing
  them, and **Call Hierarchy** (⌃⌥H / Ctrl+Alt+H) for callers and callees
- **Usage counts** ("N usages") above declarations
- **Surround With** (⌥⌘T / Ctrl+Alt+T) and **Unwrap/Remove** (⌘⇧⌫ /
  Ctrl+Shift+Delete), **Move Statement** (⇧⌘↑/↓ / Ctrl+Shift+↑/↓),
  **Complete Current Statement** (⇧⌘⏎ / Ctrl+Shift+Enter), and **Extend
  Selection** (⌥↑ / Ctrl+W) through strings, arguments, brackets, statements,
  and blocks
- **Copy Reference** (⌥⇧⌘C / Ctrl+Alt+Shift+C), like `app.util.Helper.new`
- **New Inko File** (*File → New → Inko File*): a module, a test, or a program
- Inko highlighting in Markdown ```` ```inko ```` code fences
- **External Libraries**: the standard library and each project's
  dependencies, browsable and searchable, with `dep` and `build` excluded from
  the project
- **`inko.pkg`**: highlighting and errors, plus *Tools → Inko Dependencies* to
  sync, update, add, and remove dependencies, and a Sync button when
  `inko.pkg` changes
- **Completion** for keywords, local variables, names in scope, imports,
  members after `.` (the compiler's list), and fields after `@`, plus names from modules that aren't imported yet, adding
  the import when you choose one
- **Imports on paste**: pasting Inko code into another module adds the imports
  it needs (*Settings → Editor → General → Auto Import → Insert imports on
  paste*)
- **Live templates**: `fn`, `fnp`, `type`, `typep`, `trait`, `impl`, `match`,
  `main`, `tests`, and `test`, and **postfix completion**: `.if`, `.match`,
  `.let`, `.not`, `.return`, and `.par` after an expression
- **TODO highlighting** and **spell checking** in comments
- **Test runner**: results per test file and test, failures with expected and
  actual values, jumping to (or rerunning) a single test, and rerunning the
  failed tests
- **Run and test from the gutter**: ▶ next to `fn async main`, next to
  `fn pub tests` in test files, and next to each `t.test('…')`
- **Run configurations** for `inko run`, `inko test`, `inko build`, and
  `inko check`, with release builds, other targets, and static linking, and
  clickable `file.inko:line` links in the output
- **New Project wizard** (*File → New → Project → Inko*) using `inko init`,
  optionally as a library or with a GitHub Actions workflow. The main module
  gets a valid module name (e.g. `from_intellij` for a project named
  "From IntelliJ"), which you can change.
- **Per-project toolchain** in *Settings → Languages & Frameworks → Inko*:
  auto-detect, ivm's default, any version ivm has installed, or a custom
  executable. With ivm installed, you can also install and remove Inko versions
  there, and banners offer to install Inko when it's missing, or a newer one
  when `inko.pkg` requires it.

### How it works

The plugin understands Inko through the `inko` command rather than its own
compiler, so it always agrees with your installed Inko version.

- **Errors** are checked against your unsaved text: the plugin mirrors the
  project into a temporary directory (symlinking every source file) with your
  unsaved text in place of the file, then runs `inko check` there, so imports in
  both directions and dependencies resolve.
- **Go to definition, quick documentation, Go to Class/Symbol, completion,
  parameter info and hints, and trait gutter icons** use the definitions
  `inko doc --private --dependencies` reports, rebuilt in the background when
  you save. `inko doc` only works on saved files in a project that compiles,
  so files you've edited since (saved or not) are read from source, and when
  the project doesn't compile, the standard library and dependencies are still
  documented while your own files are read from source.
- **Cuttlebone** answers with the compiler's own types and definitions; see
  [Cuttlebone](#cuttlebone) for which features ask it. Parakeet requires
  it: without it, what needs the compiler's types (go to definition of
  anything but a local or a module, completion after `.`, parameter info,
  hints, usage highlighting, Expression Type, Find Usages, Rename and the
  refactorings built on it) is unavailable, and Parakeet says so once when the
  project opens. Each question has a time budget (0.4 s for resolving a name, 1.5 s for
  completion, parameter info, documentation and Expression Type, 2 s for
  usage highlighting and for a file's inlay hints, asked in one request, and
  10 s for a search's references), and answers are kept until the code
  changes. Highlighting only waits for resolving names (inspections and
  intentions resolve them), the inlay hints and usage highlighting:
  Cuttlebone answers questions about unchanged text from the check it kept,
  in under a millisecond after a file's first check, which costs a whole
  check (about 50 ms for a small project, more on a busy machine). A
  question whose wait is cancelled because the caret moved isn't cancelled
  itself: that would discard the checks Cuttlebone kept, and its answer is
  kept for next time. An edit cancels (`$/cancelRequest`) every question
  about the text before it, since Cuttlebone (from its seventh milestone)
  would otherwise answer each one for that text, at a check each, for nobody:
  except what's still waiting for its answer, a search or completion (whose
  items are filtered as you type on). Usage highlighting, which runs each
  time the caret moves, so on every keystroke, asks once the text has been
  still for as long as highlighting waits after an edit (IntelliJ's
  autoreparse delay, 0.3 s by default).
  In code being edited, Cuttlebone answers from a copy
  with the broken lines blank, or else from the last version that type
  checked ("stale", shown only where nothing changes code; see
  [Answers in code being edited](#answers-in-code-being-edited)).
- **In code Cuttlebone can't check** (it doesn't parse there, or a
  declaration has an error), some features read the code themselves instead,
  so they don't go dark mid-edit; see
  [Answers in code being edited](#answers-in-code-being-edited) for which.
  That reading doesn't infer types: a method call like `x.to_string` narrows
  to `x`'s type when it's written or obvious (`x: Foo`, `let x = Foo.new`,
  `self`), and otherwise offers every `to_string` it can't rule out. It's
  never used where Cuttlebone has answered, or isn't running.
- **Find Usages and Rename** search the IDE's word index for each name, then
  keep the occurrences Cuttlebone's `textDocument/references` lists: one
  check of the whole project, once per search, matched by the compiler's ids.
  For a declaration outside the project (the standard library, a
  dependency), a use of it in the project is found first, by resolving
  candidates a file at a time. A trait's method counts the calls to the
  methods implementing it, since the compiler gives each its own id, and
  renaming any of them renames them all. A compound assignment to a member
  (`c.value += 1`) calls the getter `value` and then the setter `value=`, so
  it's a use of both, and renaming only one of them lists it as a conflict.
  Where references have no answer (a
  file in the project doesn't parse or imports a module that doesn't exist,
  or code a declaration's error may affect mentions the name), each candidate is
  resolved through `textDocument/definition` instead, a file's requests sent
  together. Only there, in code Cuttlebone can't check, does Find Usages show
  what Parakeet's own reading finds, as possible usages; Rename leaves those
  alone and lists them as conflicts first. **Call Hierarchy, usage counts,
  Safe Delete, Change Signature, Inline Method, Move and Introduce Parameter**
  build on the same search (usage counts stop at 99+), and like Rename use
  fresh answers only. **Moving files** rewrites the import paths of the
  modules that move.
- **Type Hierarchy** uses the trait implementations `inko doc` reports, and the
  traits each trait requires in its signature.
- **Dependencies need syncing** when `dep/hash` isn't the BLAKE3 hash of
  `inko.pkg`, which is how `inko` itself decides. Errors, indexing, and test
  runs never sync (`inko` would otherwise sync, and need the network, in
  each mirror): they use the project's `dep/` as it is, and a banner says
  when it's out of date or was never synced.
- **Introduce and Inline Variable, Introduce Constant, Move Statement, and
  Smart Enter** work from the file's tokens, following Inko's syntax
  reference: statements end at a line break unless the next line continues
  them (e.g. starts with `.`), and inlining parenthesizes values whose
  operators would otherwise bind differently (Inko's binary operators share one
  precedence and group left to right, and `and`/`or` bind more loosely). For
  the same reason, part of an operator chain (`60 - 60` in `total - 60 - 60`)
  isn't offered as an expression.
- **Change Signature, Introduce Parameter, Extract Method, and Inline
  Method** rewrite calls from their tokens, following Inko's rules: no default
  arguments (so new parameters need a value for calls to pass), named
  arguments after positional ones, no empty parentheses, parameters that can't
  be reassigned, and owned values that move unless borrowed. They update the
  calls the search finds (above). Without types, Extract Method leaves placeholders for
  types it can't work out, and `inko check` points out any left behind.
- **Quick fixes** act on the diagnostic IDs `inko check` reports
  (`invalid-symbol`, `unused-symbol`, `unused-result`, `missing-field`,
  `invalid-implementation`, `missing-trait`, `unreachable`); auto-import finds
  candidates in the `inko doc` definitions.
- **Implement and Override Methods** stub the methods `inko doc` lists as a
  trait's required or default ones, with bodies that call
  `panic('not implemented')` (in Inko's prelude), which compiles for any
  return type. **Go to Implementation and Super Method** follow the same
  trait links as the gutter icons.
- **Style-guide inspections** follow
  [Inko's style guide](https://docs.inko-lang.org/manual/latest/references/style/)
  where `inko fmt` doesn't. Removing an argument name is only offered where it
  keeps the meaning (the argument is in that parameter's position, after only
  positional ones), which needs the call to resolve to one method.
- **Tests** run the way `inko test` does (from the project root), but through
  a program Parakeet generates that reports each test to the IDE. It's built in
  a mirror of the project in the IDE's system directory (with its own build
  cache), so nothing is added to your project. It takes one name filter, so
  rerunning failed tests builds it once and runs it once per failed test.
- **Errors across the whole project** come from running `inko check` with no
  file, on a mirror of the saved project, after saves (once they pause for a
  second) and when the project opens. Unsaved edits are checked as you type in
  their own editor, as before.
- **Intentions** work from the file's tokens with the same operator rules as
  the refactorings. `if` ⇄ `match` only rewrites comparisons of a name or field
  to literals (so the value is the same each time it's read), and argument
  names are only removed when each named argument is in its parameter's
  position, since Inko accepts named arguments in any order.
- **Expression Type** uses Cuttlebone's type when hover's range is exactly
  the chosen expression. **"Specify type explicitly"** writes the compiler's
  type for the variable, and **"Remove explicit type"** is offered only when
  the value says its type itself (a literal, a cast, `Point(…)`, or
  `Type.method` returning that type, as Cuttlebone's type hints count it), so
  the annotation can't be what decides it, and the compiler's type for the
  value is the one written. Both use fresh answers only, since they write
  code, and types the compiler couldn't infer fully (with a `?`) never.
- **Move** rewrites imports from the usages Find Usages finds, and adds the
  imports the moved code needs the same way **imports on paste** does: copying
  records the imported and module-level names the copied code uses, and
  pasting adds the ones the target module lacks.
- **Completing unimported names** only offers `pub` definitions, since Inko
  can't import private ones, and ranks them below names already in scope.
- **The Structure view, breadcrumbs, folding, indentation, Extend
  Selection, Unwrap, and postfix completion** come from
  the file's own tokens, so they follow unsaved edits and work without `inko`.

## Requirements

- A JetBrains IDE, version 2025.3 or newer
- The bundled **TextMate Bundles** plugin enabled (it is by default)
- The `inko` executable. By default the plugin uses `inko` on `PATH`, then
  [ivm](https://github.com/inko-lang/ivm)'s default version. To pick an
  ivm-installed version or another executable for a project, use
  *Settings → Languages & Frameworks → Inko*.

- [Cuttlebone](#cuttlebone), for the compiler's types and definitions

## Installing

Parakeet is published on GitHub Releases, with a plugin repository your IDE can
check for updates:

1. In your IDE, open *Settings → Plugins*, click ⚙️, and choose *Manage Plugin
   Repositories…*
2. Click **+**, enter
   `https://github.com/setdef/Parakeet/releases/latest/download/updatePlugins.xml`,
   and click **OK**.
3. In the **Marketplace** tab, search for **Parakeet for Inko**, and install it.

The IDE then offers updates like any other plugin.

Alternatively, download `parakeet-<version>-signed.zip` from
[Releases](https://github.com/setdef/Parakeet/releases) and use *Settings →
Plugins → ⚙️ → Install Plugin from Disk…* (without automatic updates). To try a
local build, run `./gradlew buildPlugin` and install
`build/distributions/parakeet-<version>.zip` the same way.

## Cuttlebone

Cuttlebone is a language server for Inko that type checks with Inko's own
compiler. Parakeet requires it for everything that needs the compiler's
types; without it, those features are unavailable rather than guessed.

### Setting it up

1. Build and install Cuttlebone following its README (it needs Rust and
   LLVM 18), so that `cuttlebone` is on your `PATH` (Parakeet also checks Cargo's `bin`
   directory, where `cargo install` puts it).
2. Use the Inko version Cuttlebone links: `cuttlebone --version` prints it
   (e.g. `cuttlebone 0.1.0 (Inko 0.21.1)`), and it refuses other versions.
   The project's toolchain is in *Settings → Languages & Frameworks → Inko*.
3. Open an Inko file. Parakeet starts one `cuttlebone` per project, through
   IntelliJ's own LSP support, and stops it when the project closes.

To use another executable, set *Settings → Languages & Frameworks → Inko →
Cuttlebone → Executable*. The same page says whether Cuttlebone is running,
and if not, why: not found, a different Inko version, or stopped after
crashing. Parakeet says so there (and in `idea.log`) and nowhere else: no
popups. It restarts a crashed Cuttlebone up to 5 times in a row, waiting
1, 2, 4, 8 and 16 s, and IntelliJ's language services widget in the status
bar can restart it by hand.

### What asks Cuttlebone

Without Cuttlebone, every feature below is unavailable. With it:

| Feature | Cuttlebone's answer | Where Cuttlebone can't check the code being edited |
|---|---|---|
| Go to definition, and what's built on resolving a name (quick documentation, hierarchies, inspections, intentions, refactorings, …) | `textDocument/definition`: the declaration the compiler resolves | for navigation, documentation and hierarchies, a stale answer, else Parakeet's reading of the code; for what changes code (refactorings, intentions, quick fixes, imports), nothing |
| Find Usages, Rename, Call Hierarchy, usage counts, and the refactorings built on them | `textDocument/references`, once per search, for the whole project; a trait's method with its implementations' | each candidate's `textDocument/definition`, a file's at once; Find Usages alone adds Parakeet's reading, as possible usages, and Rename lists them as conflicts |
| Completion after `.` | `textDocument/completion` on the real buffer | Parakeet's reading |
| Parameter info | `textDocument/signatureHelp`: the resolved method's signature, with the receiver's type arguments, and the argument's parameter (named ones too). For a type's constructor (`Point(x: 1)`), which it has none for, the type's fields | Parakeet's reading |
| Parameter-name and type hints | `textDocument/inlayHint`, once for the whole file | none: hints only inform |
| Highlight usages | `textDocument/documentHighlight`: the same symbol by the compiler's ids, with assignments as writes | Parakeet's reading |
| Quick documentation | the resolved definition, plus hover's type at the caret | the definition resolved as for navigation (above) |
| Expression Type | hover's type, when its range is the expression | none, and it says why |
| "Specify type explicitly" and "Remove explicit type" | hover's fresh type, of the variable or of a value that says its type itself | not offered |
| Go to Type Declaration | `textDocument/typeDefinition`, through IntelliJ's LSP support | nothing |

Where Cuttlebone answers that there's nothing there (a name that doesn't
resolve, say), or has no answer for another reason (it's starting, or a
question ran out of time), nothing is shown: Parakeet's reading stands in
only for code being edited.

Errors and warnings come from `inko check`, with or without Cuttlebone, and
Parakeet turns off Cuttlebone's own diagnostics: IntelliJ's display of them
would have no quick fixes, wouldn't be in Inspect Code, and wouldn't mark
files that aren't open. Hints, parameter info and usage highlighting need
a Cuttlebone that answers them (since its fourth milestone); with an
older one, they're unavailable. Every other LSP feature IntelliJ offers is
turned off, so nothing shows twice. Cuttlebone answers from each open file's unsaved text,
and from saved text for the rest.

### Answers in code being edited

Since its fifth milestone, Cuttlebone answers in code that doesn't parse, or
has an error in a declaration, where an answer still holds. It says how in
each answer's status:

- **Fresh, from a repaired copy**: the documents as they are, with the lines
  in the way blank. Parakeet uses these like any fresh answer.
- **Stale**: from the last version that type checked, in text that hasn't
  changed since. Parakeet uses these only where an answer is shown and
  changes no code:

| Feature | Stale answer | Shown as |
|---|---|---|
| Parameter-name and type hints | used | the hint's tooltip says "possibly outdated" |
| Parameter info | used | "(possibly outdated)" after the signature |
| Quick documentation's "Type here" | used | "(possibly outdated)" after the type |
| Expression Type | used | "(possibly outdated)" after the type |
| Highlight usages | used | as a fresh answer: there's nowhere to mark it |
| Go to Type Declaration | used (IntelliJ's own) | as a fresh answer |
| Go to definition, documentation, Call and Type Hierarchy | used | as a fresh answer, but not one Rename at the caret takes |
| Rename, the refactorings, intentions, quick fixes and imports | not used: they change code from it | as no answer: nothing |
| Completion | not used: choosing an item inserts it (Cuttlebone never sends one) | as no answer: Parakeet's reading |

Where Cuttlebone has no answer in code being edited (the method being edited
after the edit; a file importing a module that doesn't exist, before any good
check; and, since its eighth milestone, only the method or item with a
declaration's error and those naming what it's in), the features in the
table above read the code themselves. Elsewhere in a project with a
declaration error, Cuttlebone's answers are fresh. A Cuttlebone before
milestone 5 never sends either kind, and has no answer in such code.

## Development

Tools are managed with [mise](https://mise.jdx.dev) (`mise install`).

| Command | What it does |
|---|---|
| `./gradlew runIde` | Launch a sandbox IDE with the plugin loaded |
| `./gradlew test` | Run all headless IDE tests, including integration tests against your installed `inko` and `cuttlebone` (`-PnoCuttlebone` skips the latter) |
| `./gradlew test -PunitOnly` | Run only the unit tests, which use a fake `inko` and a fake Cuttlebone and need nothing installed |
| `./gradlew typingReplay` | Type code into a std module at a person's speed against your installed `inko` and `cuttlebone`, and fail if typing responsiveness regresses ([the limits](docs/history/SESSION-typing-replay.md)); `PARAKEET_REPLAY_PROJECT` adds a project of your own |
| `./gradlew verifyPlugin` | Check compatibility with the oldest and newest supported IDEs |
| `./gradlew buildPlugin` | Build the installable zip |
| `scripts/check.sh [--full]` | Every check a commit must pass (`--full` adds the Plugin Verifier); see AGENTS.md. Cuttlebone's integration tests use `$CUTTLEBONE` or `cuttlebone` on PATH, and are skipped (with a message) without one; `--full` requires it |
| `scripts/install-hooks.sh` | Run `scripts/check.sh` on what's staged before each commit |

The files in `bundles/inko/` are copied unchanged from inko-lang/vscode
(v0.7.0, commit `46243e5`). To update them, copy `package.json`,
`language-configuration.json`, and `syntaxes/inko.tmLanguage.json` from a newer
release.

## CI and releases

GitHub Actions runs [`build.yml`](.github/workflows/build.yml) on every push to
`main` and every pull request:

- **Build and unit tests** (Linux): builds the plugin zip (uploaded as an
  artifact) and runs the unit tests, which use a fake `inko`.
- **Plugin Verifier** (Linux): checks compatibility with the oldest and newest
  supported IDEs.
- **Integration tests** on Linux and macOS, each against the newest Inko release
  and the oldest supported one (0.20.0), installed with ivm by
  [`ci/install-inko.sh`](ci/install-inko.sh). ivm builds Inko from source, so
  installed versions are cached. Cuttlebone isn't installed there, so its
  integration tests only run locally (`scripts/check.sh`).

Dependabot proposes weekly updates to Gradle dependencies and GitHub Actions.

### Releasing

Pushing a tag like `v0.11.0` (matching `version` in `gradle.properties`) runs
[`release.yml`](.github/workflows/release.yml): the full build above, then it
signs the plugin and creates a GitHub Release with the signed zip and
`updatePlugins.xml` (the plugin repository file IDEs check for updates). If a
`PUBLISH_TOKEN` secret exists, it also publishes to the JetBrains Marketplace.

One-time setup:

1. **Create a signing certificate**
   ([JetBrains' guide](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)),
   kept outside the repository:

   ```shell
   openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
   openssl rsa -in private_encrypted.pem -out private.pem
   openssl req -key private.pem -new -x509 -days 3650 -out chain.crt
   rm private.pem
   ```

2. **Add repository secrets** (*Settings → Secrets and variables → Actions*, or
   `gh secret set`): `CERTIFICATE_CHAIN` (contents of `chain.crt`),
   `PRIVATE_KEY` (contents of `private_encrypted.pem`), and
   `PRIVATE_KEY_PASSWORD`.

Without the signing secrets, a release fails at its first step and publishes
nothing.

To also publish to the JetBrains Marketplace later, upload the first version by
hand at [plugins.jetbrains.com](https://plugins.jetbrains.com/plugin/add) (it
only accepts automated uploads for plugins that already exist), then add a
`PUBLISH_TOKEN` secret (a
[Marketplace token](https://plugins.jetbrains.com/author/me/tokens)).

## History

Audits and session reports, oldest first:

- [Audit against Inko v0.21.1](docs/history/AUDIT.md)
- [Guardrails, and trusting the compiler's output](docs/history/SESSION-guardrails-and-compiler-output.md)
- [Fixing the rest of the audit](docs/history/SESSION-audit-fixes.md)
- [Connecting to Cuttlebone](docs/history/SESSION-cuttlebone-integration.md)
- [Who owns each LSP feature](docs/history/SESSION-lsp-ownership.md)
- [Stale and repaired answers](docs/history/SESSION-stale-and-repaired.md)
- [Requiring Cuttlebone](docs/history/SESSION-require-cuttlebone.md)
- [Replaying typing against Cuttlebone](docs/history/SESSION-typing-replay.md)
- [Declaration errors, and 0.12](docs/history/SESSION-declaration-errors-and-0.12.md)

## Icon

Parakeet's icons (a flat parakeet on a branch) are original to this plugin and
not derived from Inko's logo, which is licensed CC BY-NC-SA 4.0 and isn't used
here.

## License

[MPL-2.0](LICENSE), the same license as inko-lang/vscode, whose grammar and
language configuration this plugin bundles.
