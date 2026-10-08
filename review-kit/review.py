# Version 1.4
# review.py - run one text prompt on approved external reviewers, read-only, one reviewer at a time.
#
# Usage:
#   python review.py --prompt-file brief.txt --reviewers codex gemma3:1b phi4-mini --out OUTDIR
#   Reviewer names: codex | gemini | antigravity-ide (manual) | copilot | grok | mistral | codestral | perplexity |
#                   any other name is a local Ollama model
#
# Conventions
#   Encoding : UTF-8 without BOM for every file read and written
#   Time     : seconds
#   Size     : characters
#
# Safety model (rules from ~/.claude/CLAUDE.md, section "External reviewers")
#   - Reviewers must be named explicitly on the command line; there is no default list.
#   - Every reviewer runs read-only, inside an EMPTY scratch directory, so it cannot see the project.
#   - The prompt is scanned for secrets before anything is sent.
#   - Models from Chinese, Russian or Iranian vendors are refused.

import argparse
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

# =============================================================
# Parameters
# =============================================================
TIMEOUT_SEC = 900            # per reviewer; CPU-only local models are slow
MAX_PROMPT_CHARS = 60000     # refuse larger prompts; shard or compact first
DECIMALS = 1                 # rounding of reported seconds, applied at emission only
# Cloud reviewers approved by the user on 2026-10-08 (each still needs the per-day approval).
# Keys come ONLY from environment variables set by the user; they are never printed or stored.
# Model names are best guesses and NOT verified; override with the REVIEW_MODEL_<NAME> variable.
API_REVIEWERS = {
    "grok":       {"url": "https://api.x.ai/v1/chat/completions", "key_env": "XAI_API_KEY", "default_model": "grok-4", "no_code": False},
    "mistral":    {"url": "https://api.mistral.ai/v1/chat/completions", "key_env": "MISTRAL_API_KEY", "default_model": "mistral-large-latest", "no_code": False},
    "codestral":  {"url": "https://api.mistral.ai/v1/chat/completions", "key_env": "MISTRAL_API_KEY", "default_model": "codestral-latest", "no_code": False},
    "perplexity": {"url": "https://api.perplexity.ai/chat/completions", "key_env": "PERPLEXITY_API_KEY", "default_model": "sonar-pro", "no_code": True},
}
CODE_MARKERS = ("===== FILE:", "```")     # a prompt carrying these is treated as code
COPILOT_MAX_CHARS = 20000                 # the prompt travels on the command line (Windows limit about 32k)
COPILOT_DENIED_TOOLS = ("shell", "write")  # Copilot CLI runs with these tools denied, inside an empty directory
COPILOT_FALLBACK = Path.home() / "AppData" / "Local" / "Microsoft" / "WinGet" / "Packages" / "GitHub.Copilot_Microsoft.Winget.Source_8wekyb3d8bbwe" / "copilot.exe"
OLLAMA_URL = "http://127.0.0.1:11434/api/generate"   # localhost only; nothing leaves the machine
OLLAMA_NUM_CTX = 4096        # tokens; caps memory use of the KV cache on small machines
OLLAMA_KEEP_ALIVE = 0        # unload the model right after the answer

# Name fragments of banned model families (vendor in China, Russia or Iran). Matched case-insensitively.
BANNED_NAME_FRAGMENTS = (
    "qwen", "deepseek", "glm", "kimi", "yi-", "baichuan", "internlm", "minimax",
    "ernie", "hunyuan", "doubao", "chatglm", "gigachat", "yandex", "saiga",
)

# Patterns that indicate a secret in the prompt. Any match blocks the run.
SECRET_PATTERNS = (
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    r"\bghp_[A-Za-z0-9]{20,}",
    r"\bgithub_pat_[A-Za-z0-9_]{20,}",
    r"\bAKIA[0-9A-Z]{16}\b",
    r"\bAIza[0-9A-Za-z_\-]{30,}",
    r"\bsk-[A-Za-z0-9]{20,}",
    r"(?i)\b(password|passwd|storePassword|keyPassword|api[_-]?key|secret|token)\s*[:=]\s*\S{6,}",
)

# =============================================================
# Validation of parameters
# =============================================================
assert TIMEOUT_SEC > 0, "TIMEOUT_SEC must be positive"
assert MAX_PROMPT_CHARS > 0, "MAX_PROMPT_CHARS must be positive"
assert DECIMALS >= 0, "DECIMALS must not be negative"
assert OLLAMA_URL.startswith("http://127.0.0.1"), "Ollama must be reached on localhost only"
assert OLLAMA_NUM_CTX >= 512, "OLLAMA_NUM_CTX is too small to be useful"


