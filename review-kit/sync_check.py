# Version 1.1
# sync_check.py - read-only start-of-day check: is this project folder in sync with its source of truth?
#
# Usage:
#   python sync_check.py PROJECT_DIR
#
# What it checks
#   1. Git project : fetches origin (read-only), reports branch, ahead/behind, uncommitted and untracked files,
#                    and the commits that arrived from other systems.
#   2. Plain folder: compares the folder with the copy named in review-kit/sync_source.txt (a drive or network
#                    path, one per line) by relative path, size and SHA-256. Special lines: `drive-master`
#                    (this folder is itself the master on a synced drive) and `git: <relative dir>` (the source
#                    of truth is a git clone at that relative path). If the file is missing the result
#                    is UNKNOWN and the caller must ask the user where the master copy lives.
#   3. Open items  : prints the "Open items" section of REVIEW_LOG.md so the plan is synchronized too.
#
# The script never writes, never commits, never pushes and never changes the project.
#
# Conventions
#   Encoding : UTF-8 without BOM
#   Paths    : compared case-insensitively on Windows, with forward slashes in the report

import hashlib
import subprocess
import sys
from pathlib import Path

# =============================================================
# Parameters
# =============================================================
SOURCE_FILE = Path("review-kit") / "sync_source.txt"
LOG_FILE = "REVIEW_LOG.md"
OPEN_ITEMS_HEADING = "## Open items"
GIT_TIMEOUT_SEC = 60
MAX_REPORTED = 25                      # lines per category, so a huge drift stays readable
IGNORED_DIRS = {".git", "__pycache__", "build", ".gradle", ".idea", "node_modules", ".venv", "venv"}
IGNORED_NAMES = {"thumbs.db", ".ds_store", "desktop.ini"}

# =============================================================
# Validation of parameters
# =============================================================
assert GIT_TIMEOUT_SEC > 0, "GIT_TIMEOUT_SEC must be positive"
assert MAX_REPORTED > 0, "MAX_REPORTED must be positive"


def git(project, *args):
    done = subprocess.run(["git", "-C", str(project), *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=GIT_TIMEOUT_SEC)
    return done.returncode, done.stdout.strip(), done.stderr.strip()


def show(title, lines):
    print("  %s: %d" % (title, len(lines)))
    for line in lines[:MAX_REPORTED]:
        print("    " + line)
    if len(lines) > MAX_REPORTED:
        print("    ... %d more" % (len(lines) - MAX_REPORTED))


def check_git(project):
    verdict = "IN SYNC"
    code, _, err = git(project, "fetch", "origin", "--tags", "--prune")
    if code != 0:
        print("  fetch FAILED: " + err[:200])
        verdict = "UNKNOWN (cannot reach origin)"
    _, branch, _ = git(project, "rev-parse", "--abbrev-ref", "HEAD")
    code, counts, _ = git(project, "rev-list", "--left-right", "--count", "HEAD...@{upstream}")
    print("  branch: %s" % branch)
    if code == 0:
        ahead, behind = counts.split()
        print("  ahead of origin (unpushed commits): %s, behind origin (commits from other systems): %s" % (ahead, behind))
        if behind != "0":
            _, incoming, _ = git(project, "log", "--format=%h %an %ad %s", "--date=short", "HEAD..@{upstream}")
            show("commits to pull", incoming.splitlines())
            verdict = "BEHIND - pull before working"
        if ahead != "0" and verdict == "IN SYNC":
            verdict = "AHEAD - unpushed commits"
    else:
        print("  no upstream branch configured")
        verdict = "UNKNOWN (no upstream)"
    _, status, _ = git(project, "status", "--porcelain")
    changed = [l for l in status.splitlines() if not l.startswith("??")]
    untracked = [l[3:] for l in status.splitlines() if l.startswith("??")]
    show("uncommitted changes", changed)
    show("untracked files", untracked)
    if (changed or untracked) and verdict == "IN SYNC":
        verdict = "LOCAL CHANGES NOT COMMITTED"
    return verdict


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def snapshot(root):
    files = {}
    for path in root.rglob("*"):
        if path.is_file() and not (set(path.relative_to(root).parts[:-1]) & IGNORED_DIRS) and path.name.lower() not in IGNORED_NAMES:
            files[path.relative_to(root).as_posix().lower()] = path
    return files


def check_folder(project):
    source_file = project / SOURCE_FILE
    if not source_file.exists():
        print("  no %s: the master copy (drive / network folder) is not declared" % SOURCE_FILE.as_posix())
        return "UNKNOWN (declare the master copy in %s)" % SOURCE_FILE.as_posix()
    verdict = "IN SYNC"
    for line in source_file.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if line.lower() == "drive-master":
            # this folder IS the master on a synced drive; the drive client's own sync state cannot be read by a script
            print("  this folder is the master copy on a synced drive (drive client sync state not verifiable by script)")
            continue
        if line.lower().startswith("git:"):
            # the project's source of truth is a git clone located inside or next to this folder
            clone = (project / line[4:].strip()).resolve()
            print("  git clone: %s" % clone)
            clone_verdict = check_git(clone) if (clone / ".git").exists() else "UNKNOWN (clone not found)"
            if clone_verdict != "IN SYNC":
                verdict = clone_verdict
            continue
        master = Path(line)
        print("  master copy: %s" % master)
        if not master.is_dir():
            print("    not reachable from this computer")
            verdict = "UNKNOWN (master copy not reachable)"
            continue
        mine, theirs = snapshot(project), snapshot(master)
        only_here = sorted(set(mine) - set(theirs))
        only_there = sorted(set(theirs) - set(mine))
        differ = sorted(k for k in set(mine) & set(theirs) if mine[k].stat().st_size != theirs[k].stat().st_size
                        or digest(mine[k]) != digest(theirs[k]))
        newer_there = [k for k in differ if theirs[k].stat().st_mtime > mine[k].stat().st_mtime]
        show("only in this folder", only_here)
        show("only in the master copy", only_there)
        show("different content", ["%s (%s)" % (k, "master is newer" if k in newer_there else "this folder is newer") for k in differ])
        if only_here or only_there or differ:
            verdict = "OUT OF SYNC"
    return verdict


def print_open_items(project):
    log = project / LOG_FILE
    if not log.exists():
        print("  %s is missing: no recorded plan" % LOG_FILE)
        return
    lines, inside = log.read_text(encoding="utf-8").splitlines(), False
    print("  open items from %s:" % LOG_FILE)
    for line in lines:
        if line.startswith("## "):
            inside = line.strip().lower() == OPEN_ITEMS_HEADING.lower()
            continue
        if inside and line.strip():
            print("    " + line)


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: python sync_check.py PROJECT_DIR")
    project = Path(sys.argv[1]).resolve()
    if not project.is_dir():
        sys.exit("not a directory: %s" % project)
    print("PROJECT: %s" % project)
    verdict = check_git(project) if (project / ".git").exists() else check_folder(project)
    print_open_items(project)
    print("VERDICT: %s" % verdict)


if __name__ == "__main__":
    main()
