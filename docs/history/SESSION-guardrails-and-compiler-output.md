# Session: guardrails, and trusting the compiler's output

This session did two things:

1. Added guardrails, so future sessions can't quietly break this project's
   standing rules.
2. Fixed three findings from the [audit](AUDIT.md) about how Parakeet reads
   `inko`'s output: 1.8, 2.1 and 2.2.

All three findings were confirmed by a test that failed before its fix. None
was retracted.

Commits, oldest first:

| Commit | What |
|---|---|
| c1f3a27 | Guardrails: AGENTS.md, `scripts/check.sh`, the pre-commit hook, GuardrailsTest, and the audit moved to `docs/history/` |
| 5f27654 | 1.8: read compiler columns as grapheme clusters |
| bc5b679 | 2.2: keep every diagnostic, even when one isn't valid JSON |
| abe590c | 2.1: never sync dependencies when checking or indexing |
| (this report) | Audit status notes, this report, and its link in the README |

## Part 1: guardrails

The setup follows the shape of Cuttlebone's: a short AGENTS.md, one check
script, a versioned hook, and a test for the rules a machine can check.

### What's enforced, and how

| Rule (AGENTS.md) | Enforced by |
|---|---|
| Never open issues or PRs against Inko, or send it changes. Never modify a checkout of Inko's repository. | AGENTS.md only. A test can't see another repository or GitHub. |
| Never name anything after Inko. The product is "Parakeet for Inko". | `GuardrailsTest.testNothingIsNamedAfterInko` (see the details below). |
| Every bug fix starts with a test that fails without the fix. Never change a test just to make it pass. | AGENTS.md, and review. This session followed it for each finding (Part 2). |
| Run `scripts/check.sh` before committing. | `.githooks/pre-commit`, turned on by `scripts/install-hooks.sh`. |
| Use repository-relative paths. | `GuardrailsTest.testNoPathsFromOneMachine` (see the details below). |
| Reports go in `docs/history/`, linked from the README. | AGENTS.md. The README's History section links both reports. |

**The naming test** checks that:

- the `<name>` in plugin.xml is "Parakeet for Inko"
- the plugin ID contains `parakeet`
- the plugin ID, the Gradle project name, and every `@Storage` file name
  don't contain "inko"

The language ID and the `.inko` extension aren't checked, since they're
allowed.

**The paths test** fails on home-directory paths (macOS, Linux and Windows
user folders, and the tilde shorthand) in any tracked or untracked
(non-ignored) file. The patterns are in `GuardrailsTest`. One exception: the
tilde shorthand is allowed in `.github/workflows/`, where it's the hosted
runner's home, which the workflow uses to find ivm. A second test checks that the patterns recognise these
paths and leave `$HOME` and `user.home` alone. I made the paths test fail by
planting an untracked file containing a home path, then deleted that file.

### The pieces

- **`scripts/check.sh`** runs `./gradlew test --rerun`, then
  `./gradlew buildPlugin`. With `--full`, it also runs `./gradlew
  verifyPlugin`.
  - `--rerun` matters for two reasons. The integration tests depend on the
    installed `inko`, which Gradle doesn't track. Untracked files that the
    paths test reads aren't Gradle inputs either. Without `--rerun`, a cached
    test run hid the planted path.
- **`.githooks/pre-commit`** checks exactly what's being committed:
  - If anything is unstaged or untracked, it stashes those changes
    (`git stash push --keep-index --include-untracked`) and runs the checks.
  - Afterwards it puts them back, pass or fail (`git reset --hard`, then
    `git stash pop --index`).
  - It only pops a stash it made itself, and it says so if restoring fails.
  - With nothing to set aside, it just runs the checks.
- **`scripts/install-hooks.sh`** sets `core.hooksPath` to `.githooks`.

### How long `check.sh` takes

On this machine (Apple Silicon, warm Gradle daemon and caches):

| Run | Time |
|---|---|
| `./gradlew test buildPlugin`, before the guardrails | 95 s |
| `scripts/check.sh`, from the pre-commit hook | 78 s, 102 s, 108 s, 79 s |
| `scripts/check.sh --full` | 151 s |

Most of the time goes to starting the platform and to the integration tests
against the real `inko`. Plugin verification adds about a minute, so it stays
behind `--full`.

### Mutation testing (skipped)

