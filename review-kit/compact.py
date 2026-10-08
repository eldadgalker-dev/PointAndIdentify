# Version 1.0
# compact.py - collect project text files, strip noise, split into balanced shards for external reviewers.
#
# Usage:
#   python compact.py PATH [PATH ...] -o OUT.txt --map MAP.json [--shards 3]
#   Output files: OUT_part1.txt ... OUT_partN.txt (the "OUT" stem is taken from -o)
#
# Conventions
#   Encoding : UTF-8 without BOM for every file read and written
#   Size     : characters (shares are measured in characters)
#
# Rules enforced (from ~/.claude/CLAUDE.md, section "External reviewers")
#   - No shard may exceed MAX_SHARD_SHARE of the total.
#   - Keystores, keys, env files, data folders, build output and binaries are never included.
#   - Compacted text is for reading only; never edit from it. The map gives the original path and hash.

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

# =============================================================
# Parameters
# =============================================================
DEFAULT_SHARDS = 3            # 3 balanced shards keep every shard near 33%
MAX_SHARD_SHARE = 0.5         # no reviewer receives more than half of the project
MAX_FILE_BYTES = 400_000      # larger files are skipped and reported
EXCLUDED_DIRS = {".git", "data", "build", ".gradle", ".idea", "node_modules", "__pycache__",
                 "versions", "venv", ".venv", "dist", "out", ".cxx", "intermediates"}
EXCLUDED_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".env", ".zip", ".apk",
                     ".aab", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".so",
                     ".jar", ".class", ".bin", ".img", ".iso", ".tif", ".tiff", ".mp4", ".db"}
EXCLUDED_NAME_FRAGMENTS = ("secret", "password", "credential", "keystore", "signing", ".env", "id_rsa", "local.properties")

# =============================================================
# Validation of parameters
# =============================================================
assert DEFAULT_SHARDS >= 2, "DEFAULT_SHARDS must be at least 2"
assert 0 < MAX_SHARD_SHARE <= 0.5, "MAX_SHARD_SHARE must be in (0, 0.5]"
assert MAX_FILE_BYTES > 0, "MAX_FILE_BYTES must be positive"


def wanted(path):
    """Decide whether a file may be included."""
    if any(part in EXCLUDED_DIRS for part in path.parts):
        return False
    if path.suffix.lower() in EXCLUDED_SUFFIXES:
        return False
    lowered = path.name.lower()
    return not any(fragment in lowered for fragment in EXCLUDED_NAME_FRAGMENTS)


def collect(paths):
    files = []
    for raw in paths:
        root = Path(raw)
        candidates = [root] if root.is_file() else sorted(p for p in root.rglob("*") if p.is_file())
        files.extend(p for p in candidates if wanted(p))
    return files


def compact_text(text):
    """Strip trailing whitespace and collapse runs of blank lines. Comments are kept: reviewers need them."""
    lines = [line.rstrip() for line in text.replace("\r\n", "\n").split("\n")]
    return re.sub(r"\n{3,}", "\n\n", "\n".join(lines)).strip() + "\n"


def main():
    parser = argparse.ArgumentParser(description="Compact and shard project text for external reviewers.")
    parser.add_argument("paths", nargs="+")
    parser.add_argument("-o", "--out", required=True)
    parser.add_argument("--map", required=True)
    parser.add_argument("--shards", type=int, default=DEFAULT_SHARDS)
    args = parser.parse_args()
    if args.shards < 2:
        sys.exit("--shards must be at least 2")

    entries, skipped = [], []
    for path in collect(args.paths):
        if path.stat().st_size > MAX_FILE_BYTES:
            skipped.append((str(path), "too large"))
            continue
        try:
            raw = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            skipped.append((str(path), "not UTF-8 text"))
            continue
        body = compact_text(raw)
        entries.append({"path": str(path), "body": body, "orig_lines": raw.count("\n") + 1,
                        "sha256": hashlib.sha256(raw.encode("utf-8")).hexdigest()})
    if not entries:
        sys.exit("no files selected")

    total = sum(len(e["body"]) for e in entries)
    # greedy balancing: largest file first into the currently lightest shard
    shards = [{"size": 0, "items": []} for _ in range(args.shards)]
    for entry in sorted(entries, key=lambda e: -len(e["body"])):
        target = min(shards, key=lambda s: s["size"])
        target["items"].append(entry)
        target["size"] += len(entry["body"])
    worst = max(s["size"] for s in shards) / total
    if worst > MAX_SHARD_SHARE:
        sys.exit("a shard holds %.0f%% of the project (limit %.0f%%); add paths or raise --shards" % (worst * 100, MAX_SHARD_SHARE * 100))

    out = Path(args.out)
    file_map = {}
    for index, shard in enumerate(shards, start=1):
        target = out.with_name("%s_part%d%s" % (out.stem, index, out.suffix or ".txt"))
        chunks = []
        for item in sorted(shard["items"], key=lambda e: e["path"]):
            chunks.append("===== FILE: %s =====\n%s" % (item["path"], item["body"]))
            file_map[item["path"]] = {"shard": target.name, "orig_lines": item["orig_lines"], "sha256": item["sha256"]}
        target.write_text("\n".join(chunks), encoding="utf-8")
        print("%s  %d files  %d chars  %.0f%%" % (target.name, len(shard["items"]), shard["size"], 100 * shard["size"] / total))
    Path(args.map).write_text(json.dumps({"files": file_map, "skipped": skipped, "total_chars": total}, indent=2), encoding="utf-8")
    print("skipped: %d, total chars: %d" % (len(skipped), total))


if __name__ == "__main__":
    main()
