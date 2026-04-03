# Contributing to Google AI Edge Gallery

Thank you for your interest in contributing! This guide explains how to get involved — whether you want to fix a bug, add a new skill, improve the Android app, or update documentation.

---

## Table of Contents

1. [Ways to Contribute](#ways-to-contribute)
2. [Before You Start](#before-you-start)
3. [Setting Up the Development Environment](#setting-up-the-development-environment)
4. [Contributing a Skill](#contributing-a-skill)
5. [Contributing Android Code](#contributing-android-code)
6. [Code Style](#code-style)
7. [Pull Request Process](#pull-request-process)
8. [Reporting Bugs](#reporting-bugs)
9. [Suggesting Features](#suggesting-features)
10. [Good First Issues](#good-first-issues)

---

## Ways to Contribute

| Type | Difficulty | Description |
|------|-----------|-------------|
| **New skill** | Easy | Add a new Agent Skill (JS, text-only, or native intent) |
| **Bug fix** | Easy–Medium | Fix a reported issue in the Android app |
| **Documentation** | Easy | Improve guides, fix typos, add examples |
| **New feature** | Medium–Hard | Add a new capability to the Android app |
| **Model allowlist** | Easy | Propose adding a new compatible model |

---

## Before You Start

- Search [existing issues](https://github.com/google-ai-edge/gallery/issues) and [pull requests](https://github.com/google-ai-edge/gallery/pulls) to avoid duplicate work.
- For significant changes, open an issue first to discuss the approach before writing code.
- By contributing, you agree that your contributions will be licensed under the [Apache 2.0 License](LICENSE).
- Please read and follow our [Code of Conduct](CODE_OF_CONDUCT.md).

---

## Setting Up the Development Environment

### Android

See [DEVELOPMENT.md](DEVELOPMENT.md) for full instructions on building the app locally, including HuggingFace OAuth configuration.

**Prerequisites:**
- Android Studio (latest stable)
- JDK 21
- Android SDK with API level 35
- A physical Android 12+ device (recommended for model testing)

**Quick start:**
```bash
git clone https://github.com/google-ai-edge/gallery.git
cd gallery/Android/src
./gradlew assembleDebug
```

**Running tests:**
```bash
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew connectedAndroidTest   # Instrumented tests (requires connected device)
```

---

## Contributing a Skill

Agent Skills are the easiest way to contribute. Skills live in the [`skills/`](skills/) directory and can be as simple as a single markdown file.

**Full documentation:** [`skills/README.md`](skills/README.md)

### Skill types

| Type | Complexity | How it works |
|------|-----------|-------------|
| **Text-only** | ⭐ Very easy | Only a `SKILL.md` — the LLM responds directly |
| **JavaScript** | ⭐⭐ Easy | JS runs in a hidden WebView; return JSON via `ai_edge_gallery_get_result` |
| **Webview UI** | ⭐⭐ Easy | JS + HTML displayed visually to the user |
| **Native Intent** | ⭐⭐ Easy | Triggers Android Intents (email, SMS, calendar) |

### Skill contribution steps

1. Fork the repository and create a branch: `git checkout -b skill/my-skill-name`
2. Create your skill directory: `skills/built-in/my-skill-name/` or `skills/featured/my-skill-name/`
3. Add `SKILL.md` (required) and `scripts/` (if needed)
4. Test the skill in the app by loading it via URL or local import
5. Submit a pull request with:
   - A brief description of what the skill does
   - A screenshot or short screen recording of it in action
   - The skill category (productivity, health, entertainment, etc.)

### Skill quality checklist

- [ ] `SKILL.md` has valid YAML frontmatter with `name` and `description`
- [ ] `description` clearly states when the LLM should trigger the skill
- [ ] Instructions are unambiguous and tested
- [ ] No hardcoded API keys (use the `require-secret` metadata for secrets)
- [ ] Works offline if no external API is used
- [ ] JS code handles errors and returns `{ error: "..." }` on failure

---

## Contributing Android Code

### Branch naming

```
feature/short-description    # New features
fix/issue-number-description # Bug fixes
docs/what-you-changed        # Documentation only
refactor/what-you-changed    # Refactoring
```

### Architecture overview

The app uses **MVVM** with **Jetpack Compose**, **Hilt** for dependency injection, and **Kotlin Coroutines + Flow** for async work. Key packages:

| Package | Purpose |
|---------|---------|
| `data/` | Models, repositories, DataStore |
| `ui/` | Compose screens and ViewModels |
| `customtasks/` | Custom task implementations (AgentChat, MobileActions, etc.) |
| `worker/` | Background WorkManager tasks |
| `runtime/` | LLM runtime helpers |

### Adding a new test

All new code should include unit tests where practical. Tests live in:
- `Android/src/app/src/test/` — JVM unit tests (preferred)
- `Android/src/app/src/androidTest/` — Instrumented tests (for Android framework dependencies)

Use **MockK** for mocking. See existing tests in `data/ModelTest.kt` and `ui/common/tos/TosViewModelTest.kt` for examples.

---

## Code Style

- Follow the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use **Jetpack Compose** for all new UI (no Views/XML layouts)
- Keep composables small and focused — extract sub-composables when a function exceeds ~80 lines
- Inject dependencies via **Hilt** — avoid passing `Context` through multiple layers
- Use `StateFlow` for UI state (not `LiveData`)
- String literals displayed to users must be in `res/values/strings.xml`
- Format code with `./gradlew ktlintFormat` before submitting (if the project adds ktlint)

---

## Pull Request Process

1. **Fork** the repository and create a branch from `main`
2. **Write tests** for new logic where applicable
3. **Ensure CI passes** — the build and unit tests must be green
4. **Keep PRs focused** — one logical change per PR makes review easier
5. **Fill in the PR template** — describe what changed and why
6. **Respond to review comments** promptly
7. A maintainer will merge once approved

PRs are squash-merged to keep the main branch history clean.

---

## Reporting Bugs

Use the [bug report template](https://github.com/google-ai-edge/gallery/issues/new?assignees=&labels=bug&template=bug_report.md&title=%5BBUG%5D).

Please include:
- Device model and Android version
- App version (Settings → About)
- Steps to reproduce
- Expected vs. actual behaviour
- ADB logs if available (see [Bug Reporting Guide](Bug_Reporting_Guide.md))

---

## Suggesting Features

Use the [feature request template](https://github.com/google-ai-edge/gallery/issues/new?assignees=&labels=enhancement&template=feature_request.md&title=%5BFEATURE%5D).

---

## Good First Issues

Look for issues tagged [`good first issue`](https://github.com/google-ai-edge/gallery/issues?q=is%3Aopen+is%3Aissue+label%3A%22good+first+issue%22) — these are well-scoped tasks suitable for new contributors.

Great starting points:
- **Adding a new skill** — see [`skills/README.md`](skills/README.md)
- **Fixing a hardcoded string** — move to `strings.xml`
- **Adding a unit test** for an existing ViewModel or data class
- **Improving documentation** — fixing typos, adding examples

---

*We look forward to your contributions!* 🎉
