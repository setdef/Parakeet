# Parakeet for Inko

Parakeet is unofficial, community-made support for the
[Inko](https://inko-lang.org) programming language in JetBrains IDEs (IntelliJ
IDEA, RubyMine, WebStorm, RustRover, etc.).

This plugin is not affiliated with or endorsed by the Inko project. Please don't
report problems with this plugin to the Inko project; open an issue here instead.

## How this code came to exist

I use three words for how code comes to exist. Code I wrote, I typed by hand, with nothing generated. Code I wrought, an AI agent generated while I iterated with it, and I reviewed every change before committing it, so I own every diff. Code I requested, an agent built with little or no review from me, the way a dark factory runs without lights.

**Versions 0.1.0 through 0.5.0 are requested.**

## Features

- An **Inko** file type for `.inko` files, with its own icon
- Syntax highlighting, from the TextMate grammar in
  [inko-lang/vscode](https://github.com/inko-lang/vscode)
- Line comment toggling (`#`), bracket matching, and auto-closing brackets and quotes
- Compiler errors and warnings as you type, from `inko check`
- *Code → Reformat Code* (⌥⌘L / Ctrl+Alt+L) with `inko fmt`. To format on save,
  turn on *Settings → Tools → Actions on Save → Reformat code*.
- **Go to definition** (⌘-click / Ctrl-click, ⌘B / Ctrl+B) for types, traits,
  constants, functions, methods, fields, and modules, including the standard
  library and dependencies
- **Quick documentation** (F1, or hover): a definition's signature, where
  it's defined, and its doc comment
- **Go to Class / Go to Symbol** (⌘O / ⌥⌘O) and Search Everywhere for Inko
  types, traits, methods, and constants
- **Structure view**, breadcrumbs, and sticky lines for each file's
  declarations
- **Code folding** for blocks, comment runs, and imports
- **Completion** for keywords, names in scope, imports, members after `.`, and
  fields after `@`
- **Live templates**: `fn`, `fnp`, `type`, `typep`, `trait`, `impl`, `match`,
  `main`, `tests`, and `test`
- **TODO highlighting** and **spell checking** in comments
- **Test runner**: results per test file and test, failures with expected and
  actual values, and jumping to (or rerunning) a single test
- **Run and test from the gutter**: ▶ next to `fn async main`, next to
  `fn pub tests` in test files, and next to each `t.test('…')`
- **Run configurations** for `inko run`, `inko test`, `inko build`, and
  `inko check`, with clickable `file.inko:line` links in the output
- **New Project wizard** (*File → New → Project → Inko*) using `inko init`,
  optionally as a library or with a GitHub Actions workflow. The main module
  gets a valid module name (e.g. `from_intellij` for a project named
  "From IntelliJ"), which you can change.
- **Per-project toolchain** in *Settings → Languages & Frameworks → Inko*:
  auto-detect, ivm's default, any version ivm has installed, or a custom
  executable. With ivm installed, you can also install and remove Inko versions
  there, and a banner offers to install Inko when it's missing.

### How it works

The plugin understands Inko through the `inko` command rather than its own
compiler, so it always agrees with your installed Inko version.

- **Errors** are checked against your unsaved text: the plugin mirrors the
  project into a temporary directory (symlinking every source file) with your
  unsaved text in place of the file, then runs `inko check` there, so imports in
  both directions and dependencies resolve.
- **Go to definition, quick documentation, Go to Class/Symbol, and
  completion** use the definitions `inko doc --private --dependencies`
  reports, rebuilt in the background when you save. Locations are exact, but the
  plugin doesn't know types, so a method call like `x.to_string` offers every
  `to_string` it can't rule out, preferring types mentioned in the file. Local
  variables don't resolve.
- **Tests** run the way `inko test` does (from the project root), but through
  a program Parakeet generates that reports each test to the IDE. It's built in
  a mirror of the project in the IDE's system directory (with its own build
  cache), so nothing is added to your project.
- **The Structure view, breadcrumbs, and folding** come from the file's own
  tokens, so they follow unsaved edits and work without `inko`.

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

Pushing a tag like `v0.4.0` (matching `version` in `gradle.properties`) runs
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
