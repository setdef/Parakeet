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
- Run `scripts/check.sh --full` (adds the Plugin Verifier) before any
  release or session report.
- Keep Gradle's file-system watching off (`org.gradle.vfs.watch`): it
  missed changes here, so checks ran stale code (GuardrailsTest).

## Paths

- Use repository-relative paths in code, scripts and docs, never paths from
  one machine. GuardrailsTest fails on home-directory paths.

## Reports

- Audits and session reports go in docs/history/, linked from README.md.
