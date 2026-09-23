# Style guide

This file is the project's style guide. It applies to everyone working in
this repo, human or AI — in particular, Claude Code reads this file
automatically (both interactively and in the `claude-issue-implement.yml`
automation), so it's the mechanism for keeping AI-authored changes consistent
with the rest of the codebase.

When editing existing code, match the conventions already used in the
surrounding file even if they differ slightly from a rule below — consistency
within a file beats a mechanical rule change. Don't do drive-by reformatting
of unrelated code.

## Kotlin style

- 4-space indentation, no tabs, no semicolons.
- Follow the
  [official Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html):
  `UpperCamelCase` for classes/objects, `lowerCamelCase` for functions and
  properties, `UPPER_SNAKE_CASE` for `const val`s.
- Prefer `val` over `var`; prefer immutable data classes over mutable state
  holders.
- Prefer expression bodies and trailing lambdas for short functions (see
  `Prefs.kt`, `SvgPage.kt`).
- Keep one feature per package (`model`, `storage`, `sync`, `drawing`,
  `export`, `ui`), matching the layout already described in `README.md`.
  Put new code in the package it belongs to rather than growing `ui` or
  `MainActivity.kt` into a catch-all.
- Write KDoc (`/** ... */`) on public classes and non-trivial functions when
  the *why* isn't obvious from the signature (see `DocumentRepository.kt`,
  `AppViewModel.kt`). Don't write comments that just restate what the code
  does. Prefer no comment at all over a comment that only says what an
  identifier already says.
- Use `runCatching`/sealed results over throwing for expected failure paths
  (e.g. sync and file I/O), following the existing pattern in
  `DocumentRepository.kt` and `DriveSync.kt`.

## Android & Compose best practices

- UI state lives in a `ViewModel` (see `AppViewModel.kt`); Composables stay
  stateless where practical and receive state + callbacks as parameters.
- Long-running work goes through `viewModelScope` / `Dispatchers.IO` via
  `withContext`, never blocking the main thread.
- Don't hold a `Context` longer than needed, and never store an `Activity`
  context in a singleton or long-lived field — use `Application` context
  (as `AndroidViewModel` does) or scope it to the call.
- Debounce/coalesce noisy operations that autosave or resync on every
  keystroke (see `saveCurrent()` in `AppViewModel.kt`) rather than writing on
  every change.
- Guard shared mutable resources (like the sync process) against overlap
  between foreground syncs and background `WorkManager` jobs (see
  `SyncCoordinator.kt`) instead of relying on timing.
- Prefer Material 3 components and theme tokens over hardcoded colours/sizes;
  support both light and dark theme for anything new.
- Minimise permissions and OAuth scopes requested (this app already scopes
  Drive access to `drive.file` — don't broaden it without a clear reason).
- New dependencies go in `gradle/libs.versions.toml` and are referenced via
  the version catalog (`libs.xxx`), not hardcoded coordinates in
  `build.gradle.kts`.

## Commit messages: Conventional Commits

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>[optional scope]: <short summary>

[optional body]

[optional footer(s)]
```

Common `type`s used in this repo:

- `feat` — a new user-facing feature
- `fix` — a bug fix
- `docs` — documentation only (README, this file, comments)
- `refactor` — code change that neither fixes a bug nor adds a feature
- `style` — formatting only, no code behaviour change
- `perf` — a performance improvement
- `test` — adding or correcting tests
- `chore` — build process, dependency, or tooling changes
- `ci` — changes to GitHub Actions workflows

Keep the summary short (~50 chars), imperative mood ("add", not "added" or
"adds"), and use the body to explain *why* when it's not obvious. Reference
the issue being closed (e.g. `Closes #8`) in the body/footer of the commit or
PR, not the summary line.
