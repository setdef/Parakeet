# Standing rules for Parakeet

Read README.md before changing anything. Tests or scripts/check.sh enforce
most of these rules. Don't weaken a check to get past it.

## Upstream

- Never open issues or PRs against Inko, and never send it changes: it has a
  strict no-LLM policy.
- Never modify any checkout of Inko's own repository. Read it, never write
  to it.

## Names

- Never name anything after Inko (trademark). The product is "Parakeet for
  Inko"; its ID, project and settings are `parakeet`
  (GuardrailsTest). The `inko` language ID and file extension are fine.

## Tests

- Every bug fix starts with a test that fails without the fix. Run it and
  see it fail before writing the fix.
- Never change a test just to make it pass, or to confirm your own finding.

## Before committing

- Run `scripts/check.sh` (tests and build). The pre-commit hook runs it on
  what's staged; set the hook up once with `scripts/install-hooks.sh`.
- Never bypass the hook: no `git commit --no-verify` (or `-n`), no
  disabling or editing the hook to get a commit through. If the hook fails,
  fix the cause, or make the check itself robust (e.g. to load, see below),
  in a commit that passes it.
- Run `scripts/check.sh --full` (adds the Plugin Verifier) before any
  release or session report.
- Keep Gradle's file-system watching off (`org.gradle.vfs.watch`): it
  missed changes here, so checks ran stale code (GuardrailsTest).

## Where checks count

- Correctness (every test's assertions, the sweeps' findings, the Plugin
  Verifier) counts everywhere: a failure is a bug until shown otherwise.
- Timing limits (the typing replay's latencies, tests' waits) count on CI:
  build.yml's Cuttlebone job, on every push to main and every pull
  request, and release.yml before it publishes. CI's machines run nothing
  else, so a timing failure there is a regression.
- Locally, under load, timing limits are advisory. A timing failure that
  passes when rerun alone is load: rerun it alone before believing it, say
  so in the commit or report, and never loosen a limit to get past load.
- The real-world sweeps run against the latest Cuttlebone on CI only for a
  release (release.yml, or build.yml run by hand with `real-world`), on
  the commits ci/real-world-projects.txt pins; locally, `check.sh --full`
  runs them on the checkouts beside this repository.

## Paths

- Use repository-relative paths in code, scripts and docs, never paths from
  one machine. GuardrailsTest fails on home-directory paths.

## Reports

- Audits and session reports go in docs/history/, linked from README.md.
