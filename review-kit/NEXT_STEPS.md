# NEXT_STEPS - what remains, so the work can continue from any computer

Status date: 2026-10-08, written on computer BLACKHP405. Master copy: `H:\My Drive\Transit\Dev\ModelSetup\NEXT_STEPS.md` (Google Drive). Copies: `~/.claude/review-kit/NEXT_STEPS.md` and `review-kit/NEXT_STEPS.md` in every project. When you finish or add a step, edit the Drive master, copy it to the other places, and add a line to `REVIEW_LOG.md`.

עברית, תקציר: מה שנותר הוא חיבור סוקרי הענן (התחברות Copilot, מפתחות API), החלטה על Gemini CLI, ביצוע commit בפרויקטים שב-git, והרצת דיבייט אמיתי ראשון. כדי להמשיך במחשב אחר, הדבק את הפרומפט שבסעיף 5.

## 1. How to continue on another computer (5 minutes)

1. Make sure Google Drive is mounted (on the main computer it is `H:`; on another computer the drive letter may differ, then use the real path to `My Drive\Transit\Dev\ModelSetup`).
2. Open Claude Code inside the project folder you want to work on.
3. Paste the prompt from section 5 below. It reads this file, the log, runs the start-of-day sync check and continues from the open items.
4. First time on a new computer only: follow INSTALL.md in the Drive folder (one script installs everything; logins and keys are done by you), then paste the full setup prompt `review-kit/FULL_PROMPT.md`. To change or propagate anything later follow UPDATE.md.

## 2. Open decisions that need the user

| # | Item | What is needed |
|---|---|---|
| D1 | Gemini CLI is blocked for personal Google accounts (`IneligibleTierError`) | Choose: Google AI Studio API key (`GEMINI_API_KEY`, auth type `gemini-api-key`; assume the free tier may use prompts for product improvement, so plans only, check current terms before code), Vertex AI (Cloud project with billing), a Workspace / Code Assist license, or leave it disabled |
| D2 | Antigravity 2 desktop app (v2.21.1, per-user install) shows a white window | Its local server starts after about a minute, the window gives up after about 20 seconds. Suspected network path (MTU 1280 on the Hyper-V external switch, Tailscale). Changing network settings needs the user's approval. Not used for now |
| D3 | FlightInfo on Drive and `C:\Dev\FlightInfo` | Confirmed separate projects. Nothing to decide unless the user wants a different master copy for `C:\Dev\FlightInfo` (its source of truth is GitHub BlackHP17/FlightInfo, clone in the subfolder `FlightInfo`) |

## 3. Tasks that only the user can do (logins and keys)

1. GitHub Copilot CLI: it is installed (winget `GitHub.Copilot`, 1.0.93) but not logged in. Run `copilot`, then `/login`, or run `gh auth login`.
2. Create API keys and set them as user environment variables (never paste a key into the chat): `XAI_API_KEY` (Grok), `PERPLEXITY_API_KEY` (Perplexity), `MISTRAL_API_KEY` (Mistral and Codestral). Open a new terminal after setting them.
3. Optional: `GEMINI_API_KEY` if D1 is decided that way.
4. Codex CLI is logged in (ChatGPT account) on the main computer; on another computer run `codex login`.

## 4. Tasks for Claude

| # | Task | Notes |
|---|---|---|
| T1 | After the logins and keys, run the "OK" test on each new reviewer (Copilot, Grok, Mistral, Codestral, Perplexity) with a prompt that has no project content | `python review-kit/review.py --prompt-file <file> --reviewers <names> --out <dir>` |
| T2 | Verify the guessed model names in `review.py` (`grok-4`, `mistral-large-latest`, `codestral-latest`, `sonar-pro`) against each vendor's current model list; fix defaults or set `REVIEW_MODEL_<NAME>` | Names were not verified |
| T3 | Confirm server locations from official vendor pages: xAI (US / EU by model per third-party sources), Perplexity (US per third-party, zero retention per official docs), Mistral (EU by default per the Mistral Trust Center, not yet read), GitHub Copilot (not checked). Waived for Codex and Gemini / Antigravity | Show as "reported, not officially confirmed" until then |
| T4 | Run the first real review debate on a real plan, following section D of `review-kit/FULL_PROMPT.md` (default 3 rounds, escalate high-severity points) | Needs the user to supply the plan as text and approve the day's reviewers and any code shards |
| T5 | PointAndIdentify: the guide, `review-kit/` and `REVIEW_LOG.md` are in the working tree but not committed. The project commits them with its next normal commit (Claude does not commit them). `EXTERNAL_REVIEW.md` is already tracked and shows as modified | Files: `EXTERNAL_REVIEW.md`, `REVIEW_LOG.md`, `review-kit/` |
| T6 | Install or replace the missing skills named in `PointAndIdentify/CLAUDE.md`: `external-reviewers` and `compact-for-reviewers` are now covered by `review-kit/review.py` and `compact.py`; `global-rules-sync` (Hebrew document linter) does not exist yet | Update the project CLAUDE.md wording only with the user's approval |
| T7 | Antigravity IDE is a manual reviewer (its `chat` command is not connected to the agent). If a supported way to drive its agent is found, make it automatic | Pilot answer received and integrated on 2026-10-08 |

## 5. Continuation prompt (paste into Claude Code in the project folder)

```text
Continue the external review environment work from where it stopped. Reply in Hebrew, masculine, dry and professional; start every chat line, list item and table cell with a Hebrew word and never open a line with an English term, file name, number or command.

1. Find the Drive master folder (H:\My Drive\Transit\Dev\ModelSetup, or the equivalent path on this computer; if it is not reachable, ask me for the path). Read NEXT_STEPS.md, REVIEW_LOG.md, PROJECTS.md and review-kit/FULL_PROMPT.md there.
2. Compare the Drive master with ~/.claude (CLAUDE.md "External reviewers" section, EXTERNAL_REVIEW.md, review-kit/) and with this project's copies. If this computer has no setup, run the full setup prompt from FULL_PROMPT.md first. Report differences and do not overwrite anything without my approval.
3. Run the start-of-day sync check for this project (python review-kit/sync_check.py <project dir>) and log it in REVIEW_LOG.md.
4. Tell me: what was already done, what is out of sync, which items in NEXT_STEPS.md are still open, and which ones only I can do (logins, keys, decisions).
5. Show today's reviewer list with owners and server locations (location waived for Codex and Gemini / Antigravity) and wait for my approval before anything leaves this computer.
6. Work through the Claude tasks (T1 to T7) in order, tell me before each step that needs me, and record every change in REVIEW_LOG.md and in the three masters (~/.claude, Drive ModelSetup, this project's review-kit). Do not commit or push without my approval.
```

## 6. Reference

1. Reviewers and status: `SETTINGS_SNAPSHOT.md`. Projects and locations: `PROJECTS.md`. Rules text: `review-kit/global-rules.md`. Guide: `EXTERNAL_REVIEW.md`. Log and open items: `REVIEW_LOG.md`.
2. Known quirks: Ollama may not be on PATH in a terminal opened before the install; npm package `@github/copilot` fails to extract on the main computer (EXDEV), use winget; the main computer has 7.8 GB RAM and no GPU, so only small local models are practical; Antigravity IDE answers cannot be read by a script.