This is what mutation testing would take for an IntelliJ platform plugin:

- **Tool:** PIT (pitest), through the `info.solidsoft.pitest` Gradle plugin,
  with its Kotlin support (`pitest-kotlin`).
- **Classpath:** it must run tests on the platform test classpath that the
  IntelliJ Platform Gradle Plugin's `test` task sets up: the sandbox, the
  bundled plugins, and the `idea.*` system properties.
  - PIT launches its own JVMs, so those properties and JVM arguments have to
    be copied into its `jvmArgs`.
  - It may also need `--add-opens` for the platform.
- **Speed:** `BasePlatformTestCase` starts a headless application in each
  JVM, so mutation runs are slow. Expect hours across all of `P/` unless the
  scope is narrowed.
  - Good targets: the pure logic (`InkoLexer`, `InkoTokenStream`,
    `InkoRewrites`, `InkoManifest`, `InkoBlake3`, `InkoCheck.parse`,
    `Inko.graphemeOffset`), with its plain `TestCase` tests.
- **Test selection:** exclude `*IntegrationTest` from mutation runs. Those
  need a real `inko`, and they can't be made deterministic under mutation.
- **Kotlin noise:** Kotlin's generated code (null checks, `when` defaults,
  data-class methods) produces many equivalent mutants. They need PIT's
  Kotlin filters, or an accepted baseline.

## Part 2: the three findings

### 1.8: compiler columns are grapheme clusters (confirmed, fixed in 5f27654)

**Tests.** `InkoCompilerPositionsTest` (unit) uses a file containing:

- `👩‍👩‍👦` (one grapheme, eight UTF-16 units)
- `éé` written with combining accents
- `漢字テスト`
- tabs
- `🇳🇱🇯🇵`
- CRLF and LF line endings

It uses the columns that `inko check --format json` 0.21.1 actually reported
for this file (Inko reports the same columns for CRLF and LF). The tests:

- `testCompilerColumnsAreGraphemeClusters`: every reported range must cover
  exactly the undefined name. Before the fix, the ZWJ line's range was
  `‍👦' `.
- `testColumnsPastTheLineStayOnIt`: positions past the end stay on their
  line.
- `testConsoleLinksUseCompilerColumns`: a `src/b.inko:LINE:COLUMN` console
  link opens on the name, in a CRLF file. This failed before the fix.

`InkoIntegrationTest.testHighlightsErrorsAfterEmojiAndCombiningAccents`
runs the real compiler. Before the fix, it highlighted `? é` instead of
`nope`.

**Fix.**

- `Inko.offsetOf` now walks the line in extended grapheme clusters
  (`Inko.graphemeOffset`, using `java.text.BreakIterator`), the way Inko's
  lexer counts columns (`graphemes(true)`). Tabs count as one column, and CR
  isn't part of a document line.
- Every caller that converts compiler positions goes through `offsetOf`:
  - diagnostics (`InkoCheckAnnotator`)
  - Inspect Code (`InkoProjectCheck`)
  - Optimize Imports (`InkoImportOptimizer`)
  - `inko doc` locations (`InkoResolver`, `InkoGoto`)
- The one caller that didn't use it, console links (`InkoFileLinkFilter`),
  now does too. It converts the column into the platform's character column.
  - An offset-based `OpenFileDescriptor` lost its offset inside
    `OpenFileHyperlinkInfo`, which broke the existing console-link test, so I
    used the line and character-column form instead.
  - The test runner's locations use lines only, so they were already right.

### 2.2: one malformed diagnostic drops all of them (confirmed, fixed in bc5b679)

**Tests.** `InkoDiagnosticsParseTest` (unit) uses `inko`'s real output for a
project with two errors:

- a syntax error in one file
- an invalid escape in another, whose message contains Rust's `\u{1}`

Three of its four tests failed before the fix, and the whole run parsed to
nothing:

- keeping both diagnostics
- decoding Rust's `\u{1f600}`, `\0` and `\u{7f}`
- keeping the good entries around one unreadable entry

The fourth, "non-JSON output gives no diagnostics", passed before and after.

`InkoIntegrationTest.testAnInvalidEscapeDoesNotHideOtherErrors` runs a real
project check. It expects both files' errors, and it failed before the fix.

**Fix.** `InkoCheck.parse` changed in two ways:

