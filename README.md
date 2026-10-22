# Parakeet for Inko

Parakeet is unofficial, community-made support for the
[Inko](https://inko-lang.org) programming language in JetBrains IDEs (IntelliJ
IDEA, RubyMine, WebStorm, RustRover, etc.).

This plugin is not affiliated with or endorsed by the Inko project. Please don't
report problems with this plugin to the Inko project; open an issue here instead.

## How this code came to exist

I use three words for how code comes to exist. Code I wrote, I typed by hand, with nothing generated. Code I wrought, an AI agent generated while I iterated with it, and I reviewed every change before committing it, so I own every diff. Code I requested, an agent built with little or no review from me, the way a dark factory runs without lights.

**Versions 0.1.0 through 0.3.1 are requested.**

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
  executable

### How it works

The plugin understands Inko through the `inko` command rather than its own
compiler, so it always agrees with your installed Inko version.

- **Errors** are checked against your unsaved text: the plugin mirrors the
  project into a temporary directory (symlinking every source file) with your
  unsaved text in place of the file, then runs `inko check` there, so imports in
  both directions and dependencies resolve.
- **Go to definition** uses the definitions `inko doc --private --dependencies`
  reports, rebuilt in the background when you save. Locations are exact, but the
  plugin doesn't know types, so a method call like `x.to_string` offers every
  `to_string` it can't rule out, preferring types mentioned in the file. Local
  variables don't resolve.

## Requirements

- A JetBrains IDE, version 2025.2 or newer
- The bundled **TextMate Bundles** plugin enabled (it is by default)
- The `inko` executable. By default the plugin uses `inko` on `PATH`, then
  [ivm](https://github.com/inko-lang/ivm)'s default version. To pick an
  ivm-installed version or another executable for a project, use
  *Settings → Languages & Frameworks → Inko*.

## Installing from disk

1. Build the plugin: `./gradlew buildPlugin`
2. In your IDE, open *Settings → Plugins → ⚙️ → Install Plugin from Disk…*
3. Choose `build/distributions/parakeet-<version>.zip` and restart if asked.

## Development

Tools are managed with [mise](https://mise.jdx.dev) (`mise install`).

| Command | What it does |
|---|---|
| `./gradlew runIde` | Launch a sandbox IDE with the plugin loaded |
| `./gradlew test` | Run the headless IDE tests (needs `inko` installed) |
| `./gradlew verifyPlugin` | Check compatibility with the oldest and newest supported IDEs |
| `./gradlew buildPlugin` | Build the installable zip |

The files in `bundles/inko/` are copied unchanged from inko-lang/vscode
(v0.7.0, commit `46243e5`). To update them, copy `package.json`,
`language-configuration.json`, and `syntaxes/inko.tmLanguage.json` from a newer
release.

## Icon

Parakeet's icons (a flat parakeet on a branch) are original to this plugin and
not derived from Inko's logo, which is licensed CC BY-NC-SA 4.0 and isn't used
here.

## License

[MPL-2.0](LICENSE), the same license as inko-lang/vscode, whose grammar and
language configuration this plugin bundles.
