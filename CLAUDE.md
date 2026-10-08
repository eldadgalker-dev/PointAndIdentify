# PointAndIdentify - instructions for Claude Code

Version 1.6. This file is read automatically at the start of every session in this repository.
Full design history and decisions: `docs/IMPLEMENTATION_NOTES.md`. User-facing overview: `README.md`.

## 1. Communication

Language, tone, separation of fact from estimate and the right-to-left rule for chat replies are global rules (sections Communication with the user and Hebrew replies in `~/.claude/CLAUDE.md`). Not repeated here.

1. If a requested approach looks wrong, say so before executing.
2. Ambiguous request: act on the most reasonable reading and state the assumption. Ask only when readings lead to materially different results.
3. Do not send repository content to web searches or external tools without explicit approval (git/gh against this repository is approved).
4. Hebrew documents of this project (`README.md`, `docs/*.md`, `docs/index.html`) keep every paragraph, list item and table cell starting with a Hebrew word. Before committing new or changed text run `python ~/.claude/skills/global-rules-sync/audit.py lint FILE`. Existing lines stay unless the user asks for a cleanup.

## 2. Repository facts

1. Android app, Kotlin, Gradle (AGP 8.6, Kotlin 2.0, KSP, Room, CameraX). Package and applicationId: `com.galker.pointandidentify`. Never change the applicationId: installed apps could no longer update.
2. Layout: `app/` app code, `tools/` Python data tools, `data/` published data (manifest, terrain tiles, targets), `docs/` help page (GitHub Pages) and notes, `.github/workflows/` CI. The old Windows ZIP upload scripts were archived outside the repository; updates are made directly in this clone.
3. Workflows:
   1. `release.yml`: on every app-code change in `main` (and manual). Builds a signed APK, version `point.versionBase` + run number, creates tag and GitHub Release with `PointAndIdentify.apk` + `version.json`. Needs the four `POINT_*` secrets.
   2. `data.yml`: on data-tool changes, monthly, manual. Builds targets (Overpass), downloads elevation, cuts tiles, commits `data/`. Its bot commits to `main`.
   3. `data-check.yml`: verifies `data/manifest.json` against the files.
   4. `ci.yml`: on every push and pull request that touches app code, runs `gradle :app:testDebugUnitTest :app:assembleDebug` (no secrets). `release.yml` also runs `testDebugUnitTest` before it builds, so a failing test blocks the Release.
4. Signing key lives outside the repository (`%USERPROFILE%\PointAndIdentify-signing`). Never add keystores, passwords or tokens to the repository.
5. License: BSD 3-Clause (`LICENSE.txt`). Data under `data/` and `app/src/main/assets/` keeps third-party licenses (OSM ODbL, Terrain Tiles attribution).

## 3. Code rules

1. Code is English only: identifiers, comments, log text. No Hebrew characters in code files. User-facing text goes to `app/src/main/res/values/strings.xml`.
2. Every code file starts with the 3-line copyright header, then `Version X.Y`. Bump it on every change: minor for fixes/additions, major for rewrites or interface changes. New files start at 1.0.
3. Global parameters at the top under a `Parameters` block (`AppConfig.kt`, `TargetKind.kt`, script headers). Inline comments for logic and engineering reasoning. Validate parameters.
4. Write complete files; never leave "unchanged" placeholders or omitted sections.
5. If the `engineering-code-protocol` skill is installed, follow it for every code change.
6. Hebrew documents (`README.md`, `docs/*.md`, `docs/index.html`) stay right-to-left; keep their existing structure and numbering.

## 4. Work loop (run it without waiting for the user)

1. Start: `git pull --rebase origin main` (the data bot commits to `main`).
2. Change the code. Local checks that always work: `python -m py_compile tools/*.py` and `python tools/verify_manifest.py`. If an Android SDK and `gradlew` are present, also `./gradlew :app:testDebugUnitTest :app:assembleDebug`.
3. Commit with a clear English message and `git push origin main`.
4. Watch CI: `gh run list --limit 5`, then `gh run watch <id> --exit-status` for each run started by the push.
5. On failure: `gh run view <id> --log-failed`, fix the cause, go back to step 2. Stop after 5 failed rounds and report what is blocking.
6. Report to the user in Hebrew: what changed, CI results, Release version if one was published, and a protocol table per changed file (version, lines, previous lines, added, removed). Explain every removal larger than 10 lines.

## 5. Do not

1. Do not force-push or rewrite published history.
2. Do not edit files under `data/` by hand; change `tools/` and let `data.yml` rebuild.
3. Do not change the signing setup or the secret names without the user's approval.

## 6. Backlog (agreed with the user, not implemented yet)

1. Map mode on Google Maps SDK for Android (online only). Needs a Maps API key from the user, restricted to the package name and the SHA-1 of the release certificate. Layers: target markers coloured by visibility; field-of-view wedge and heading line; line of sight to the selected target; viewshed overlay computed from the DEM. Show the map when the camera elevation is below -55 deg and the camera view when above -35 deg (hysteresis). Unbind the camera in map mode to save battery.
2. (done) `release.yml` runs `testDebugUnitTest` before the release build.
3. Verify the Terrain Tiles attribution wording in `LICENSE.txt` against their attribution document.
4. First on-device test: confirm camera, sensors, overlay, update button; fix what fails.

## 7. Parameters for the global review and resource tools

Rules for external reviewers, token saving (`compact.py`, `minify.py`) and system resources are global (`~/.claude/CLAUDE.md`); this section holds only what is specific to this project. Full procedures: `EXTERNAL_REVIEW.md` in the repository root.

1. Project name for every review tool: `PointAndIdentify`. Paths to shard: `app tools`.
2. Never send `data/` (67 MB of generated tiles), keystores or workflow secrets.