- It first rewrites Rust's `{:?}` escapes into JSON:
  - `\u{X}` becomes `\uXXXX`, or a surrogate pair above U+FFFF
  - `\0` becomes `\u0000`
  - `\'` becomes `'`
  - an invalid `\u{` keeps its backslash
- It then splits the array into its top-level objects (tracking strings, so
  braces inside messages don't count) and parses each on its own. An entry
  that still can't be read is skipped, and the rest are kept.

### 2.1: checks and indexing synced dependencies in throwaway mirrors (confirmed, fixed in abe590c)

**Tests.** `InkoDependencySyncIntegrationTest` runs the real `inko`, with a
fake `git` first on `PATH`. The fake records every call and fails like an
offline machine. The project depends on a package at an `example.invalid`
URL. Its tests:

- `testChecksUseTheProjectsDependenciesWhenTheManifestChangedSinceTheLastSync`
- `testChecksOfANeverSyncedProjectDoNotSync`
- `testProjectChecksDoNotSync`
- `testIndexingDoesNotSync`

All four failed before the fix. `inko` ran
`git clone --single-branch --branch v1.0.0 …` in the mirror, and the check
then produced no diagnostics at all.

`InkoManifestTest.testTheBannerSaysWhetherTheProjectWasEverSynced` failed
before the banner change.

**Fix.**

- A mirror no longer links the project's `dep/`. It gets its own `dep/`
  directory containing:
  - a link to each package the project has installed
  - a `hash` that matches the mirrored `inko.pkg`

  To `inko`, the mirror is up to date, so `check`, `doc` and the test runner's
  `build` never sync. The project's `dep/` is never written to.
- Packages that were never installed are simply missing. Their imports
  report "couldn't be found", and the other diagnostics are still shown.
- The editor banner now says which case applies:
  - "The dependencies in inko.pkg have never been synced, so errors and
    navigation can't see them."
  - "inko.pkg changed since the dependencies were last synced; errors and
    navigation use the synced ones."

  Syncing is still one click away, from the banner or *Tools → Inko
  Dependencies*.

**Decisions.**

- The test runner's persistent mirror uses the same `dep/`. An unsynced
  project's tests now fail to build with "couldn't be found" instead of
  syncing, as `inko test` would.
  - I chose this so that no Parakeet-started command touches the network
    unasked.
  - Running `inko test` in a terminal still syncs.
- `InkoMirrorTest.testClosingATemporaryMirrorNeverDeletesTheProject`
  asserted that the mirror's `dep` *is* a symlink, which encoded the old
  design. It now asserts that a package inside `dep` is a symlink.
  - The test's purpose, that closing a mirror never deletes the project, is
    unchanged and still checked.
  - This is the only existing test that changed this session.

## What's left unverified

- **The package data directory.** Even when nothing needs syncing, `inko`
  creates its package data directory and takes a lock file in it before
  comparing hashes (`compiler/src/pkg/sync.rs`, `sync_if_needed` calls
  `data_dir()`). This touches the user's data directory, not the network. It
  happens on every check, and only Inko can change it.
- **"No network."** This is shown by the fake `git`: a sync is the only
  thing in `check` and `doc` that runs `git`, and nothing else fetches.
  That rests on reading the source, not on watching the network.
- **Other platforms.** Everything ran on macOS with `inko` 0.21.1. I didn't
  run anything on Linux, or with Inko 0.20.0, the oldest version CI tests.
  Inko's lexer counted grapheme columns before 0.20 too, but I didn't check
  0.20's output.
- **Unusual combinations in the grapheme walk.** `BreakIterator`'s grapheme
  rules come from the JDK's Unicode version, and Inko's from the
  `unicode-segmentation` crate. They agree on every case tested, but could
  differ for newly added emoji sequences.
- **Escapes other than those `inko` emitted.** Inko's JSON may contain Rust
  escapes besides `\u{…}` and `\0`. Rust's `str` Debug output doesn't produce
  any others I know of, and the parser keeps unknown ones as they are rather
  than failing.
- **Hook conflicts.** The hook's stash and restore was exercised by real
  commits with untracked files. I didn't test a commit where restoring
  conflicts. The hook prints a message and leaves the stash in place for that
  case.
- **The other findings.** Findings 1.1–1.7, 1.9–1.16, and sections 3 and 4
  of the audit are out of scope here and untouched.
