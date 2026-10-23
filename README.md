# Parakeet for Inko

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

**Versions 0.1.0 through 0.10.0 are requested.**

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
  parameters, including the standard library and dependencies
- **Find Usages** (⌥F7 / Alt+F7) and **Rename** (⇧F6 / Shift+F6) across the
  project, including renaming a module's file; calls on values of unknown type
  are shown as possible usages
- **Highlight usages** of the name at the caret within the file
  (⌘⇧F7 / Ctrl+Shift+F7)
- **Parameter info** (⌘P / Ctrl+P) in calls, and parameter-name hints before
  positional arguments
- **Trait gutter icons** linking traits, their implementations, and the
  methods that implement them, with **Go to Implementation** (⌥⌘B /
  Ctrl+Alt+B) and **Go to Super Method** (⌘U / Ctrl+U)
- **Implement Methods** (⌃I / Ctrl+I) and **Override Methods** (⌃O / Ctrl+O)
  in `impl Trait for Type`, stubbing the trait's methods
- **Go to Test** (⌘⇧T / Ctrl+Shift+T) between `src/app/util.inko` and
  `test/app/test_util.inko`, and Create Test
- **Quick documentation** (F1, or hover): a definition's signature, where
  it's defined, and its doc comment; ⇧F1 opens standard library definitions
  on docs.inko-lang.org
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
  Alt+Delete), and moving files or directories and renaming directories
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
  members after `.` (narrowed to the receiver's type when it's known), and
  fields after `@`
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
- **Types aren't inferred.** A method call like `x.to_string` narrows to `x`'s
  type when it's written or obvious (`x: Foo`, `let x = Foo.new`, `self`), and
  otherwise offers every `to_string` it can't rule out, preferring types
  mentioned in the file. Parameter-name hints only appear when exactly one
  parameter list fits. Local variables and parameters are found from the
  file's own tokens.
- **Find Usages and Rename** search the IDE's word index for each name, then
  keep the occurrences that resolve to the declaration. A method call whose
  receiver type isn't known resolves to every candidate, so it's listed as a
  possible usage. **Call Hierarchy, usage counts, and Safe Delete** build on
  the same search (usage counts stop at 99+). **Moving files** rewrites the
  import paths of the modules that move.
- **Type Hierarchy** uses the trait implementations `inko doc` reports, and the
  traits each trait requires in its signature.
- **Dependencies need syncing** when `dep/hash` isn't the BLAKE3 hash of
  `inko.pkg`, which is how `inko` itself decides.
- **Introduce and Inline Variable, Move Statement, and Smart Enter** work from
  the file's tokens, following Inko's syntax reference: statements end at a
  line break unless the next line continues them (e.g. starts with `.`), and
  inlining parenthesizes values whose operators would otherwise bind
  differently (Inko's binary operators share one precedence, and `and`/`or`
  bind tighter).
- **Change Signature, Introduce Parameter, Extract Method, and Inline
  Method** rewrite calls from their tokens, following Inko's rules: no default
  arguments (so new parameters need a value for calls to pass), named
  arguments after positional ones, no empty parentheses, parameters that can't
  be reassigned, and owned values that move unless borrowed. They update the
  calls Find Usages finds, so calls on values of unknown type show in the
  preview to exclude. Without types, Extract Method leaves placeholders for
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
- **The Structure view, breadcrumbs, folding, indentation, usage
  highlighting, Extend Selection, Unwrap, and postfix completion** come from
  the file's own tokens, so they follow unsaved edits and work without `inko`.

## Requirements

- A JetBrains IDE, version 2025.2 or newer
- The bundled **TextMate Bundles** plugin enabled (it is by default)
- The `inko` executable. By default the plugin uses `inko` on `PATH`, then
  [ivm](https://github.com/inko-lang/ivm)'s default version. To pick an
  ivm-installed version or another executable for a project, use
  *Settings → Languages & Frameworks → Inko*.

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

## Development

Tools are managed with [mise](https://mise.jdx.dev) (`mise install`).

| Command | What it does |
|---|---|
| `./gradlew runIde` | Launch a sandbox IDE with the plugin loaded |
| `./gradlew test` | Run all headless IDE tests, including integration tests against your installed `inko` |
| `./gradlew test -PunitOnly` | Run only the unit tests, which use a fake `inko` and need nothing installed |
| `./gradlew verifyPlugin` | Check compatibility with the oldest and newest supported IDEs |
| `./gradlew buildPlugin` | Build the installable zip |

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
  installed versions are cached.

Dependabot proposes weekly updates to Gradle dependencies and GitHub Actions.

### Releasing

Pushing a tag like `v0.10.0` (matching `version` in `gradle.properties`) runs
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

## Icon

Parakeet's icons (a flat parakeet on a branch) are original to this plugin and
not derived from Inko's logo, which is licensed CC BY-NC-SA 4.0 and isn't used
here.

## License

[MPL-2.0](LICENSE), the same license as inko-lang/vscode, whose grammar and
language configuration this plugin bundles.
