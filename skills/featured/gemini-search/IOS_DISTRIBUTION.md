# Gemini Search iOS distribution path

Approved path: host the existing `skills/featured/gemini-search/` folder on GitHub Pages and load it in iOS Gallery with `Load skill from URL`.

Why this path:

- iOS Gallery can load shared JS skills from a hosted skill-folder URL.
- This repo does not contain the closed-source iOS app code or a live featured-skill allowlist hookup, so app-packaging or official featured-list distribution cannot be completed here.
- GitHub Pages serves the folder with the MIME types the hidden webview needs, while `github.com` and `raw.githubusercontent.com` do not.

Hosted folder URL:

- `https://johanesalxd.github.io/gallery/skills/featured/gemini-search`

Browser verification URL:

- `https://johanesalxd.github.io/gallery/skills/featured/gemini-search/SKILL.md`

Notes:

- `.nojekyll` already exists at the repo root so GitHub Pages can serve raw `SKILL.md` instead of rewriting it.
- Keep using the built-in `require-secret` flow for the Gemini API key; do not embed the key in the skill files or URL.

Jo iOS test steps:

1. Ensure GitHub Pages is enabled for the `main` branch / root of `johanesalxd/gallery`.
2. Open Google AI Edge Gallery on iPhone.
3. Enter Agent Skills, tap `+`, then choose `Load skill from URL`.
4. Paste `https://johanesalxd.github.io/gallery/skills/featured/gemini-search`.
5. Enable the imported skill, run a factual query, and provide the Gemini API key when prompted.

Fallback only if URL import still regresses on iOS:

- Import the same `gemini-search` folder from local storage instead of changing the skill implementation.