# Parakeet for Inko

Parakeet is unofficial, community-made support for the
[Inko](https://inko-lang.org) programming language in JetBrains IDEs (IntelliJ
IDEA, RubyMine, WebStorm, RustRover, etc.).

This plugin is not affiliated with or endorsed by the Inko project. Please don't
report problems with this plugin to the Inko project; open an issue here instead.

## How this code came to exist

I use three words for how code comes to exist. Code I wrote, I typed by hand, with nothing generated. Code I wrought, an AI agent generated while I iterated with it, and I reviewed every change before committing it, so I own every diff. Code I requested, an agent built with little or no review from me, the way a dark factory runs without lights.

**Version 0.1.0 is requested.**

## Features

- Syntax highlighting for `.inko` files
- Line comment toggling (`#`)
- Bracket matching, auto-closing pairs, and surrounding pairs

These come from the TextMate grammar and language configuration in
[inko-lang/vscode](https://github.com/inko-lang/vscode), loaded through the
IDE's built-in TextMate support.

## Requirements

- A JetBrains IDE, version 2025.2 or newer
- The bundled **TextMate Bundles** plugin enabled (it is by default)

## Installing from disk

1. Build the plugin: `./gradlew buildPlugin`
2. In your IDE, open *Settings → Plugins → ⚙️ → Install Plugin from Disk…*
3. Choose `build/distributions/parakeet-<version>.zip` and restart if asked.

## Development

Tools are managed with [mise](https://mise.jdx.dev) (`mise install`).

| Command | What it does |
|---|---|
| `./gradlew runIde` | Launch a sandbox IDE with the plugin loaded |
| `./gradlew test` | Run the headless IDE tests |
| `./gradlew verifyPlugin` | Check compatibility with the oldest and newest supported IDEs |
| `./gradlew buildPlugin` | Build the installable zip |

The files in `bundles/inko/` are copied unchanged from inko-lang/vscode
(v0.7.0, commit `46243e5`). To update them, copy `package.json`,
`language-configuration.json`, and `syntaxes/inko.tmLanguage.json` from a newer
release.

## License

[MPL-2.0](LICENSE), the same license as inko-lang/vscode, whose grammar and
language configuration this plugin bundles.