def check_banned(name):
    lowered = name.lower()
    for fragment in BANNED_NAME_FRAGMENTS:
        if fragment in lowered:
            sys.exit("REFUSED: reviewer '%s' matches banned family '%s' (China/Russia/Iran rule)" % (name, fragment))


def check_prompt(text):
    if len(text) > MAX_PROMPT_CHARS:
        sys.exit("REFUSED: prompt has %d chars, limit is %d. Shard or compact it first." % (len(text), MAX_PROMPT_CHARS))
    for pattern in SECRET_PATTERNS:
        if re.search(pattern, text):
            sys.exit("REFUSED: prompt matches a secret pattern (%s). Remove it before sending." % pattern)


def build_command(name, scratch):
    """Return the argument list that runs reviewer `name` read-only with the prompt on stdin."""
    if name == "codex":
        exe = shutil.which("codex")
        if not exe:
            sys.exit("codex CLI not found")
        # read-only sandbox, scratch dir as root, '-' means read the prompt from stdin
        return [exe, "exec", "--sandbox", "read-only", "--skip-git-repo-check", "-C", str(scratch), "-"]
    if name == "gemini":
        exe = shutil.which("gemini")
        if not exe:
            sys.exit("gemini CLI not found")
        # plan mode is read-only; prompt arrives on stdin and -p adds the instruction
        return [exe, "--skip-trust", "--approval-mode", "plan", "-p", "Follow the instructions on stdin."]
    sys.exit("internal error: '%s' is served through the Ollama API, not a command line" % name)


def run_ollama(name, prompt):
    """Run a local model through the Ollama HTTP API (localhost only, no tools).
    Why not `ollama run`: the API lets us cap the context window and unload the model right after
    the answer (keep_alive 0), which avoids the out-of-memory crash seen on an 8 GB machine."""
    import json
    import urllib.request
    body = json.dumps({"model": name, "prompt": prompt, "stream": False, "keep_alive": OLLAMA_KEEP_ALIVE,
                       "options": {"num_ctx": OLLAMA_NUM_CTX}}).encode("utf-8")
    request = urllib.request.Request(OLLAMA_URL, data=body, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=TIMEOUT_SEC) as reply:
        return json.loads(reply.read().decode("utf-8")).get("response", "").strip()


def run_api_reviewer(name, prompt):
    """Call an OpenAI-compatible chat endpoint. The key is read from the environment and never shown."""
    import json
    import os
    import urllib.error
    import urllib.request
    spec = API_REVIEWERS[name]
    if spec["no_code"] and any(marker in prompt for marker in CODE_MARKERS):
        return "", "refused: %s receives questions and plans only, never code" % name
    key = os.environ.get(spec["key_env"], "")
    if not key:
        return "", "failed: environment variable %s is not set (the user creates and sets the key)" % spec["key_env"]
    model = os.environ.get("REVIEW_MODEL_" + name.upper(), spec["default_model"])
    body = json.dumps({"model": model, "messages": [{"role": "user", "content": prompt}], "temperature": 0.2}).encode("utf-8")
    request = urllib.request.Request(spec["url"], data=body, headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + key, "User-Agent": "review-kit/1.4"})
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_SEC) as reply:
            text = json.loads(reply.read().decode("utf-8"))["choices"][0]["message"]["content"].strip()
        return text, ("ok" if text else "failed: empty answer")
    except urllib.error.HTTPError as error:
        return "", "failed: HTTP %d from %s (model %s)" % (error.code, name, model)
    except Exception as error:  # network, JSON or shape problems; the message never contains the key
        return "", "failed: %s" % type(error).__name__


def run_copilot(prompt, scratch):
    """Run GitHub Copilot CLI non-interactively in an empty directory with shell and write tools denied."""
    exe = shutil.which("copilot") or (str(COPILOT_FALLBACK) if COPILOT_FALLBACK.exists() else None)
    if not exe:
        return "", "failed: copilot CLI not found"
    if len(prompt) > COPILOT_MAX_CHARS:
        return "", "refused: prompt has %d chars, Copilot limit is %d (command-line prompt)" % (len(prompt), COPILOT_MAX_CHARS)
    command = [exe, "-p", prompt]
    for tool in COPILOT_DENIED_TOOLS:
        command += ["--deny-tool", tool]
    done = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace",
                          timeout=TIMEOUT_SEC, cwd=str(scratch))
    text = done.stdout.strip()
    return text, ("ok" if done.returncode == 0 and text else "failed (exit %d): %s" % (done.returncode, done.stderr.strip()[-200:]))


