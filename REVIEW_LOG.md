# REVIEW_LOG - external review environment: change log and open items

Purpose: lets the review rules, tools and setup be continued and edited from any computer. Append new lines at the end of "Log"; never rewrite old lines. Keep "Open items" current. Format of a log line: `YYYY-MM-DD HH:MM | computer | TYPE | text`. Types: SYNC-CHECK, CHANGE, DECISION, SETUP, TEST, OPEN, DONE.

## Open items

1. Gemini CLI does not work with a personal Google account (IneligibleTierError). Needs a decision: API key, another account, or leave disabled.
2. Antigravity IDE (D:\Antigravity IDE, 1.107.0, works well as a workspace) is a manual reviewer: its `chat` command is not connected to its agent (no chat activity in IDE logs, no answer, checked 2026-10-08) and answers cannot be read by a script. review.py 1.3 saves the prompt to a file and the clipboard; the user pastes it into the IDE agent panel (ask / planning only) and pastes the answer back. Possible future improvement: find a supported agent entry point.
3. Antigravity 2 (v2.21.1, per-user install) shows a white window: its local server starts after about a minute while the window gives up after about 20 seconds. Suspected network path (MTU 1280 on the Hyper-V external switch, Tailscale). Network settings were not changed; needs the user's approval.
5. Cloud reviewers approved by the user on 2026-10-08 (Grok, GitHub Copilot CLI, Perplexity, Mistral / Codestral) are not yet usable: Copilot CLI is installed (winget) but not logged in; the other three need API keys set by the user as environment variables XAI_API_KEY, PERPLEXITY_API_KEY, MISTRAL_API_KEY. Server locations are reported by third parties only and must be shown as "not officially confirmed". Model names in review.py are unverified guesses.
6. Full list of remaining work, with owners (user / Claude) and a continuation prompt for another computer: see NEXT_STEPS.md (Drive master: H:\My Drive\Transit\Dev\ModelSetup\NEXT_STEPS.md). Sync sources are declared for all known projects.
7. Skill `global-rules-sync` (referenced by PointAndIdentify CLAUDE.md) is not installed.

## Log

2026-10-08 | BlackHP405 | SETUP | Installed Codex CLI 0.161.0 (logged in via ChatGPT), Ollama 0.40.1, pulled gemma3:1b, phi4-mini, llama3.2:3b. Gemini CLI 0.40.0 already present (blocked, see open item 1).
2026-10-08 | BlackHP405 | TEST | "OK" test: Codex ok, gemma3:1b ok, phi4-mini ok, llama3.2:3b ok after fix. Gemini failed.
2026-10-08 | BlackHP405 | CHANGE | Global rules "External reviewers" added to ~/.claude/CLAUDE.md; server-location check waived for Codex and Gemini/Antigravity; Russia/China/Iran ban kept.
2026-10-08 | BlackHP405 | CHANGE | Scripts created: review.py 1.1 (read-only reviewers, secret scan, banned vendors, Ollama through local API with num_ctx 4096 and unload after answer, fixes out-of-memory crash), compact.py 1.0 (balanced shards, none above 50%), sync_check.py 1.0 (read-only start-of-day check).
2026-10-08 | BlackHP405 | CHANGE | Rules 10-13 added: portable kit, git projects not committed by Claude, daily sync check, log discipline. FULL_PROMPT.md 2.0 saved.
2026-10-08 13:54 | BLACKHP405 | SYNC-CHECK | LOCAL CHANGES NOT COMMITTED (expected: guide, kit and log await the next normal project commit; 0 ahead, 0 behind origin)
2026-10-08 13:54 | BLACKHP405 | CHANGE | Added kit (review-kit/), EXTERNAL_REVIEW.md 2.0 with section 8 (sync, log, portable kit) and this log. Not committed; the project commits them next time.
2026-10-08 14:04 | BLACKHP405 | DECISION | User: Antigravity IDE (D:\Antigravity IDE) works well and is the Antigravity reviewer. review.py 1.2 adds the manual reviewer name antigravity-ide (hands the prompt to the IDE in ask mode). Answer cannot be read back by script.
2026-10-08 14:16 | BLACKHP405 | DECISION | Codex debate improvements approved and applied (FULL_PROMPT.md 2.1). Cross-computer master: H:\My Drive\Transit\Dev\ModelSetup. sync_check.py 1.1.
2026-10-08 14:17 | BLACKHP405 | SYNC-CHECK | LOCAL CHANGES NOT COMMITTED (0 ahead, 0 behind origin)
2026-10-08 14:21 | BLACKHP405 | DECISION | CTF-2026 name confirmed; FlightInfo master copy is H:\My Drive\Transit\Dev\FlightInfo. Antigravity IDE chat command found not connected to its agent: review.py 1.3 hands the prompt over by file and clipboard (manual reviewer).
2026-10-08 14:44 | BLACKHP405 | DECISION | User approved: Codex-style debate additions (fact checks, contradictions as disputed points, small models limited to summarizing/dedup/format) and default 3 rounds (5 only while a high-severity point is open); approved Grok, GitHub Copilot CLI, Perplexity, Mistral/Codestral. FULL_PROMPT 2.2, review.py 1.4 (adds copilot, grok, mistral, codestral, perplexity).
2026-10-08 14:44 | BLACKHP405 | SETUP | Copilot CLI 1.0.93 installed via winget; test run: reaches the login requirement (not logged in). API reviewers tested without keys: clear failure, nothing sent; Perplexity refuses code prompts.
2026-10-08 14:47 | BLACKHP405 | CHANGE | NEXT_STEPS.md created (open decisions, user-only tasks, Claude tasks T1-T7, continuation prompt for another computer). Open item 6 replaced.
2026-10-08 14:54 | BLACKHP405 | CHANGE | Main copy is now the Drive folder H:\My Drive\Transit\Dev\ModelSetup (scripts\modelsetup.ps1, INSTALL.md, UPDATE.md); kit refreshed from it (FULL_PROMPT 2.3).
