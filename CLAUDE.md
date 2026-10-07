# PointAndIdentify - instructions for Claude Code

Version 1.3. This file is read automatically at the start of every session in this repository.
Full design history and decisions: `docs/IMPLEMENTATION_NOTES.md`. User-facing overview: `README.md`.

## 1. Communication

1. Talk to the user in Hebrew, masculine form. Dry and professional: no praise, no preambles, no filler summaries.
2. Always separate: verified fact / estimate / unknown. Never fill gaps by guessing.
3. If a requested approach looks wrong, say so before executing.
4. Ambiguous request: act on the most reasonable reading and state the assumption. Ask only when readings lead to materially different results.
5. Do not send repository content to web searches or external tools without explicit approval (git/gh against this repository is approved).
6. Right-to-left display: start every paragraph, list item and table cell with a Hebrew word, so the interface aligns it to the right. Never start a line with a command, file name, number or English term; place such items later in the line. Keep code and commands in code blocks (left-to-right).

## 2. Repository facts

1. Android app, Kotlin, Gradle (AGP 8.6, Kotlin 2.0, KSP, Room, CameraX). Package and applicationId: `com.galker.pointandidentify`. Never change the applicationId: installed apps could no longer update.
2. Layout: `app/` app code, `tools/` Python data tools, `data/` published data (manifest, terrain tiles, targets), `docs/` help page (GitHub Pages) and notes, `.github/workflows/` CI. The old Windows ZIP upload scripts were archived outside the repository; updates are made directly in this clone.
3. Workflows:
   1. `release.yml`: on every app-code change in `main` (and manual). Builds a signed APK, version `point.versionBase` + run number, creates tag and GitHub Release with `PointAndIdentify.apk` + `version.json`. Needs the four `POINT_*` secrets.
   2. `data.yml`: on data-tool changes, monthly, manual. Builds targets (Overpass), downloads elevation, cuts tiles, commits `data/`. Its bot commits to `main`.
   3. `data-check.yml`: verifies `data/manifest.json` against the files.
   4. `ci.yml`: on every push and pull request that touches app code, runs `gradle :app:testDebugUnitTest :app:assembleDebug` (no secrets). It runs in parallel with `release.yml` and does not block it.
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
2. Make `release.yml` wait for the unit tests (run `testDebugUnitTest` before the release build), so a failing test blocks a Release. `ci.yml` already runs the tests on every push.
3. Verify the Terrain Tiles attribution wording in `LICENSE.txt` against their attribution document.
4. First on-device test: confirm camera, sensors, overlay, update button; fix what fails.

## 7. Global skills applied to this project

Global rules live in `~/.claude/CLAUDE.md` (Hebrew masculine and dry, RTL line starts, no data to servers in China, scripts before prose). This file adds to them and never weakens them. Skills in `~/.claude/skills`:

1. External review (`external-reviewers`): project name `PointAndIdentify`. Plans and designs go to reviewers as text. Code goes out only after the user approves it in chat, as disjoint shards (no reviewer above 50% of the project), with `--paths app tools`. Never include `data/` (67 MB of generated tiles), keystores or workflow secrets.
2. Compaction (`compact-for-reviewers`): run before any code leaves the machine and for large read-only files, e.g. `python ~/.claude/skills/compact-for-reviewers/compact.py app/src/main -o OUT.txt --map MAP.json`. Never edit from compacted text.
3. Hebrew documents (`global-rules-sync`): before committing `README.md`, `docs/*.md` or `docs/index.html`, run `python ~/.claude/skills/global-rules-sync/audit.py lint FILE` for new or changed text. Existing lines stay as they are unless the user asks for a cleanup.