def unload_ollama_models():
    """Unload every resident model so the next one starts with free memory."""
    import json
    import urllib.request
    try:
        with urllib.request.urlopen(OLLAMA_URL.replace("/api/generate", "/api/ps"), timeout=10) as reply:
            loaded = [m["name"] for m in json.loads(reply.read().decode("utf-8")).get("models", [])]
        for model in loaded:
            body = json.dumps({"model": model, "keep_alive": 0}).encode("utf-8")
            urllib.request.urlopen(urllib.request.Request(OLLAMA_URL, data=body,
                                                          headers={"Content-Type": "application/json"}), timeout=30).read()
    except OSError:
        pass  # server not reachable; the run itself will report it


def run_reviewer(name, prompt, scratch, out_dir):
    import time
    start = time.time()
    if name == "antigravity-ide":
        # manual reviewer. The IDE's `chat` command is NOT connected to its agent (checked 2026-10-08: no chat
        # activity in the IDE logs, no answer appears), so the prompt is saved to a file and put on the
        # clipboard; the user pastes it into the IDE agent panel (ask / planning mode, never edit or agent
        # actions on files) and pastes the answer back to the author.
        prompt_file = Path(out_dir) / "antigravity-ide_prompt.txt"
        prompt_file.write_text(prompt, encoding="utf-8")
        clip = shutil.which("clip")
        if clip:
            subprocess.run([clip], input=prompt.encode("utf-16-le"), timeout=30)  # clip.exe reads UTF-16 LE
        note = "(manual) prompt saved to %s%s; paste it into the Antigravity IDE agent panel and return the answer" % (
            prompt_file, " and copied to the clipboard" if clip else "")
        return note, "manual", round(time.time() - start, DECIMALS)
    if name in API_REVIEWERS or name == "copilot":
        try:
            if name == "copilot":
                text, status = run_copilot(prompt, scratch)
            else:
                text, status = run_api_reviewer(name, prompt)
        except subprocess.TimeoutExpired:
            text, status = "", "timeout after %d s" % TIMEOUT_SEC
        return text, status, round(time.time() - start, DECIMALS)
    if name not in ("codex", "gemini"):
        unload_ollama_models()
        try:
            text = run_ollama(name, prompt)
            return text, ("ok" if text else "failed: empty answer"), round(time.time() - start, DECIMALS)
        except Exception as error:  # report any API or network failure as a reviewer failure
            return "", "failed: %s" % error, round(time.time() - start, DECIMALS)
    try:
        done = subprocess.run(
            build_command(name, scratch), input=prompt, capture_output=True, text=True,
            encoding="utf-8", errors="replace", timeout=TIMEOUT_SEC, cwd=str(scratch))
        text = done.stdout.strip()
        # strip terminal spinner noise that ollama writes when stdout is not a tty
        text = re.sub(r"\x1b\[[0-9;?]*[A-Za-z]", "", text)
        text = re.sub(r"[⠀-⣿]", "", text).strip()
        status = "ok" if done.returncode == 0 and text else "failed (exit %d): %s" % (done.returncode, done.stderr.strip()[-300:])
    except subprocess.TimeoutExpired:
        text, status = "", "timeout after %d s" % TIMEOUT_SEC
    return text, status, round(time.time() - start, DECIMALS)


def main():
    parser = argparse.ArgumentParser(description="Run a text prompt on approved reviewers, read-only.")
    parser.add_argument("--prompt-file", required=True)
    parser.add_argument("--reviewers", nargs="+", required=True, help="codex | gemini | <ollama model name>")
    parser.add_argument("--out", required=True, help="directory that receives one <reviewer>.txt per reviewer")
    args = parser.parse_args()

    for name in args.reviewers:
        check_banned(name)
    prompt = Path(args.prompt_file).read_text(encoding="utf-8")
    check_prompt(prompt)

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    results = []
    with tempfile.TemporaryDirectory(prefix="review_scratch_") as scratch:
        for name in args.reviewers:
            text, status, seconds = run_reviewer(name, prompt, scratch, out_dir)
            safe = re.sub(r"[^A-Za-z0-9._-]", "_", name)
            (out_dir / (safe + ".txt")).write_text(text, encoding="utf-8")
            results.append((name, status, seconds, len(text)))
    for name, status, seconds, size in results:
        print("%-16s %-10s %8s s %7d chars" % (name, status[:10], seconds, size))
        if status not in ("ok", "manual"):
            print("    " + status)
    sys.exit(0 if all(r[1] in ("ok", "manual") for r in results) else 1)


if __name__ == "__main__":
    main()
